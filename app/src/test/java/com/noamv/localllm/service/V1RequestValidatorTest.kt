package com.noamv.localllm.service

import com.noamv.localllm.contract.Fact
import com.noamv.localllm.contract.InsightContract
import com.noamv.localllm.contract.InsightRequest
import com.noamv.localllm.contract.InsightTask
import com.noamv.localllm.contract.Period
import com.noamv.localllm.contract.SafetyPolicy
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class V1RequestValidatorTest {
    private val valid = InsightRequest(
        clientId = "test-client",
        subject = "personal records",
        period = Period("the last 7 days", "2026-08-17", "2026-08-23"),
        facts = listOf(Fact("Entries recorded", "7", "one per day")),
    )

    @Test
    fun `accepts strict summary nudge and comparison shapes`() {
        assertNull(V1RequestValidator.errorMessage(valid))
        assertNull(
            V1RequestValidator.errorMessage(
                valid.copy(
                    task = InsightTask.NUDGE,
                    maxWords = 20,
                    safety = SafetyPolicy(lockScreenSafe = true),
                ),
            ),
        )
        assertNull(
            V1RequestValidator.errorMessage(
                valid.copy(
                    task = InsightTask.PERIOD_COMPARISON,
                    comparisonPeriod = Period("the prior 7 days", "2026-08-10", "2026-08-16"),
                    comparisonFacts = listOf(Fact("Entries recorded", "5")),
                ),
            ),
        )
    }

    @Test
    fun `raw request limit counts UTF-8 bytes before decoding`() {
        assertNull(V1RequestValidator.rawErrorMessage("a".repeat(V1RequestValidator.MAX_REQUEST_BYTES)))
        assertNotNull(
            V1RequestValidator.rawErrorMessage("a".repeat(V1RequestValidator.MAX_REQUEST_BYTES + 1)),
        )
        assertNotNull(
            V1RequestValidator.rawErrorMessage("é".repeat(V1RequestValidator.MAX_REQUEST_BYTES / 2 + 1)),
        )
    }

    @Test
    fun `rejects reserved schema and unsupported contract versions`() {
        assertErrorContains(valid.copy(resultSchema = "{}"), "not implemented")
        assertErrorContains(valid.copy(contractVersion = 2), "contract v2")
        assertErrorContains(valid.copy(contractVersion = 0), "not supported")
    }

    @Test
    fun `strict JSON enum decoding rejects an unknown task before validation`() {
        val encoded = InsightContract.json.encodeToString(InsightRequest.serializer(), valid)
            .replace("PERIOD_SUMMARY", "ARBITRARY_TASK")

        assertThrows(Exception::class.java) {
            InsightContract.json.decodeFromString(InsightRequest.serializer(), encoded)
        }
    }

    @Test
    fun `preserves deliberate v1 safety opt-outs required by the documented contract`() {
        assertNull(
            V1RequestValidator.errorMessage(
                valid.copy(safety = SafetyPolicy(forbidHealthClaims = false)),
            ),
        )
        assertNull(
            V1RequestValidator.errorMessage(
                valid.copy(safety = SafetyPolicy(forbidNewNumbers = false)),
            ),
        )
    }

    @Test
    fun `requires safe task-specific shapes`() {
        assertErrorContains(valid.copy(period = null), "requires a period")
        assertErrorContains(valid.copy(facts = emptyList()), "at least one fact")
        assertErrorContains(
            valid.copy(comparisonFacts = listOf(Fact("Earlier", "4"))),
            "does not accept comparison data",
        )
        assertErrorContains(valid.copy(task = InsightTask.NUDGE), "lockScreenSafe=true")

        val comparison = valid.copy(task = InsightTask.PERIOD_COMPARISON)
        assertErrorContains(comparison, "both period objects")
        assertErrorContains(
            comparison.copy(comparisonPeriod = Period("earlier")),
            "facts for both periods",
        )
        assertErrorContains(
            comparison.copy(
                comparisonPeriod = Period("overlap", "2026-08-16", "2026-08-17"),
                comparisonFacts = listOf(Fact("Entries", "4")),
            ),
            "must end before period starts",
        )
    }

    @Test
    fun `rejects blank padded malformed or dangerous strings`() {
        assertErrorContains(valid.copy(clientId = ""), "must not be blank")
        assertErrorContains(valid.copy(clientId = "Client"), "lowercase")
        assertErrorContains(valid.copy(clientId = "client id"), "lowercase")
        assertErrorContains(valid.copy(clientId = "."), "must start")
        assertErrorContains(valid.copy(subject = " records"), "leading or trailing")
        assertErrorContains(valid.copy(subject = "line\nbreak"), "control")
        assertErrorContains(valid.copy(subject = "records\u202Ehidden"), "invisible format")
        assertErrorContains(valid.copy(subject = "records\uD800"), "malformed Unicode")
        assertErrorContains(
            valid.copy(facts = listOf(Fact(" ", "7"))),
            "facts[0].label must not be blank",
        )
        assertErrorContains(
            valid.copy(facts = listOf(Fact("Entries", "7", ""))),
            "facts[0].note must not be blank",
        )
    }

    @Test
    fun `enforces code-point and UTF-8 field bounds`() {
        assertErrorContains(valid.copy(clientId = "a".repeat(65)), "64-character")
        assertNull(V1RequestValidator.errorMessage(valid.copy(subject = "😀".repeat(256))))
        assertErrorContains(valid.copy(subject = "😀".repeat(257)), "256-character")
        assertErrorContains(
            valid.copy(facts = listOf(Fact("Entries", "😀".repeat(257)))),
            "256-character",
        )
    }

    @Test
    fun `enforces fact list and total fact bounds`() {
        val fact = Fact("Entries", "1")
        assertNull(
            V1RequestValidator.errorMessage(
                valid.copy(facts = List(V1RequestValidator.MAX_FACTS_PER_PERIOD) { fact }),
            ),
        )
        assertErrorContains(
            valid.copy(facts = List(V1RequestValidator.MAX_FACTS_PER_PERIOD + 1) { fact }),
            "at most 64",
        )
        assertErrorContains(
            valid.copy(
                task = InsightTask.PERIOD_COMPARISON,
                facts = List(49) { fact },
                comparisonPeriod = Period("earlier"),
                comparisonFacts = List(48) { fact },
            ),
            "at most 96 facts",
        )
    }

    @Test
    fun `validates complete strict ISO dates and ascending ranges`() {
        assertErrorContains(
            valid.copy(period = Period("range", start = "2026-08-23")),
            "both be present",
        )
        assertErrorContains(
            valid.copy(period = Period("range", "2026-2-03", "2026-02-04")),
            "YYYY-MM-DD",
        )
        assertErrorContains(
            valid.copy(period = Period("range", "2026-02-30", "2026-03-01")),
            "YYYY-MM-DD",
        )
        assertErrorContains(
            valid.copy(period = Period("range", "2026-08-24", "2026-08-23")),
            "must not be after",
        )
        assertNull(
            V1RequestValidator.errorMessage(
                valid.copy(period = Period("leap day", "2024-02-29", "2024-02-29")),
            ),
        )
    }

    @Test
    fun `bounds maxWords`() {
        assertErrorContains(valid.copy(maxWords = 0), "between 1")
        assertNull(V1RequestValidator.errorMessage(valid.copy(maxWords = V1RequestValidator.MAX_WORDS)))
        assertErrorContains(valid.copy(maxWords = V1RequestValidator.MAX_WORDS + 1), "between 1")
    }

    private fun assertErrorContains(request: InsightRequest, expected: String) {
        val error = V1RequestValidator.errorMessage(request)
        assertTrue("Expected '$expected' in validation error, got $error", error.orEmpty().contains(expected))
    }
}
