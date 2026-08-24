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
        val coordinator = OwnerModelAcquisitionCoordinator(
            scope = this,
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
        val coordinator = OwnerModelAcquisitionCoordinator(
            scope = this,
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
}
