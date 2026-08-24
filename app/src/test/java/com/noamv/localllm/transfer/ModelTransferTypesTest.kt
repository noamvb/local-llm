package com.noamv.localllm.transfer

import com.noamv.localllm.engine.ModelAcquisitionException
import com.noamv.localllm.model.InsufficientModelStorageException
import com.noamv.localllm.model.InvalidModelRangeException
import com.noamv.localllm.model.ModelBackend
import com.noamv.localllm.model.ModelBuild
import com.noamv.localllm.model.ModelChecksumException
import com.noamv.localllm.model.ModelNetworkException
import com.noamv.localllm.model.ModelPromotionException
import com.noamv.localllm.model.ModelStorageException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ModelTransferTypesTest {
    private val descriptor = ModelTransferDescriptor(
        role = ModelRole.WRITER,
        modelId = "writer-test",
        modelName = "Writer test model",
        expectedBytes = 1_000L,
    )

    @Test
    fun `default policy requires validated internet unmetered wifi`() {
        val allowed = snapshot(validated = true, internet = true, metered = false, wifi = true)
        assertEquals(
            TransferNetworkDecision.Allowed,
            evaluateTransferNetwork(TransferNetworkPolicy.UNMETERED_WIFI, allowed),
        )

        listOf(
            snapshot(validated = false, internet = true, metered = false, wifi = true),
            snapshot(validated = true, internet = false, metered = false, wifi = true),
            snapshot(validated = true, internet = true, metered = true, wifi = true),
            snapshot(validated = true, internet = true, metered = false, wifi = false),
        ).forEach { rejected ->
            assertTrue(
                evaluateTransferNetwork(
                    TransferNetworkPolicy.UNMETERED_WIFI,
                    rejected,
                ) is TransferNetworkDecision.Blocked,
            )
        }
    }

    @Test
    fun `one-run override relaxes metered and transport only`() {
        assertEquals(
            TransferNetworkDecision.Allowed,
            evaluateTransferNetwork(
                TransferNetworkPolicy.ALLOW_METERED_ONCE,
                snapshot(validated = true, internet = true, metered = true, wifi = false),
            ),
        )
        assertTrue(
            evaluateTransferNetwork(
                TransferNetworkPolicy.ALLOW_METERED_ONCE,
                snapshot(validated = false, internet = true, metered = true, wifi = false),
            ) is TransferNetworkDecision.Blocked,
        )
        assertTrue(
            evaluateTransferNetwork(
                TransferNetworkPolicy.ALLOW_METERED_ONCE,
                snapshot(validated = true, internet = false, metered = true, wifi = false),
            ) is TransferNetworkDecision.Blocked,
        )
    }

    @Test
    fun `byte arithmetic clamps partial current and remaining values`() {
        assertEquals(
            TransferByteSnapshot(1_000, 400, 650, 250, 350),
            TransferByteSnapshot.create(1_000, 400, 650),
        )
        assertEquals(
            TransferByteSnapshot(1_000, 1_000, 0, 0, 1_000),
            TransferByteSnapshot.create(1_000, 2_000, -4),
        )
    }

    @Test
    fun `complete partial selects local promotion while missing bytes require network`() {
        val coordinator = ModelTransferStatusCoordinator(descriptor)
        coordinator.begin(1, TransferNetworkPolicy.UNMETERED_WIFI, partialBytes = 1_000)
        assertEquals(
            TransferAcquisitionPath.LOCAL_VERIFY_AND_PROMOTE,
            acquisitionPath(coordinator.status.value),
        )

        coordinator.begin(2, TransferNetworkPolicy.UNMETERED_WIFI, partialBytes = 999)
        assertEquals(
            TransferAcquisitionPath.NETWORK_REQUIRED,
            acquisitionPath(coordinator.status.value),
        )
    }

    @Test
    fun `terminal status ignores late progress and stale sessions`() {
        val coordinator = ModelTransferStatusCoordinator(descriptor)
        coordinator.begin(4, TransferNetworkPolicy.UNMETERED_WIFI, partialBytes = 100)
        coordinator.publish(4, ModelTransferPhase.DOWNLOADING, 500)
        coordinator.cancel(4, TransferStopReason.OWNER_CANCELLED)
        coordinator.publish(4, ModelTransferPhase.INSTALLING, 1_000)
        coordinator.fail(3, IOException("stale"))

        assertEquals(ModelTransferPhase.CANCELLED, coordinator.status.value.phase)
        assertEquals(500, coordinator.status.value.bytes.availableBytes)
        assertNull(coordinator.status.value.failureCategory)
    }

    @Test
    fun `exact storage refresh updates terminal bytes but never a newer session`() {
        val coordinator = ModelTransferStatusCoordinator(descriptor)
        coordinator.begin(30, TransferNetworkPolicy.UNMETERED_WIFI, partialBytes = 100)
        coordinator.publish(30, ModelTransferPhase.DOWNLOADING, 105, 5)
        coordinator.cancel(30, TransferStopReason.OWNER_CANCELLED)

        // A same-percent disk write discovered after cancellation becomes terminal truth.
        coordinator.refreshStorageBytes(30, availableBytes = 109, transferredThisRunBytes = 9)
        assertEquals(109L, coordinator.status.value.bytes.availableBytes)
        assertEquals(891L, coordinator.status.value.bytes.remainingBytes)
        assertEquals(9L, coordinator.status.value.bytes.transferredThisRunBytes)

        coordinator.begin(31, TransferNetworkPolicy.UNMETERED_WIFI, partialBytes = 200)
        coordinator.refreshStorageBytes(30, availableBytes = 999, transferredThisRunBytes = 899)
        assertEquals(31L, coordinator.status.value.sessionId)
        assertEquals(200L, coordinator.status.value.bytes.availableBytes)
    }

    @Test
    fun `committed promotion overrides same-session cancellation with exact storage truth`() {
        val coordinator = ModelTransferStatusCoordinator(descriptor)
        coordinator.begin(32, TransferNetworkPolicy.UNMETERED_WIFI, partialBytes = 100)
        coordinator.cancel(32, TransferStopReason.SERVICE_TIMEOUT)

        coordinator.completeCommittedPromotion(
            sessionId = 32,
            availableBytes = 1_000,
            transferredThisRunBytes = 900,
        )

        assertEquals(ModelTransferPhase.COMPLETED, coordinator.status.value.phase)
        assertEquals(1_000L, coordinator.status.value.bytes.availableBytes)
        assertEquals(0L, coordinator.status.value.bytes.remainingBytes)
        assertEquals(900L, coordinator.status.value.bytes.transferredThisRunBytes)
        assertNull(coordinator.status.value.stopReason)

        coordinator.begin(33, TransferNetworkPolicy.UNMETERED_WIFI, partialBytes = 200)
        coordinator.completeCommittedPromotion(32, 1_000, 900)
        assertEquals(33L, coordinator.status.value.sessionId)
        assertEquals(ModelTransferPhase.STARTING, coordinator.status.value.phase)
    }

    @Test
    fun `cancellation winner prevents promotion`() {
        val arbiter = PromotionCommitArbiter()
        var promoted = false

        assertTrue(arbiter.cancel())
        assertFalse(arbiter.commit { promoted = true })

        assertFalse(promoted)
        assertEquals(PromotionCommitState.CANCELLED, arbiter.state())
    }

    @Test
    fun `promotion claim makes concurrent cancellation lose without waiting for file work`() {
        val arbiter = PromotionCommitArbiter()
        val promotionEntered = CountDownLatch(1)
        val releasePromotion = CountDownLatch(1)
        var committed = false
        val promotion = Thread {
            committed = arbiter.commit {
                promotionEntered.countDown()
                check(releasePromotion.await(5, TimeUnit.SECONDS))
            }
        }
        promotion.start()
        assertTrue(promotionEntered.await(5, TimeUnit.SECONDS))

        val before = System.nanoTime()
        assertFalse(arbiter.cancel())
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before)
        assertTrue("timeout cancellation must not await file I/O", elapsedMillis < 1_000L)

        releasePromotion.countDown()
        promotion.join(5_000L)
        assertFalse(promotion.isAlive)
        assertTrue(committed)
        assertEquals(PromotionCommitState.COMMITTED, arbiter.state())
    }

    @Test
    fun `failed promotion owns failure rather than cancellation`() {
        val arbiter = PromotionCommitArbiter()

        val failure = runCatching {
            arbiter.commit { throw IOException("move") }
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertFalse(arbiter.cancel())
        assertEquals(PromotionCommitState.FAILED, arbiter.state())
    }

    @Test
    fun `uncommitted promotion terminal snapshot remains a storage failure`() {
        val coordinator = ModelTransferStatusCoordinator(descriptor)
        coordinator.begin(34, TransferNetworkPolicy.UNMETERED_WIFI, partialBytes = 1_000)
        coordinator.publish(34, ModelTransferPhase.INSTALLING, 1_000, 0)
        coordinator.refreshStorageBytes(34, availableBytes = 1_000, transferredThisRunBytes = 0)
        coordinator.fail(34, ModelPromotionException(build, IOException("missing target")))

        assertEquals(ModelTransferPhase.FAILED, coordinator.status.value.phase)
        assertEquals(
            ModelTransferFailureCategory.STORAGE,
            coordinator.status.value.failureCategory,
        )
    }

    @Test
    fun `timeout and critical trim remain distinct terminal reasons`() {
        val coordinator = ModelTransferStatusCoordinator(descriptor)
        coordinator.begin(7, TransferNetworkPolicy.UNMETERED_WIFI, partialBytes = 100)
        coordinator.cancel(7, TransferStopReason.SERVICE_TIMEOUT)
        assertEquals(ModelTransferPhase.TIMED_OUT, coordinator.status.value.phase)
        assertEquals(TransferStopReason.SERVICE_TIMEOUT, coordinator.status.value.stopReason)

        coordinator.begin(8, TransferNetworkPolicy.UNMETERED_WIFI, partialBytes = 200)
        coordinator.cancel(8, TransferStopReason.CRITICAL_MEMORY)
        assertEquals(ModelTransferPhase.CANCELLED, coordinator.status.value.phase)
        assertEquals(TransferStopReason.CRITICAL_MEMORY, coordinator.status.value.stopReason)
    }

    @Test
    fun `unknown preflight notification is neutral and indeterminate`() {
        val pending = ModelTransferStatus(
            sessionId = 1,
            descriptor = descriptor.copy(
                modelId = "pending",
                modelName = "Selected writer model",
                expectedBytes = 0,
            ),
            phase = ModelTransferPhase.STARTING,
        )

        val presentation = notificationPresentation(pending)

        assertEquals("Starting transfer", presentation.content)
        assertTrue(presentation.indeterminate)
        assertFalse(presentation.expandedText.contains("Expected:"))
        assertFalse(presentation.expandedText.contains("0 bytes"))
        assertTrue(presentation.showCancel)
    }

    @Test
    fun `known transfer notification reports exact neutral byte evidence`() {
        val coordinator = ModelTransferStatusCoordinator(descriptor)
        coordinator.begin(2, TransferNetworkPolicy.UNMETERED_WIFI, partialBytes = 200)
        coordinator.publish(
            sessionId = 2,
            phase = ModelTransferPhase.DOWNLOADING,
            availableBytes = 650,
            transferredThisRunBytes = 450,
        )

        val presentation = notificationPresentation(coordinator.status.value)

        assertTrue(presentation.expandedText.contains("Role: Writer"))
        assertTrue(presentation.expandedText.contains("Expected: 1000 bytes"))
        assertTrue(presentation.expandedText.contains("Partial at start: 200 bytes"))
        assertTrue(presentation.expandedText.contains("Transferred this run: 450 bytes"))
        assertTrue(presentation.expandedText.contains("Remaining: 350 bytes"))
        assertFalse(presentation.expandedText.contains("prompt"))
        assertFalse(presentation.expandedText.contains("fact"))
    }

    @Test
    fun `wrapped acquisition failures preserve specific typed categories`() {
        val cases = listOf(
            ModelNetworkException(build, IOException("offline")) to
                ModelTransferFailureCategory.NETWORK,
            TransferNetworkMonitorException(SecurityException("callback limit")) to
                ModelTransferFailureCategory.NETWORK,
            InsufficientModelStorageException(20, 10, build) to
                ModelTransferFailureCategory.INSUFFICIENT_STORAGE,
            ModelStorageException("disk") to ModelTransferFailureCategory.STORAGE,
            ModelPromotionException(build, IOException("move")) to
                ModelTransferFailureCategory.STORAGE,
            ModelChecksumException("expected", "actual", build) to
                ModelTransferFailureCategory.CHECKSUM,
            InvalidModelRangeException(null, 1, 2, build) to
                ModelTransferFailureCategory.PROTOCOL,
        )

        cases.forEach { (cause, expected) ->
            assertEquals(expected, classifyTransferFailure(ModelAcquisitionException(build, cause)))
        }
        assertEquals(
            ModelTransferFailureCategory.CANCELLED,
            classifyTransferFailure(CancellationException("cancelled")),
        )
        assertEquals(
            ModelTransferFailureCategory.MEMORY,
            classifyTransferFailure(ModelAcquisitionException(build, OutOfMemoryError("oom"))),
        )
        assertEquals(
            ModelTransferFailureCategory.INTERNAL,
            classifyTransferFailure(ModelAcquisitionException(build, IllegalStateException("x"))),
        )
    }

    @Test
    fun `setup failure creates one typed terminal status when begin could not publish`() {
        val coordinator = ModelTransferStatusCoordinator(descriptor)

        coordinator.failSetup(
            sessionId = 19,
            policy = TransferNetworkPolicy.ALLOW_METERED_ONCE,
            error = ModelNetworkException(build, IOException("admission")),
        )

        assertEquals(19L, coordinator.status.value.sessionId)
        assertEquals(ModelTransferPhase.FAILED, coordinator.status.value.phase)
        assertEquals(
            ModelTransferFailureCategory.NETWORK,
            coordinator.status.value.failureCategory,
        )
    }

    @Test
    fun `validator policy loss remains exact through acquisition wrapper`() {
        val wrapped = ModelAcquisitionException(
            build,
            TransferNetworkPolicyException(
                TransferNetworkBlockReason.REQUIRES_UNMETERED_WIFI,
            ),
        )

        assertEquals(
            TransferNetworkBlockReason.REQUIRES_UNMETERED_WIFI,
            findTransferNetworkBlockReason(wrapped),
        )
        assertEquals(ModelTransferFailureCategory.NETWORK, classifyTransferFailure(wrapped))

        val coordinator = ModelTransferStatusCoordinator(descriptor)
        coordinator.begin(27, TransferNetworkPolicy.UNMETERED_WIFI, partialBytes = 100)
        coordinator.block(27, findTransferNetworkBlockReason(wrapped)!!)
        coordinator.fail(27, wrapped)
        assertEquals(ModelTransferPhase.POLICY_BLOCKED, coordinator.status.value.phase)
        assertEquals(
            TransferNetworkBlockReason.REQUIRES_UNMETERED_WIFI,
            coordinator.status.value.blockReason,
        )
    }

    @Test
    fun `lease policy reason wins whether acquisition failure or monitor terminal arrives first`() {
        val failure = ModelAcquisitionException(build, IOException("cancelled socket"))
        val exactReason = TransferNetworkBlockReason.REQUIRES_UNMETERED_WIFI

        // Lease/call invalidation is visible before the main-thread monitor callback.
        val failureFirst = ModelTransferStatusCoordinator(descriptor)
        failureFirst.begin(40, TransferNetworkPolicy.UNMETERED_WIFI, partialBytes = 100)
        failureFirst.block(
            40,
            resolveTransferNetworkBlockReason(failure, exactReason)!!,
        )
        failureFirst.fail(40, failure)
        assertEquals(ModelTransferPhase.POLICY_BLOCKED, failureFirst.status.value.phase)
        assertEquals(exactReason, failureFirst.status.value.blockReason)

        // The monitor publishes first; the later acquisition failure is idempotent.
        val monitorFirst = ModelTransferStatusCoordinator(descriptor)
        monitorFirst.begin(41, TransferNetworkPolicy.UNMETERED_WIFI, partialBytes = 100)
        monitorFirst.block(41, exactReason)
        monitorFirst.block(
            41,
            resolveTransferNetworkBlockReason(failure, exactReason)!!,
        )
        monitorFirst.fail(41, failure)
        monitorFirst.begin(42, TransferNetworkPolicy.UNMETERED_WIFI, partialBytes = 200)
        monitorFirst.block(41, TransferNetworkBlockReason.NO_VALIDATED_NETWORK)
        assertEquals(42L, monitorFirst.status.value.sessionId)
        assertEquals(ModelTransferPhase.STARTING, monitorFirst.status.value.phase)
    }

    private fun snapshot(
        validated: Boolean,
        internet: Boolean,
        metered: Boolean,
        wifi: Boolean,
    ) = TransferNetworkSnapshot(
        validated = validated,
        internetCapable = internet,
        metered = metered,
        transports = if (wifi) {
            setOf(TransferNetworkTransport.WIFI)
        } else {
            setOf(TransferNetworkTransport.CELLULAR)
        },
    )

    private val build = ModelBuild(
        id = "test",
        displayName = "Test",
        repo = "test/test",
        fileName = "test.bin",
        sizeBytes = 10,
        sha256 = "abc",
        backend = ModelBackend.GPU,
    )
}
