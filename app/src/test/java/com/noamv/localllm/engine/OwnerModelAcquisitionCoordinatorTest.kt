package com.noamv.localllm.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class OwnerModelAcquisitionCoordinatorTest {
    @Test
    fun `repeated owner taps share one active acquisition and a later tap may retry`() = runTest {
        val calls = AtomicInteger()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val workEpoch = ProcessWorkEpoch()
        val coordinator = OwnerModelAcquisitionCoordinator(
            scope = this,
            workEpoch = workEpoch,
            acquireAndPrepare = {
                if (calls.incrementAndGet() == 1) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
            },
            onFailure = { throw AssertionError("unexpected failure", it) },
        )

        val first = coordinator.start()
        firstStarted.await()
        val repeated = coordinator.start()

        assertSame(first, repeated)
        assertEquals(1, calls.get())

        releaseFirst.complete(Unit)
        advanceUntilIdle()
        coordinator.start()
        advanceUntilIdle()
        assertEquals(2, calls.get())
    }

    @Test
    fun `cancellation reaches acquisition without clearing its resumable partial state`() = runTest {
        val started = CompletableDeferred<Unit>()
        var retainedPartialBytes = 0
        val workEpoch = ProcessWorkEpoch()
        val coordinator = OwnerModelAcquisitionCoordinator(
            scope = this,
            workEpoch = workEpoch,
            acquireAndPrepare = {
                retainedPartialBytes = 4096
                started.complete(Unit)
                awaitCancellation()
            },
            onFailure = { throw AssertionError("cancellation must not be wrapped", it) },
        )

        val job = coordinator.start()
        started.await()
        coordinator.cancel()
        runCurrent()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(4096, retainedPartialBytes)
    }

    @Test
    fun `stale owner request cannot absorb valid post-trim acquisition`() = runTest {
        val workEpoch = ProcessWorkEpoch()
        val calls = AtomicInteger()
        val coordinator = OwnerModelAcquisitionCoordinator(
            scope = this,
            workEpoch = workEpoch,
            acquireAndPrepare = { calls.incrementAndGet() },
            onFailure = { throw AssertionError("unexpected failure", it) },
        )
        val preTrimTicket = workEpoch.ticket()

        workEpoch.invalidate()
        val staleJob = coordinator.start(preTrimTicket)
        val currentJob = coordinator.start()
        advanceUntilIdle()

        assertTrue(staleJob.isCompleted)
        assertTrue(currentJob.isCompleted)
        assertEquals(1, calls.get())
    }

    @Test
    fun `request captured after critical trim may start normally`() = runTest {
        val workEpoch = ProcessWorkEpoch()
        val calls = AtomicInteger()
        val coordinator = OwnerModelAcquisitionCoordinator(
            scope = this,
            workEpoch = workEpoch,
            acquireAndPrepare = { calls.incrementAndGet() },
            onFailure = { throw AssertionError("unexpected failure", it) },
        )

        workEpoch.invalidate()
        coordinator.start()
        advanceUntilIdle()

        assertEquals(1, calls.get())
    }

    @Test
    fun `shared prewarm slot rejects stale registration before coalescing current work`() = runTest {
        val workEpoch = ProcessWorkEpoch()
        val calls = AtomicInteger()
        val prewarm = EpochProcessJobCoordinator(
            scope = this,
            workEpoch = workEpoch,
            work = { calls.incrementAndGet() },
            onFailure = { throw AssertionError("unexpected failure", it) },
        )
        val preTrimTicket = workEpoch.ticket()

        workEpoch.invalidate()
        val staleJob = prewarm.start(preTrimTicket)
        val currentJob = prewarm.start()
        advanceUntilIdle()

        assertTrue(staleJob.isCompleted)
        assertTrue(currentJob.isCompleted)
        assertEquals(1, calls.get())
    }
}
