package com.noamv.localllm.transfer

import com.noamv.localllm.service.DeliveredStartIdTracker
import com.noamv.localllm.service.ModelTransferCommand
import com.noamv.localllm.service.ModelTransferService
import com.noamv.localllm.service.TransferCancellationDisposition
import com.noamv.localllm.service.shouldForcePlatformTimeoutCleanup
import com.noamv.localllm.service.transferCancellationDisposition
import com.noamv.localllm.service.routeModelTransferCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTransferSessionOwnerTest {
    @Test
    fun `pre-start cancellation cleans immediately because lazy body has no finally`() {
        assertEquals(
            TransferCancellationDisposition.CLEAN_UP_NOW,
            transferCancellationDisposition(cancellationWon = true, jobStarted = false),
        )
    }

    @Test
    fun `started cancellation keeps ownership until exact terminal settlement`() {
        assertEquals(
            TransferCancellationDisposition.CANCEL_JOB_AND_AWAIT_SETTLEMENT,
            transferCancellationDisposition(cancellationWon = true, jobStarted = true),
        )
    }

    @Test
    fun `promotion winner is neither cancelled nor cleaned up early`() {
        assertEquals(
            TransferCancellationDisposition.AWAIT_PROMOTION_OR_FAILURE,
            transferCancellationDisposition(cancellationWon = false, jobStarted = true),
        )
    }

    @Test
    fun `every delivered command advances the exact cleanup start id`() {
        val starts = DeliveredStartIdTracker()
        starts.record(41)
        assertEquals(41, starts.latest())

        // Cancel and retry/invalid deliveries remain real Service starts even though
        // neither is allowed to begin or resume acquisition.
        starts.record(42)
        assertEquals(42, starts.latest())
        starts.record(43)
        assertEquals(43, starts.latest())
    }

    @Test
    fun `repeated starts coalesce without widening the active policy`() {
        val owner = ModelTransferSessionOwner()
        val first = owner.start(TransferNetworkPolicy.UNMETERED_WIFI)
            as TransferStartDecision.Started
        val repeated = owner.start(TransferNetworkPolicy.ALLOW_METERED_ONCE)
            as TransferStartDecision.Coalesced

        assertEquals(first.session.id, repeated.session.id)
        assertEquals(TransferNetworkPolicy.UNMETERED_WIFI, repeated.session.policy)
        assertFalse(owner.finish(first.session.id + 1))
        assertSame(first.session, owner.active())
        assertTrue(owner.finish(first.session.id))

        val later = owner.start(TransferNetworkPolicy.ALLOW_METERED_ONCE)
            as TransferStartDecision.Started
        assertNotEquals(first.session.id, later.session.id)
    }

    @Test
    fun `service recreation uses a new process-wide session id and stale callback is fenced`() {
        val firstOwner = ModelTransferSessionOwner()
        val oldSession = (firstOwner.start(TransferNetworkPolicy.UNMETERED_WIFI)
            as TransferStartDecision.Started).session
        assertTrue(firstOwner.finish(oldSession.id))

        val recreatedOwner = ModelTransferSessionOwner()
        val newSession = (recreatedOwner.start(TransferNetworkPolicy.UNMETERED_WIFI)
            as TransferStartDecision.Started).session
        assertTrue(newSession.id > oldSession.id)

        val descriptor = ModelTransferDescriptor(
            role = ModelRole.WRITER,
            modelId = "writer",
            modelName = "Writer",
            expectedBytes = 100,
        )
        val coordinator = ModelTransferStatusCoordinator(descriptor)
        coordinator.begin(newSession.id, newSession.policy, partialBytes = 10)
        coordinator.completeCommittedPromotion(oldSession.id, 100, 90)

        assertEquals(newSession.id, coordinator.status.value.sessionId)
        assertEquals(ModelTransferPhase.STARTING, coordinator.status.value.phase)
        assertEquals(10L, coordinator.status.value.bytes.availableBytes)
    }

    @Test
    fun `command routing is explicit start only and retry never resumes`() {
        assertEquals(
            ModelTransferCommand.Start(TransferNetworkPolicy.UNMETERED_WIFI),
            routeModelTransferCommand(ModelTransferService.ACTION_START, false, 0),
        )
        assertEquals(
            ModelTransferCommand.Start(TransferNetworkPolicy.ALLOW_METERED_ONCE),
            routeModelTransferCommand(ModelTransferService.ACTION_START, true, 0),
        )
        assertEquals(
            ModelTransferCommand.Cancel,
            routeModelTransferCommand(ModelTransferService.ACTION_CANCEL, false, 0),
        )
        assertEquals(ModelTransferCommand.Invalid, routeModelTransferCommand(null, true, 0))
        assertEquals(
            ModelTransferCommand.Invalid,
            routeModelTransferCommand(
                ModelTransferService.ACTION_START,
                true,
                android.app.Service.START_FLAG_RETRY,
            ),
        )
    }

    @Test
    fun `app deadline remains below Android data sync limit`() {
        assertTrue(ModelTransferService.TRANSFER_DEADLINE_MILLIS > 0)
        assertTrue(ModelTransferService.TRANSFER_DEADLINE_MILLIS < 6L * 60L * 60L * 1_000L)
    }

    @Test
    fun `platform timeout watchdog is positive bounded and exact-session fenced`() {
        assertTrue(ModelTransferService.PLATFORM_TIMEOUT_STOP_GRACE_MILLIS > 0L)
        assertTrue(ModelTransferService.PLATFORM_TIMEOUT_STOP_GRACE_MILLIS <= 2_000L)
        val active = ActiveTransferSession(81, TransferNetworkPolicy.UNMETERED_WIFI)

        assertTrue(shouldForcePlatformTimeoutCleanup(81, active))
        assertFalse(shouldForcePlatformTimeoutCleanup(80, active))
        assertFalse(shouldForcePlatformTimeoutCleanup(81, null))
    }

    @Test
    fun `critical trim registry reaches only current foreground owner`() {
        val registry = ForegroundTransferCancellationRegistry()
        val reasons = mutableListOf<TransferStopReason>()
        val registration = registry.register(8) { reasons += it }

        assertTrue(registry.cancel(TransferStopReason.CRITICAL_MEMORY))
        assertEquals(listOf(TransferStopReason.CRITICAL_MEMORY), reasons)
        registration.close()
        assertFalse(registry.cancel(TransferStopReason.CRITICAL_MEMORY))
    }
}
