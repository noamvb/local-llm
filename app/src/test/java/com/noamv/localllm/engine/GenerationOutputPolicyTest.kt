package com.noamv.localllm.engine

import com.noamv.localllm.contract.Fact
import com.noamv.localllm.contract.InsightRequest
import com.noamv.localllm.contract.InsightTask
import com.noamv.localllm.contract.Period
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationOutputPolicyTest {
    private fun request(
        task: InsightTask = InsightTask.PERIOD_SUMMARY,
        maxWords: Int = 90,
    ) = InsightRequest(
        clientId = "test",
        task = task,
        subject = "records",
        period = Period("this period"),
        facts = listOf(Fact("Entries", "7")),
        maxWords = maxWords,
    )

    @Test
    fun `LiteRT token budget is request-sensitive and always hard capped`() {
        assertEquals(18, GenerationOutputPolicy.maxOutputTokens(request(maxWords = 1)))
        assertEquals(196, GenerationOutputPolicy.maxOutputTokens(request(maxWords = 90)))
        assertEquals(
            GenerationOutputPolicy.HARD_MAX_OUTPUT_TOKENS,
            GenerationOutputPolicy.maxOutputTokens(request(maxWords = 120)),
        )
    }

    @Test
    fun `nudge respects lower requested word cap and never exceeds twenty`() {
        assertEquals(5, GenerationOutputPolicy.wordLimit(request(InsightTask.NUDGE, maxWords = 5)))
        assertEquals(20, GenerationOutputPolicy.wordLimit(request(InsightTask.NUDGE, maxWords = 90)))
        assertEquals(56, GenerationOutputPolicy.maxOutputTokens(request(InsightTask.NUDGE, 90)))
    }

    @Test
    fun `terminal validation trims and accepts text at the exact word limit`() {
        assertEquals(
            "one two three",
            GenerationOutputPolicy.validatedTerminalText(request(maxWords = 3), "  one two three  "),
        )
    }

    @Test
    fun `terminal validation rejects blank and excess words separately from token cap`() {
        assertThrows(GenerationOutputPolicyException::class.java) {
            GenerationOutputPolicy.validatedTerminalText(request(), " \n ")
        }
        val error = assertThrows(GenerationOutputPolicyException::class.java) {
            GenerationOutputPolicy.validatedTerminalText(request(maxWords = 2), "one two three")
        }
        assertTrue(error.message.orEmpty().contains("3 words"))
    }

    @Test
    fun `stream accumulator rejects before retaining excess terminal characters`() {
        val builder = StringBuilder()
        GenerationOutputPolicy.append(
            builder,
            "a".repeat(GenerationOutputPolicy.MAX_TERMINAL_CHARACTERS),
        )

        assertThrows(GenerationOutputPolicyException::class.java) {
            GenerationOutputPolicy.append(builder, "b")
        }
        assertEquals(GenerationOutputPolicy.MAX_TERMINAL_CHARACTERS, builder.length)
    }
}
