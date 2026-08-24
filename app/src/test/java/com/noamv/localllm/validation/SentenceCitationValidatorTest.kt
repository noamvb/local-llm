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
    fun testClinicalDiagnosticClaimFailsValidation() {
        val clinicalText = "Your 12 purchases mean you have IBS and need treatment."
        val result = SentenceCitationValidator.validate(clinicalText, sampleFacts)

        assertEquals(AssistantTerminalStatus.FAILED_VALIDATION, result.status)
        assertTrue(result.finalOrEscapedText.startsWith("[UNVALIDATED GENERATION]:"))
        assertTrue(result.validationIssues.any { it.contains("clinical") || it.contains("diagnostic") })
    }
}
