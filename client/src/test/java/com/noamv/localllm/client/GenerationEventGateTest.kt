package com.noamv.localllm.client

import com.noamv.localllm.contract.LocalLlmError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationEventGateTest {

    @Test
    fun `buffers callbacks until ID and final text replaces draft`() {
        val harness = Harness()

        harness.gate.accept("request-1", CallbackEvent.Token("draft "))
        harness.gate.accept("request-1", CallbackEvent.Token("text"))
        harness.gate.accept("request-1", CallbackEvent.Complete("authoritative text", null))

        assertTrue(harness.events.isEmpty())
        harness.gate.assignRequestId("request-1")

        assertEquals(
            listOf(
                GenerationEvent.Draft("draft "),
                GenerationEvent.Draft("draft text"),
                GenerationEvent.Complete("authoritative text"),
            ),
            harness.events,
        )
        assertEquals(1, harness.firstResponses)
        assertEquals(listOf<Throwable?>(null), harness.closes)
        assertEquals(1, harness.terminalSelections)
        assertTrue(harness.gate.serverFinished)
    }

    @Test
    fun `delivers completion-only response`() {
        val harness = Harness()

        harness.gate.assignRequestId("request-1")
        harness.gate.accept("request-1", CallbackEvent.Complete("complete only", null))

        assertEquals(listOf(GenerationEvent.Complete("complete only")), harness.events)
        assertEquals(listOf<Throwable?>(null), harness.closes)
        assertEquals(1, harness.firstResponses)
        assertEquals(1, harness.terminalSelections)
        assertTrue(harness.gate.serverFinished)
        assertFalse(harness.gate.requiresRemoteCancellation)
    }

    @Test
    fun `preserves progress percent and stage`() {
        val harness = Harness()

        harness.gate.assignRequestId("request-1")
        harness.gate.accept("request-1", CallbackEvent.Progress(37, "Downloading model"))

        assertEquals(
            listOf(GenerationEvent.Progress(37, "Downloading model")),
            harness.events,
        )
        assertEquals(1, harness.firstResponses)
        assertTrue(harness.closes.isEmpty())
        assertFalse(harness.gate.isTerminal)
        assertFalse(harness.gate.requiresRemoteCancellation)
    }

    @Test
    fun `rejects callback ID that differs from returned ID`() {
        val harness = Harness()

        harness.gate.accept("spoofed", CallbackEvent.Progress(1, "stage"))
        harness.gate.assignRequestId("assigned")

        val error = (harness.events.single() as GenerationEvent.Failure).error
            as LocalLlmClient.InferenceFailed
        assertEquals(LocalLlmError.INTERNAL, error.code)
        assertTrue(error.message.orEmpty().contains("request ID"))
        assertEquals(listOf<Throwable?>(null), harness.closes)
        assertEquals(1, harness.terminalSelections)
        assertFalse(harness.gate.serverFinished)
    }

    @Test
    fun `rejects changing provisional callback IDs`() {
        val harness = Harness()

        harness.gate.accept("one", CallbackEvent.Progress(1, "stage"))
        harness.gate.accept("two", CallbackEvent.Token("ignored"))

        assertTrue(harness.events.single() is GenerationEvent.Failure)
        assertEquals(listOf<Throwable?>(null), harness.closes)
        assertEquals(1, harness.terminalSelections)
        assertFalse(harness.gate.serverFinished)
    }

    @Test
    fun `failed nonterminal delivery emits one terminal failure`() {
        val harness = Harness(
            deliveryResult = { event -> event is GenerationEvent.Failure },
        )

        harness.gate.assignRequestId("request-1")
        harness.gate.accept("request-1", CallbackEvent.Token("text"))

        assertEquals(2, harness.events.size)
        assertEquals(GenerationEvent.Draft("text"), harness.events.first())
        val error = (harness.events.last() as GenerationEvent.Failure).error
            as LocalLlmClient.InferenceFailed
        assertEquals(LocalLlmError.INTERNAL, error.code)
        assertTrue(error.message.orEmpty().contains("could not accept"))
        assertEquals(listOf<Throwable?>(null), harness.closes)
        assertEquals(1, harness.terminalSelections)
        assertFalse(harness.gate.serverFinished)
    }

    @Test
    fun `failed terminal delivery closes with its cause`() {
        val harness = Harness(deliveryResult = { false })
        val timeout = LocalLlmClient.TimedOut(LocalLlmClient.TimeoutPhase.TOTAL)

        assertTrue(harness.gate.fail(timeout))

        assertEquals(1, harness.events.size)
        assertTrue(harness.events.single() is GenerationEvent.Failure)
        assertEquals(1, harness.closes.size)
        assertSame(timeout, harness.closes.single())
        assertEquals(1, harness.terminalSelections)
        assertFalse(harness.gate.serverFinished)
        assertTrue(harness.gate.requiresRemoteCancellation)
    }

    @Test
    fun `rejects structured result in v1`() {
        val harness = Harness()

        harness.gate.assignRequestId("request-1")
        harness.gate.accept("request-1", CallbackEvent.Complete("text", "{}"))

        val failure = harness.events.single() as GenerationEvent.Failure
        assertTrue(failure.error is LocalLlmClient.InferenceFailed)
        assertEquals(listOf<Throwable?>(null), harness.closes)
        assertFalse(harness.gate.serverFinished)
    }

    @Test
    fun `propagates server error exactly once`() {
        val harness = Harness()

        harness.gate.assignRequestId("request-1")
        harness.gate.accept("request-1", CallbackEvent.Error(LocalLlmError.BUSY, "busy"))
        harness.gate.accept("request-1", CallbackEvent.Complete("late", null))

        val error = (harness.events.single() as GenerationEvent.Failure).error
            as LocalLlmClient.InferenceFailed
        assertEquals(LocalLlmError.BUSY, error.code)
        assertEquals(listOf<Throwable?>(null), harness.closes)
        assertEquals(1, harness.terminalSelections)
        assertTrue(harness.gate.serverFinished)
        assertFalse(harness.gate.requiresRemoteCancellation)
    }

    @Test
    fun `rejects callback flood before request ID assignment`() {
        val harness = Harness(maxPreAssignmentEvents = 2)

        harness.gate.accept("request-1", CallbackEvent.Progress(1, "one"))
        harness.gate.accept("request-1", CallbackEvent.Progress(2, "two"))
        harness.gate.accept("request-1", CallbackEvent.Progress(3, "three"))

        val error = (harness.events.single() as GenerationEvent.Failure).error
            as LocalLlmClient.InferenceFailed
        assertEquals(LocalLlmError.INTERNAL, error.code)
        assertTrue(error.message.orEmpty().contains("Too many callbacks"))
        assertEquals(listOf<Throwable?>(null), harness.closes)
        assertFalse(harness.gate.serverFinished)
    }

    @Test
    fun `collector cancellation owns cleanup without fabricating an event`() {
        val harness = Harness()

        assertTrue(harness.gate.cancelCollector())
        assertFalse(harness.gate.cancelCollector())
        harness.gate.accept("request-1", CallbackEvent.Complete("late", null))

        assertTrue(harness.events.isEmpty())
        assertTrue(harness.closes.isEmpty())
        assertEquals(1, harness.terminalSelections)
        assertFalse(harness.gate.serverFinished)
        assertTrue(harness.gate.requiresRemoteCancellation)
    }

    private class Harness(
        deliveryResult: (GenerationEvent) -> Boolean = { true },
        maxPreAssignmentEvents: Int = 64,
    ) {
        val events = mutableListOf<GenerationEvent>()
        val closes = mutableListOf<Throwable?>()
        var firstResponses = 0
        var terminalSelections = 0

        val gate = GenerationEventGate(
            onFirstResponse = { firstResponses++ },
            deliverEvent = { event ->
                events += event
                deliveryResult(event)
            },
            closeFlow = closes::add,
            onTerminalSelected = { terminalSelections++ },
            maxPreAssignmentEvents = maxPreAssignmentEvents,
        )
    }
}
