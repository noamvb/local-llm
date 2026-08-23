package com.noamv.localllm.service

import com.noamv.localllm.engine.InferenceAdmission
import com.noamv.localllm.engine.InferencePriority
import com.noamv.localllm.engine.InferenceQueueState
import com.noamv.localllm.engine.InferenceScheduler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class RequestLifecycleGateTest {
    @Test
    fun `cancellation immediately before registration prevents submission`() {
        val cancellations = AtomicInteger()
        val gate = RequestLifecycleGate("request") { cancellations.incrementAndGet() > 0 }
        var submitted = false

        assertTrue(gate.cancel())
        assertEquals(
            RegistrationResult.CancelledBeforeAdmission,
            gate.register {
                submitted = true
                accepted()
            },
        )

        assertFalse(submitted)
        assertEquals(0, cancellations.get())
        assertTrue(gate.terminal())
        assertFalse(gate.terminal())
    }

    @Test
    fun `cancellation during synchronous registration cancels the admitted entry`() {
        val registrationEntered = CountDownLatch(1)
        val releaseRegistration = CountDownLatch(1)
        val cancelReturned = CountDownLatch(1)
        val cancellations = AtomicInteger()
        val result = AtomicReference<RegistrationResult>()
        val gate = RequestLifecycleGate("request") { id ->
            assertEquals("request", id)
            cancellations.incrementAndGet()
            true
        }

        val registrationThread = thread(name = "register-request") {
            result.set(
                gate.register {
                    registrationEntered.countDown()
                    check(releaseRegistration.await(5, TimeUnit.SECONDS))
                    accepted()
                },
            )
        }
        assertTrue(registrationEntered.await(5, TimeUnit.SECONDS))

        val cancellationThread = thread(name = "cancel-during-registration") {
            assertTrue(gate.cancel())
            cancelReturned.countDown()
        }
        assertFalse(cancelReturned.await(100, TimeUnit.MILLISECONDS))
        releaseRegistration.countDown()

        registrationThread.join(5_000)
        cancellationThread.join(5_000)
        assertFalse(registrationThread.isAlive)
        assertFalse(cancellationThread.isAlive)
        assertTrue(result.get() is RegistrationResult.Admitted)
        assertEquals(1, cancellations.get())
        assertFalse(gate.cancel())
    }

    @Test
    fun `reentrant cancellation from registration callback cannot leak admission`() {
        val cancellations = AtomicInteger()
        lateinit var gate: RequestLifecycleGate
        gate = RequestLifecycleGate("request") {
            cancellations.incrementAndGet()
            true
        }

        val result = gate.register {
            assertTrue(gate.cancel())
            accepted()
        }

        assertTrue(result is RegistrationResult.Admitted)
        assertEquals(1, cancellations.get())
        assertFalse(gate.cancel())
    }

    @Test
    fun `callback death during real queued publication removes scheduler registration`() = runTest {
        val scheduler = InferenceScheduler(this)
        val releaseActive = CompletableDeferred<Unit>()
        val states = mutableListOf<InferenceQueueState>()
        var waitingStarted = false
        scheduler.submit("active", InferencePriority.OPEN_SCREEN) { releaseActive.await() }
        lateinit var gate: RequestLifecycleGate
        gate = RequestLifecycleGate("waiting", scheduler::cancel)

        val result = gate.register {
            scheduler.submit(
                requestId = "waiting",
                priority = InferencePriority.OPEN_SCREEN,
                onState = { state ->
                    states += state
                    if (state == InferenceQueueState.QUEUED) gate.cancel()
                },
            ) { waitingStarted = true }
        }

        assertTrue(result is RegistrationResult.Admitted)
        advanceUntilIdle()
        assertFalse(waitingStarted)
        assertEquals(
            listOf(InferenceQueueState.QUEUED, InferenceQueueState.CANCELLED),
            states,
        )
        assertEquals(0, scheduler.snapshot().waiting)
        releaseActive.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `busy registration still gives cleanup exactly one owner`() {
        val gate = RequestLifecycleGate("request") { false }
        var cancellationClaimed = false

        assertEquals(RegistrationResult.Busy, gate.register { InferenceAdmission.Busy })
        assertFalse(gate.cancel { cancellationClaimed = true })
        assertFalse(cancellationClaimed)
        assertTrue(gate.terminal())
        assertFalse(gate.terminal())
    }

    private fun accepted() = InferenceAdmission.Accepted("request", initiallyQueued = false)
}
