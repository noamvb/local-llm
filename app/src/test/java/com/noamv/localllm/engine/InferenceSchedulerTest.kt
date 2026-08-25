package com.noamv.localllm.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class InferenceSchedulerTest {

    @Test
    fun `one request runs two wait and the fourth is busy`() = runTest {
        val scheduler = InferenceScheduler(this)
        val release = CompletableDeferred<Unit>()

        assertAccepted(scheduler.submit("active", InferencePriority.OPEN_SCREEN) { release.await() })
        assertAccepted(scheduler.submit("waiting-1", InferencePriority.OPEN_SCREEN) {})
        assertAccepted(scheduler.submit("waiting-2", InferencePriority.BACKGROUND) {})
        assertEquals(
            InferenceAdmission.Busy,
            scheduler.submit("rejected", InferencePriority.LIVE_ASSISTANT) {},
        )
        assertEquals(InferenceSchedulerSnapshot(1, 2, 2, false), scheduler.snapshot())

        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(InferenceSchedulerSnapshot(0, 0, 2, false), scheduler.snapshot())
    }

    @Test
    fun `higher priority waiting work moves ahead without preempting active work`() = runTest {
        val scheduler = InferenceScheduler(this, maxWaiting = 3)
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        scheduler.submit("active", InferencePriority.BACKGROUND) {
            order += "active-start"
            release.await()
            order += "active-end"
        }
        runCurrent()
        scheduler.submit("background", InferencePriority.BACKGROUND) { order += "background" }
        scheduler.submit("open", InferencePriority.OPEN_SCREEN) { order += "open" }
        scheduler.submit("live", InferencePriority.LIVE_ASSISTANT) { order += "live" }
        runCurrent()
        assertEquals(listOf("active-start"), order)

        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(
            listOf("active-start", "active-end", "live", "open", "background"),
            order,
        )
    }

    @Test
    fun `equal priority waiting work remains FIFO`() = runTest {
        val scheduler = InferenceScheduler(this)
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        scheduler.submit("active", InferencePriority.OPEN_SCREEN) { release.await() }
        scheduler.submit("first", InferencePriority.OPEN_SCREEN) { order += "first" }
        scheduler.submit("second", InferencePriority.OPEN_SCREEN) { order += "second" }

        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("first", "second"), order)
    }

    @Test
    fun `a concurrent submit cannot jump an earlier unpublished queue admission`() = runTest {
        val scheduler = InferenceScheduler(this)
        val releaseActive = CompletableDeferred<Unit>()
        val queueCallbackEntered = CountDownLatch(1)
        val releaseQueueCallback = CountDownLatch(1)
        val order = mutableListOf<String>()
        scheduler.submit("active", InferencePriority.OPEN_SCREEN) { releaseActive.await() }
        runCurrent()

        val firstSubmit = thread(name = "first-scheduler-submit") {
            scheduler.submit(
                requestId = "first",
                priority = InferencePriority.OPEN_SCREEN,
                onState = { state ->
                    if (state == InferenceQueueState.QUEUED) {
                        queueCallbackEntered.countDown()
                        check(releaseQueueCallback.await(5, TimeUnit.SECONDS))
                    }
                },
            ) { order += "first" }
        }
        assertTrue(queueCallbackEntered.await(5, TimeUnit.SECONDS))

        releaseActive.complete(Unit)
        runCurrent()
        scheduler.submit("second", InferencePriority.OPEN_SCREEN) { order += "second" }
        runCurrent()
        assertTrue(order.isEmpty())

        releaseQueueCallback.countDown()
        firstSubmit.join(5_000)
        assertFalse(firstSubmit.isAlive)
        advanceUntilIdle()
        assertEquals(listOf("first", "second"), order)
    }

    @Test
    fun `cancelling an unpublished head promotes an already published waiter`() = runTest {
        val scheduler = InferenceScheduler(this)
        val releaseActive = CompletableDeferred<Unit>()
        val headCallbackEntered = CountDownLatch(1)
        val releaseHeadCallback = CountDownLatch(1)
        val order = mutableListOf<String>()
        scheduler.submit("active", InferencePriority.OPEN_SCREEN) { releaseActive.await() }
        runCurrent()

        val headSubmit = thread(name = "blocked-head-submit") {
            scheduler.submit(
                requestId = "head",
                priority = InferencePriority.LIVE_ASSISTANT,
                onState = { state ->
                    if (state == InferenceQueueState.QUEUED) {
                        headCallbackEntered.countDown()
                        check(releaseHeadCallback.await(5, TimeUnit.SECONDS))
                    }
                },
            ) { order += "head" }
        }
        assertTrue(headCallbackEntered.await(5, TimeUnit.SECONDS))

        releaseActive.complete(Unit)
        runCurrent()
        scheduler.submit("published", InferencePriority.OPEN_SCREEN) { order += "published" }
        runCurrent()
        assertTrue(order.isEmpty())

        assertTrue(scheduler.cancel("head"))
        runCurrent()
        assertEquals(listOf("published"), order)

        releaseHeadCallback.countDown()
        headSubmit.join(5_000)
        assertFalse(headSubmit.isAlive)
        advanceUntilIdle()
        assertEquals(InferenceSchedulerSnapshot(0, 0, 2, false), scheduler.snapshot())
    }

    @Test
    fun `cancelling queued work frees capacity and never starts it`() = runTest {
        val scheduler = InferenceScheduler(this, maxWaiting = 1)
        val release = CompletableDeferred<Unit>()
        var cancelledStarted = false
        val states = mutableListOf<InferenceQueueState>()
        scheduler.submit("active", InferencePriority.OPEN_SCREEN) { release.await() }
        scheduler.submit("cancelled", InferencePriority.OPEN_SCREEN, states::add) {
            cancelledStarted = true
        }

        assertTrue(scheduler.cancel("cancelled"))
        assertFalse(scheduler.cancel("cancelled"))
        assertAccepted(scheduler.submit("replacement", InferencePriority.OPEN_SCREEN) {})
        release.complete(Unit)
        advanceUntilIdle()

        assertFalse(cancelledStarted)
        assertEquals(
            listOf(InferenceQueueState.QUEUED, InferenceQueueState.CANCELLED),
            states,
        )
    }

    @Test
    fun `cancelling active work starts the highest priority waiter`() = runTest {
        val scheduler = InferenceScheduler(this)
        val activeStarted = CompletableDeferred<Unit>()
        val neverReleased = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val activeStates = mutableListOf<InferenceQueueState>()
        scheduler.submit("active", InferencePriority.BACKGROUND, activeStates::add) {
            activeStarted.complete(Unit)
            neverReleased.await()
        }
        scheduler.submit("background", InferencePriority.BACKGROUND) { order += "background" }
        scheduler.submit("live", InferencePriority.LIVE_ASSISTANT) { order += "live" }
        activeStarted.await()

        assertTrue(scheduler.cancel("active"))
        advanceUntilIdle()

        assertEquals(listOf("live", "background"), order)
        assertEquals(
            listOf(InferenceQueueState.ACTIVE, InferenceQueueState.CANCELLED),
            activeStates,
        )
    }

    @Test
    fun `queued work expires without starting and frees capacity`() = runTest {
        val scheduler = InferenceScheduler(this, maxWaiting = 1, maxQueueWaitMillis = 1_000)
        val release = CompletableDeferred<Unit>()
        val states = mutableListOf<InferenceQueueState>()
        var expiredStarted = false
        scheduler.submit("active", InferencePriority.OPEN_SCREEN) { release.await() }
        scheduler.submit("expired", InferencePriority.BACKGROUND, states::add) {
            expiredStarted = true
        }

        testScheduler.advanceTimeBy(1_000)
        runCurrent()
        assertFalse(expiredStarted)
        assertEquals(
            listOf(InferenceQueueState.QUEUED, InferenceQueueState.EXPIRED),
            states,
        )
        assertAccepted(scheduler.submit("replacement", InferencePriority.OPEN_SCREEN) {})
        release.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `fast completion is removed and every terminal state is emitted once`() = runTest {
        val scheduler = InferenceScheduler(this)
        val states = mutableListOf<InferenceQueueState>()
        assertAccepted(
            scheduler.submit("fast", InferencePriority.OPEN_SCREEN, states::add) {},
        )

        advanceUntilIdle()
        assertEquals(
            listOf(InferenceQueueState.ACTIVE, InferenceQueueState.COMPLETED),
            states,
        )
        assertEquals(InferenceSchedulerSnapshot(0, 0, 2, false), scheduler.snapshot())
        assertFalse(scheduler.cancel("fast"))
    }

    @Test
    fun `failure is terminal and does not block the next request`() = runTest {
        val scheduler = InferenceScheduler(this)
        val failedStates = mutableListOf<InferenceQueueState>()
        val nextStates = mutableListOf<InferenceQueueState>()
        scheduler.submit("failed", InferencePriority.OPEN_SCREEN, failedStates::add) {
            error("boom")
        }
        scheduler.submit("next", InferencePriority.OPEN_SCREEN, nextStates::add) {}

        advanceUntilIdle()
        assertEquals(
            listOf(InferenceQueueState.ACTIVE, InferenceQueueState.FAILED),
            failedStates,
        )
        assertEquals(
            listOf(
                InferenceQueueState.QUEUED,
                InferenceQueueState.ACTIVE,
                InferenceQueueState.COMPLETED,
            ),
            nextStates,
        )
    }

    @Test
    fun `out of memory is contained as request failure and releases the queue`() = runTest {
        val scheduler = InferenceScheduler(this)
        val failedStates = mutableListOf<InferenceQueueState>()
        var nextRan = false
        scheduler.submit("oom", InferencePriority.OPEN_SCREEN, failedStates::add) {
            throw OutOfMemoryError("simulated native allocation failure")
        }
        scheduler.submit("next", InferencePriority.OPEN_SCREEN) { nextRan = true }

        advanceUntilIdle()

        assertEquals(
            listOf(InferenceQueueState.ACTIVE, InferenceQueueState.FAILED),
            failedStates,
        )
        assertTrue(nextRan)
        assertEquals(InferenceSchedulerSnapshot(0, 0, 2, false), scheduler.snapshot())
    }

    @Test
    fun `closing cancels active and queued work and rejects new admission`() = runTest {
        val scheduler = InferenceScheduler(this)
        val release = CompletableDeferred<Unit>()
        val activeStates = mutableListOf<InferenceQueueState>()
        val queuedStates = mutableListOf<InferenceQueueState>()
        scheduler.submit("active", InferencePriority.OPEN_SCREEN, activeStates::add) {
            release.await()
        }
        scheduler.submit("queued", InferencePriority.OPEN_SCREEN, queuedStates::add) {}

        scheduler.close()
        advanceUntilIdle()

        assertEquals(
            listOf(InferenceQueueState.ACTIVE, InferenceQueueState.CANCELLED),
            activeStates,
        )
        assertEquals(
            listOf(InferenceQueueState.QUEUED, InferenceQueueState.CANCELLED),
            queuedStates,
        )
        assertEquals(InferenceSchedulerSnapshot(0, 0, 2, true), scheduler.snapshot())
        assertEquals(
            InferenceAdmission.Closed,
            scheduler.submit("late", InferencePriority.LIVE_ASSISTANT) {},
        )
    }

    @Test
    fun `onActivityFinished callback is invoked when inference completes`() = runTest {
        var callbackCount = 0
        val scheduler = InferenceScheduler(
            scope = this,
            onActivityFinished = { callbackCount++ },
        )

        scheduler.submit("work-1", InferencePriority.OPEN_SCREEN) {}
        advanceUntilIdle()

        assertEquals(1, callbackCount)
    }

    private fun assertAccepted(admission: InferenceAdmission) {
        assertTrue("Expected accepted admission, got $admission", admission is InferenceAdmission.Accepted)
    }
}
