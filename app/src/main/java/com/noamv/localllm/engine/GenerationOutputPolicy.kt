package com.noamv.localllm.engine

import com.noamv.localllm.contract.InsightRequest
import com.noamv.localllm.contract.InsightTask

/** A generated terminal value violated a service-owned output bound. */
internal class GenerationOutputPolicyException(message: String) : Exception(message)

/**
 * Hard generation and terminal-text limits independent of prompt compliance.
 *
 * `maxWords` remains a caller-requested semantic limit. LiteRT receives a separate token
 * ceiling, and the assembled terminal text is then checked against the actual word limit.
 */
internal object GenerationOutputPolicy {
    const val HARD_MAX_OUTPUT_TOKENS = 256
    const val MAX_TERMINAL_CHARACTERS = 8 * 1024

    private const val MIN_OUTPUT_TOKENS = 16
    private const val TOKEN_ALLOWANCE_PER_WORD = 2
    private const val TOKEN_PADDING = 16

    fun wordLimit(request: InsightRequest): Int = when (request.task) {
        InsightTask.NUDGE -> minOf(request.maxWords, NUDGE_MAX_WORDS)
        InsightTask.PERIOD_SUMMARY,
        InsightTask.PERIOD_COMPARISON,
        -> request.maxWords
    }

    fun maxOutputTokens(request: InsightRequest): Int =
        (wordLimit(request) * TOKEN_ALLOWANCE_PER_WORD + TOKEN_PADDING)
            .coerceIn(MIN_OUTPUT_TOKENS, HARD_MAX_OUTPUT_TOKENS)

    fun append(builder: StringBuilder, fragment: String) {
        if (fragment.length > MAX_TERMINAL_CHARACTERS - builder.length) {
            throw GenerationOutputPolicyException(
                "Generated output exceeded the terminal character limit.",
            )
        }
        builder.append(fragment)
    }

    fun validatedTerminalText(request: InsightRequest, rawText: String): String {
        val text = rawText.trim()
        if (text.isEmpty()) {
            throw GenerationOutputPolicyException("Generated output was blank.")
        }
        val words = WORD.findAll(text).count()
        val limit = wordLimit(request)
        if (words > limit) {
            throw GenerationOutputPolicyException(
                "Generated output contained $words words; the request permits $limit.",
            )
        }
        return text
    }

    private const val NUDGE_MAX_WORDS = 20
    private val WORD = Regex("\\S+")
}
