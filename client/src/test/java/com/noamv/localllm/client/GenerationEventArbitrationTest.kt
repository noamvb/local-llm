package com.noamv.localllm.client

import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class GenerationEventArbitrationTest {

    @Test
    fun `admitted completion wins against concurrent total timeout`() {
        val completeDeliveryEntered = CountDownLatch(1)
        val allowCompleteDelivery = CountDownLatch(1)
        val harness = ConflatedHarness(
            beforeDelivery = { event ->
                if (event is GenerationEvent.Complete) {
                    completeDeliveryEntered.countDown()
                    allowCompleteDelivery.await(5, TimeUnit.SECONDS)
                }
            },
        )
        harness.gate.assignRequestId("request-1")

        val callback = thread(name = "completion-callback") {
            harness.gate.accept(
                "request-1",
                CallbackEvent.Complete("authoritative", null),
            )
        }
        assertTrue(completeDeliveryEntered.await(5, TimeUnit.SECONDS))

        val timeoutAttempted = CountDownLatch(1)
        val timeoutWon = AtomicReference<Boolean?>(null)
        val timeout = LocalLlmClient.TimedOut(LocalLlmClient.TimeoutPhase.TOTAL)
        val deadline = thread(name = "total-deadline") {
            timeoutAttempted.countDown()
            timeoutWon.set(harness.gate.fail(timeout))
        }
        assertTrue(timeoutAttempted.await(5, TimeUnit.SECONDS))
        assertThreadBlocked(deadline)

        allowCompleteDelivery.countDown()
        callback.join(5_000)
        deadline.join(5_000)

        assertFalse(callback.isAlive)
        assertFalse(deadline.isAlive)
        assertEquals(false, timeoutWon.get())
        assertEquals(
            listOf(GenerationEvent.Complete("authoritative")),
            harness.deliveryAttempts,
        )
        assertEquals(GenerationEvent.Complete("authoritative"), harness.takeOnlyTerminal())
        assertEquals(1, harness.closeCount.get())
        assertEquals(1, harness.terminalCount.get())
        assertEquals(1, harness.firstResponseCount.get())
        assertTrue(harness.gate.serverFinished)
    }

    @Test
    fun `conflated channel replaces admitted draft with concurrent Binder death`() {
        val draftDeliveryEntered = CountDownLatch(1)
        val allowDraftDelivery = CountDownLatch(1)
        val harness = ConflatedHarness(
            beforeDelivery = { event ->
                if (event is GenerationEvent.Draft) {
                    draftDeliveryEntered.countDown()
                    allowDraftDelivery.await(5, TimeUnit.SECONDS)
                }
            },
        )
        harness.gate.assignRequestId("request-1")

        val callback = thread(name = "draft-callback") {
            harness.gate.accept("request-1", CallbackEvent.Token("draft"))
        }
        assertTrue(draftDeliveryEntered.await(5, TimeUnit.SECONDS))

        val deathAttempted = CountDownLatch(1)
        val deathWon = AtomicReference<Boolean?>(null)
        val death = LocalLlmClient.Unavailable("Binder died")
        val binderDeath = thread(name = "binder-death") {
            deathAttempted.countDown()
            deathWon.set(harness.gate.fail(death))
        }
        assertTrue(deathAttempted.await(5, TimeUnit.SECONDS))
        assertThreadBlocked(binderDeath)

        allowDraftDelivery.countDown()
        callback.join(5_000)
        binderDeath.join(5_000)

        assertFalse(callback.isAlive)
        assertFalse(binderDeath.isAlive)
        assertEquals(true, deathWon.get())
        assertEquals(2, harness.deliveryAttempts.size)
        assertEquals(GenerationEvent.Draft("draft"), harness.deliveryAttempts.first())
        assertSame(
            death,
            (harness.deliveryAttempts.last() as GenerationEvent.Failure).error,
        )
        val failure = harness.takeOnlyTerminal() as GenerationEvent.Failure
        assertSame(death, failure.error)
        assertEquals(1, harness.closeCount.get())
        assertEquals(1, harness.terminalCount.get())
        assertEquals(1, harness.firstResponseCount.get())
        assertFalse(harness.gate.serverFinished)
    }

    @Test
    fun `admitted first response cancels concurrent first-response timeout`() {
        val responseAdmitted = CountDownLatch(1)
        val allowResponse = CountDownLatch(1)
        val harness = ConflatedHarness(
            afterFirstResponseAdmission = {
                responseAdmitted.countDown()
                allowResponse.await(5, TimeUnit.SECONDS)
            },
        )
        harness.gate.assignRequestId("request-1")

        val callback = thread(name = "first-response-callback") {
            harness.gate.accept("request-1", CallbackEvent.Progress(5, "starting"))
        }
        assertTrue(responseAdmitted.await(5, TimeUnit.SECONDS))

        val timeoutAttempted = CountDownLatch(1)
        val timeoutWon = AtomicReference<Boolean?>(null)
        val timeout = LocalLlmClient.TimedOut(LocalLlmClient.TimeoutPhase.FIRST_RESPONSE)
        val deadline = thread(name = "first-response-deadline") {
            timeoutAttempted.countDown()
            timeoutWon.set(harness.gate.failIfNoFirstResponse(timeout))
        }
        assertTrue(timeoutAttempted.await(5, TimeUnit.SECONDS))
        assertThreadBlocked(deadline)

        allowResponse.countDown()
        callback.join(5_000)
        deadline.join(5_000)

        assertFalse(callback.isAlive)
        assertFalse(deadline.isAlive)
        assertEquals(false, timeoutWon.get())
        assertEquals(
            listOf(GenerationEvent.Progress(5, "starting")),
            harness.deliveryAttempts,
        )
        assertEquals(
            GenerationEvent.Progress(5, "starting"),
            harness.channel.tryReceive().getOrNull(),
        )
        assertFalse(harness.channel.tryReceive().isClosed)
        assertEquals(0, harness.closeCount.get())
        assertEquals(0, harness.terminalCount.get())
        assertEquals(1, harness.firstResponseCount.get())
        assertFalse(harness.gate.isTerminal)

        assertTrue(harness.gate.cancelCollector())
        harness.channel.cancel()
    }

    @Test
    fun `first-response timeout rejects callback admitted after its terminal claim`() {
        val failureDeliveryEntered = CountDownLatch(1)
        val allowFailureDelivery = CountDownLatch(1)
        val harness = ConflatedHarness(
            beforeDelivery = { event ->
                if (event is GenerationEvent.Failure) {
                    failureDeliveryEntered.countDown()
                    allowFailureDelivery.await(5, TimeUnit.SECONDS)
                }
            },
        )
        harness.gate.assignRequestId("request-1")

        val timeout = LocalLlmClient.TimedOut(LocalLlmClient.TimeoutPhase.FIRST_RESPONSE)
        val timeoutWon = AtomicReference<Boolean?>(null)
        val deadline = thread(name = "winning-first-response-deadline") {
            timeoutWon.set(harness.gate.failIfNoFirstResponse(timeout))
        }
        assertTrue(failureDeliveryEntered.await(5, TimeUnit.SECONDS))

        val callbackAttempted = CountDownLatch(1)
        val callbackReturned = AtomicBoolean(false)
        val callback = thread(name = "late-first-response-callback") {
            callbackAttempted.countDown()
            harness.gate.accept("request-1", CallbackEvent.Progress(5, "late"))
            callbackReturned.set(true)
        }
        assertTrue(callbackAttempted.await(5, TimeUnit.SECONDS))
        assertThreadBlocked(callback)

        allowFailureDelivery.countDown()
        deadline.join(5_000)
        callback.join(5_000)

        assertFalse(deadline.isAlive)
        assertFalse(callback.isAlive)
        assertTrue(callbackReturned.get())
        assertEquals(true, timeoutWon.get())
        assertEquals(1, harness.deliveryAttempts.size)
        assertSame(
            timeout,
            (harness.deliveryAttempts.single() as GenerationEvent.Failure).error,
        )
        val failure = harness.takeOnlyTerminal() as GenerationEvent.Failure
        assertSame(timeout, failure.error)
        assertEquals(1, harness.closeCount.get())
        assertEquals(1, harness.terminalCount.get())
        assertEquals(0, harness.firstResponseCount.get())
        assertFalse(harness.gate.serverFinished)
    }

    private class ConflatedHarness(
        afterFirstResponseAdmission: () -> Unit = {},
        beforeDelivery: (GenerationEvent) -> Unit = {},
    ) {
        val channel = Channel<GenerationEvent>(Channel.CONFLATED)
        val closeCount = AtomicInteger()
        val terminalCount = AtomicInteger()
        val firstResponseCount = AtomicInteger()
        val deliveryAttempts = mutableListOf<GenerationEvent>()

        val gate = GenerationEventGate(
            onFirstResponse = {
                firstResponseCount.incrementAndGet()
                afterFirstResponseAdmission()
            },
            deliverEvent = { event ->
                deliveryAttempts += event
                beforeDelivery(event)
                channel.trySend(event).isSuccess
            },
            closeFlow = { cause ->
                closeCount.incrementAndGet()
                channel.close(cause)
            },
            onTerminalSelected = { terminalCount.incrementAndGet() },
        )

        fun takeOnlyTerminal(): GenerationEvent {
            val terminal = channel.tryReceive().getOrThrow()
            assertTrue(channel.tryReceive().isClosed)
            return terminal
        }
    }

    private fun assertThreadBlocked(thread: Thread) {
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.isAlive && thread.state != Thread.State.BLOCKED) {
            if (System.nanoTime() >= deadlineNanos) break
            Thread.yield()
        }
        assertEquals(Thread.State.BLOCKED, thread.state)
    }
}
