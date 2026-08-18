package com.noamv.localllm.engine

import com.noamv.localllm.contract.Fact
import com.noamv.localllm.contract.InsightRequest
import com.noamv.localllm.contract.InsightTask
import com.noamv.localllm.contract.Period

/**
 * Turns an [InsightRequest] into a system instruction and a user message.
 *
 * Everything here is deterministic and unit-testable, which matters because the prompt
 * is the only thing standing between a small model and an unwanted medical claim.
 */
object PromptBuilder {

    fun systemInstruction(request: InsightRequest): String = buildString {
        append("You write short, plain, factual summaries of a person's own records. ")
        append("You are given a list of already-calculated facts. ")

        if (request.safety.forbidNewNumbers) {
            append("Use only the numbers you are given. ")
            append("Never calculate, estimate, extrapolate or invent a number. ")
            append("If a fact is not in the list, do not mention it. ")
        }

        if (request.safety.forbidHealthClaims) {
            append("Do not diagnose. Do not name conditions or illnesses. ")
            append("Do not give medical, dietary or treatment advice. ")
            append("Do not claim one thing caused another; describe what was recorded, nothing more. ")
            append("Do not reassure or alarm the reader about their health. ")
        }

        if (request.safety.lockScreenSafe) {
            append("This text may appear on a lock screen. ")
            append("Do not name specific products, quantities, or dates. Keep it general. ")
        }

        when (request.task) {
            InsightTask.PERIOD_SUMMARY ->
                append("Write flowing prose of at most ${request.maxWords} words. No lists, no headings. ")
            InsightTask.NUDGE ->
                append("Write exactly one sentence of at most 20 words. No greeting, no emoji. ")
            InsightTask.PERIOD_COMPARISON ->
                append("Compare the two periods in at most ${request.maxWords} words. ")
                    .also { append("Only state a difference if the given facts show one. ") }
        }

        append("Write in the second person (\"you\"). Do not add a preamble or sign-off. ")
        append("Output the summary text only.")
    }

    fun userMessage(request: InsightRequest): String = buildString {
        appendLine("Subject: ${request.subject}")
        request.period?.let { appendLine("Period: ${describe(it)}") }
        appendLine()
        appendLine("Facts:")
        appendFacts(request.facts)

        if (request.task == InsightTask.PERIOD_COMPARISON && request.comparisonFacts.isNotEmpty()) {
            appendLine()
            request.comparisonPeriod?.let { appendLine("Earlier period: ${describe(it)}") }
            appendLine("Earlier facts:")
            appendFacts(request.comparisonFacts)
        }

        appendLine()
        append(
            when (request.task) {
                InsightTask.PERIOD_SUMMARY -> "Summarise these facts."
                InsightTask.NUDGE -> "Write one sentence drawing attention to the most notable fact."
                InsightTask.PERIOD_COMPARISON -> "Describe how the two periods differ."
            },
        )
    }

    private fun StringBuilder.appendFacts(facts: List<Fact>) {
        if (facts.isEmpty()) {
            appendLine("- (none recorded)")
            return
        }
        facts.forEach { fact ->
            append("- ").append(fact.label).append(": ").append(fact.value)
            fact.note?.let { append(" (").append(it).append(")") }
            appendLine()
        }
    }

    private fun describe(period: Period): String =
        if (period.start != null && period.end != null) {
            "${period.label} (${period.start} to ${period.end})"
        } else {
            period.label
        }
}
