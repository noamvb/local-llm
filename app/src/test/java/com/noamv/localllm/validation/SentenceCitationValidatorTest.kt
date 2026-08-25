package com.noamv.localllm.validation

import com.noamv.localllm.contract.v2.AppSource
import com.noamv.localllm.contract.v2.AssistantTerminalStatus
import com.noamv.localllm.contract.v2.FactEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceCitationValidatorTest {

    private val sampleFacts = listOf(
        FactEvidence(
            factId = "fact-spend",
            sourceApp = AppSource.CANNSHEET,
            sourceContractVersion = 2,
            metricId = "cannsheet.recorded_spend",
            displayLabel = "Total Spend",
            displayValue = "$120.50",
            timezone = "America/New_York",
            asOfTime = 1000L,
            sourceRevision = "rev-1",
        ),
        FactEvidence(
            factId = "fact-purchases",
            sourceApp = AppSource.CANNSHEET,
            sourceContractVersion = 2,
            metricId = "cannsheet.purchase_count",
            displayLabel = "Purchases",
            displayValue = "12",
            denominator = 12L,
            timezone = "America/New_York",
            asOfTime = 1000L,
            sourceRevision = "rev-1",
        ),
    )

    @Test
    fun testValidGroundedTextPassesValidationWithCitations() {
        val text = "You logged 12 purchases totaling $120.50 this month."
        val result = SentenceCitationValidator.validate(text, sampleFacts)

        assertEquals(AssistantTerminalStatus.VALIDATED, result.status)
        assertEquals(text, result.finalOrEscapedText)
        assertEquals(0, result.validationIssues.size)
        assertEquals(1, result.citations.size)
        assertTrue(result.citations[0].citedFactIds.contains("fact-spend"))
        assertTrue(result.citations[0].citedFactIds.contains("fact-purchases"))
    }

    @Test
    fun testUngroundedHallucinatedNumberFailsValidation() {
        val hallucinatedText = "You logged 12 purchases totaling $999.00 this month."
        val result = SentenceCitationValidator.validate(hallucinatedText, sampleFacts)

        assertEquals(AssistantTerminalStatus.FAILED_VALIDATION, result.status)
        assertTrue(result.finalOrEscapedText.startsWith("[UNVALIDATED GENERATION]:"))
        assertTrue(result.validationIssues.any { it.contains("999") })
    }

    @Test
    fun testBidiOverrideFailsValidation() {
        val bidiText = "You spent $120.50 \u202Ereversed text"
        val result = SentenceCitationValidator.validate(bidiText, sampleFacts)
        assertEquals(AssistantTerminalStatus.FAILED_VALIDATION, result.status)
        assertTrue(result.validationIssues.any { it.contains("override") || it.contains("Bidirectional") })
    }

    @Test
    fun testControlCharactersFailValidation() {
        val ctrlText = "You spent $120.50 \u0000 with null byte"
        val result = SentenceCitationValidator.validate(ctrlText, sampleFacts)
        assertEquals(AssistantTerminalStatus.FAILED_VALIDATION, result.status)
        assertTrue(result.validationIssues.any { it.contains("Control") })
    }

    @Test
    fun testSystemPromptLeakageFailsValidation() {
        val leakText = "You spent $120.50 according to system prompt instructions."
        val result = SentenceCitationValidator.validate(leakText, sampleFacts)
        assertEquals(AssistantTerminalStatus.FAILED_VALIDATION, result.status)
        assertTrue(result.validationIssues.any { it.contains("leakage") || it.contains("System prompt") })
    }

    @Test
    fun testRefusalTextFailsValidation() {
        val refusalText = "I cannot answer this request as an AI language model."
        val result = SentenceCitationValidator.validate(refusalText, sampleFacts)
        assertEquals(AssistantTerminalStatus.FAILED_VALIDATION, result.status)
        assertTrue(result.validationIssues.any { it.contains("refusal") })
    }

    @Test
    fun testCausalClaimFailsValidation() {
        val causalText = "Your high spend was caused by increased stress."
        val result = SentenceCitationValidator.validate(causalText, sampleFacts)
        assertEquals(AssistantTerminalStatus.FAILED_VALIDATION, result.status)
        assertTrue(result.validationIssues.any { it.contains("causal") || it.contains("diagnostic") })
    }

    @Test
    fun testNoNumberAssertiveSentenceWithoutCitationsFailsValidation() {
        val ungroundedSentence = "You frequently consume cannabis during weekends and feel great."
        val result = SentenceCitationValidator.validate(ungroundedSentence, sampleFacts)
        assertEquals(AssistantTerminalStatus.FAILED_VALIDATION, result.status)
        assertTrue(result.validationIssues.any { it.contains("without citing any verified fact") })
    }

    @Test
    fun testHallucinatedThousandNumberFailsValidation() {
        val text = "You spent $1,000 on purchases this month."
        val result = SentenceCitationValidator.validate(text, sampleFacts)
        assertEquals(AssistantTerminalStatus.FAILED_VALIDATION, result.status)
        assertTrue(result.validationIssues.any { it.contains("1,000") })
    }

    @Test
    fun testDecimalNormalizationTruePositive() {
        val facts = listOf(
            FactEvidence(
                factId = "fact-grams",
                sourceApp = AppSource.CANNSHEET,
                sourceContractVersion = 2,
                metricId = "cannsheet.grams",
                displayLabel = "Grams",
                displayValue = "2.5",
                timezone = "America/New_York",
                asOfTime = 1000L,
                sourceRevision = "rev-1",
            ),
        )
        val text = "You consumed 2.50 grams this week."
        val result = SentenceCitationValidator.validate(text, facts)
        assertEquals(AssistantTerminalStatus.VALIDATED, result.status)
    }

    @Test
    fun testCrossFactNumeralCollisionUnitMismatchFails() {
        // 12 is a count of purchases, not currency
        val text = "Your spend was $12 this month."
        val result = SentenceCitationValidator.validate(text, sampleFacts)
        assertEquals(AssistantTerminalStatus.FAILED_VALIDATION, result.status)
    }
}
