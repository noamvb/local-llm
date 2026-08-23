package com.noamv.localllm

import com.noamv.localllm.contract.Fact
import com.noamv.localllm.contract.InsightRequest
import com.noamv.localllm.contract.InsightTask
import com.noamv.localllm.contract.Period
import com.noamv.localllm.contract.SafetyPolicy
import com.noamv.localllm.engine.PromptBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prompt is the only thing standing between a small model and an unwanted medical
 * claim, so the guardrails are asserted rather than assumed.
 */
class PromptBuilderTest {

    private fun request(
        task: InsightTask = InsightTask.PERIOD_SUMMARY,
        safety: SafetyPolicy = SafetyPolicy(),
        facts: List<Fact> = listOf(Fact("Entries recorded", "18")),
    ) = InsightRequest(
        clientId = "test",
        task = task,
        subject = "your own records",
        period = Period("the last 30 days", "2026-07-19", "2026-08-18"),
        facts = facts,
        safety = safety,
    )

    @Test
    fun `default policy forbids diagnosis and advice`() {
        val system = PromptBuilder.systemInstruction(request())
        assertTrue(system.contains("Do not diagnose"))
        assertTrue(system.contains("Do not give medical"))
        assertTrue(system.contains("Do not claim one thing caused another"))
    }

    @Test
    fun `default policy forbids inventing numbers`() {
        val system = PromptBuilder.systemInstruction(request())
        assertTrue(system.contains("Use only the numbers you are given"))
        assertTrue(system.contains("Never calculate"))
    }

    @Test
    fun `lock screen safety is off by default and adds a restriction when enabled`() {
        assertFalse(PromptBuilder.systemInstruction(request()).contains("lock screen"))

        val guarded = PromptBuilder.systemInstruction(
            request(safety = SafetyPolicy(lockScreenSafe = true)),
        )
        assertTrue(guarded.contains("lock screen"))
        assertTrue(guarded.contains("Do not name specific products, quantities, or dates"))
    }

    @Test
    fun `a nudge is constrained to a single short sentence`() {
        val system = PromptBuilder.systemInstruction(request(task = InsightTask.NUDGE))
        assertTrue(system.contains("exactly one sentence"))
        assertTrue(system.contains("20 words"))

        val tighter = PromptBuilder.systemInstruction(
            request(task = InsightTask.NUDGE).copy(maxWords = 7),
        )
        assertTrue(tighter.contains("7 words"))
    }

    @Test
    fun `facts are rendered as a labelled list including notes`() {
        val message = PromptBuilder.userMessage(
            request(facts = listOf(Fact("Busiest day", "Tuesday", "4 entries"))),
        )
        assertTrue(message.contains("- Busiest day: Tuesday (4 entries)"))
        assertTrue(message.contains("the last 30 days (2026-07-19 to 2026-08-18)"))
    }

    @Test
    fun `an empty fact list is stated explicitly rather than left blank`() {
        val message = PromptBuilder.userMessage(request(facts = emptyList()))
        assertTrue(message.contains("(none recorded)"))
    }

    @Test
    fun `opting out of the health policy removes those clauses`() {
        val system = PromptBuilder.systemInstruction(
            request(safety = SafetyPolicy(forbidHealthClaims = false)),
        )
        assertFalse(system.contains("Do not diagnose"))
    }
}
