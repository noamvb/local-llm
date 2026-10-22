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

    private val activeDays = sampleFacts[1].copy(
        factId = "fact-active-days",
        sourceApp = AppSource.POOP_SCHEDULE,
        metricId = "poop.active_days",
        displayLabel = "Active logging days",
        displayValue = "23",
        denominator = 30L,
    )

    private val duration = activeDays.copy(
        factId = "fact-duration",
        metricId = "poop.duration",
        displayLabel = "Average duration",
        displayValue = "8",
        denominator = null,
        unit = "minutes",
    )

    @Test
    fun testNumberlessSentenceWithLabelKeywordPasses() {
        val result = SentenceCitationValidator.validate(
            "You logged entries on most active days.",
            listOf(activeDays),
        )
        assertEquals(AssistantTerminalStatus.VALIDATED, result.status)
        assertEquals(listOf(activeDays.factId), result.citations.single().citedFactIds)
    }

    @Test
    fun testNumberlessSentenceWithoutKeywordFails() {
        val result = SentenceCitationValidator.validate("You feel great on weekends.", listOf(activeDays))
        assertEquals(AssistantTerminalStatus.FAILED_VALIDATION, result.status)
    }

    @Test
    fun testMetricSuffixKeywordsNormalizeSimplePlurals() {
        val fact = activeDays.copy(displayLabel = "Total entries recorded", metricId = "poop.active_days")
        for (text in listOf("Your active day was logged.", "Your active days were logged.")) {
            val result = SentenceCitationValidator.validate(text, listOf(fact))
            assertEquals(text, AssistantTerminalStatus.VALIDATED, result.status)
        }
        val symptom = fact.copy(metricId = "poop.symptoms.rashes")
        val result = SentenceCitationValidator.validate("Your rash was recorded.", listOf(symptom))
        assertEquals(AssistantTerminalStatus.VALIDATED, result.status)
    }

    @Test
    fun testKeywordMustMatchAWholeWord() {
        val result = SentenceCitationValidator.validate("Your holidays were pleasant.", listOf(activeDays))
        assertEquals(AssistantTerminalStatus.FAILED_VALIDATION, result.status)
    }

    @Test
    fun testSizeKeywordMatchesSimplePlural() {
        val fact = activeDays.copy(displayLabel = "Size small entries", metricId = "poop.small")
        val result = SentenceCitationValidator.validate("Your sizes were recorded.", listOf(fact))
        assertEquals(AssistantTerminalStatus.VALIDATED, result.status)
    }

    @Test
    fun testFactWithNoKeywordsRequiresLabelOrNumber() {
        val fact = activeDays.copy(displayLabel = "Total entries recorded", metricId = "poop.entries")
        assertEquals(
            AssistantTerminalStatus.FAILED_VALIDATION,
            SentenceCitationValidator.validate("Entries were recorded on weekends.", listOf(fact)).status,
        )
        assertEquals(
            AssistantTerminalStatus.VALIDATED,
            SentenceCitationValidator.validate("Total entries recorded were reviewed.", listOf(fact)).status,
        )
        assertEquals(
            AssistantTerminalStatus.VALIDATED,
            SentenceCitationValidator.validate("You logged 23 records.", listOf(fact)).status,
        )
    }

    @Test
    fun testMultiParagraphTextHasSeparateCitations() {
        val result = SentenceCitationValidator.validate(
            "You logged 23 of 30 days.\n\nYour average duration was 8 minutes.",
            listOf(activeDays, duration),
        )
        assertEquals(AssistantTerminalStatus.VALIDATED, result.status)
        assertEquals(2, result.citations.size)
        assertEquals(listOf(activeDays.factId), result.citations[0].citedFactIds)
        assertEquals(listOf(duration.factId), result.citations[1].citedFactIds)
    }

    @Test
    fun testParagraphBreakSplitsSentencesWithoutPunctuation() {
        val result = SentenceCitationValidator.validate(
            "You logged 23 of 30 days\n\nYour average duration was 8 minutes",
            listOf(activeDays, duration),
        )
        assertEquals(AssistantTerminalStatus.VALIDATED, result.status)
        assertEquals(2, result.citations.size)
        assertEquals("You logged 23 of 30 days", result.citations[0].sentence)
        assertEquals("Your average duration was 8 minutes", result.citations[1].sentence)
    }

    @Test
    fun testLegacyDisplayValueGroundsEveryNumber() {
        val fact = activeDays.copy(displayValue = "23 of 30", denominator = null)
        val result = SentenceCitationValidator.validate("You logged 23 of 30 days.", listOf(fact))
        assertEquals(AssistantTerminalStatus.VALIDATED, result.status)
        assertEquals(listOf(fact.factId), result.citations.single().citedFactIds)
    }

    @Test
    fun testLegacySecondNumberIsGroundedWithoutOrdinalExemption() {
        val fact = activeDays.copy(displayValue = "23 of 31", denominator = null)
        val result = SentenceCitationValidator.validate("You logged 23 of 31 days.", listOf(fact))
        assertEquals(AssistantTerminalStatus.VALIDATED, result.status)
    }

    @Test
    fun testLegacyDisplayValueWithUnitGroundsItsNumber() {
        val result = SentenceCitationValidator.validate(
            "Your average duration was 8 minutes.",
            listOf(duration.copy(displayValue = "8 min")),
        )
        assertEquals(AssistantTerminalStatus.VALIDATED, result.status)
    }

    @Test
    fun testRoundingAllowsNearestWholeNumberButRejectsInventedNumber() {
        val facts = listOf(duration.copy(displayValue = "7.6"))
        assertEquals(
            AssistantTerminalStatus.VALIDATED,
            SentenceCitationValidator.validate("Your duration was about 8 minutes.", facts).status,
        )
        assertEquals(
            AssistantTerminalStatus.VALIDATED,
            SentenceCitationValidator.validate("Your duration was 7.6 minutes.", facts).status,
        )
        assertEquals(
            AssistantTerminalStatus.FAILED_VALIDATION,
            SentenceCitationValidator.validate("Your duration was 6 minutes.", facts).status,
        )
    }

    @Test
    fun testRoundingUsesHalfUpAtSentencePrecision() {
        val facts = listOf(duration.copy(displayValue = "8.25"))
        assertEquals(
            AssistantTerminalStatus.VALIDATED,
            SentenceCitationValidator.validate("Your duration was 8.3 minutes.", facts).status,
        )
        for (text in listOf("Your duration was 8.2 minutes.", "Your duration was 8.2501 minutes.")) {
            assertEquals(text, AssistantTerminalStatus.FAILED_VALIDATION, SentenceCitationValidator.validate(text, facts).status)
        }
    }

    @Test
    fun testFactPeriodDateNumbersAreGrounded() {
        val fact = activeDays.copy(displayValue = "3", denominator = null, periodEnd = "2026-10-22")
        for (text in listOf("On 22 October you logged 3 entries.", "On 10/22/2026 you logged 3 entries.")) {
            assertEquals(text, AssistantTerminalStatus.VALIDATED, SentenceCitationValidator.validate(text, listOf(fact)).status)
        }
        assertEquals(
            AssistantTerminalStatus.FAILED_VALIDATION,
            SentenceCitationValidator.validate("On 23 October you logged 3 entries.", listOf(fact)).status,
        )
    }

    @Test
    fun testPeriodStartDateIsGroundedOnlyWithACitedFact() {
        val fact = activeDays.copy(displayValue = "3", denominator = null, periodStart = "2026-10-18")
        assertEquals(
            AssistantTerminalStatus.VALIDATED,
            SentenceCitationValidator.validate("On 18 October you logged 3 entries.", listOf(fact)).status,
        )
        val result = SentenceCitationValidator.validate("On 18 October you felt great.", listOf(fact))
        assertEquals(AssistantTerminalStatus.FAILED_VALIDATION, result.status)
        assertTrue(result.citations.single().citedFactIds.isEmpty())
    }

    @Test
    fun testInventedNumberStillFailsWithKeywordCitation() {
        val result = SentenceCitationValidator.validate("You logged 41 entries.", listOf(activeDays))
        assertEquals(AssistantTerminalStatus.FAILED_VALIDATION, result.status)
        assertTrue(result.validationIssues.any { it.contains("41") })
    }

    @Test
    fun testCurrencyAndPercentMismatchesStillFail() {
        for (text in listOf("Your active days were $23.", "Your active days were 23%.")) {
            assertEquals(text, AssistantTerminalStatus.FAILED_VALIDATION, SentenceCitationValidator.validate(text, listOf(activeDays)).status)
        }
    }

    @Test
    fun testPercentValueRemainsGrounded() {
        val fact = activeDays.copy(displayValue = "23%", denominator = null)
        val result = SentenceCitationValidator.validate("Your active days were 23%.", listOf(fact))
        assertEquals(AssistantTerminalStatus.VALIDATED, result.status)
    }

    @Test
    fun testUnitMismatchCannotUseOrdinalOrDateExemption() {
        val fact = activeDays.copy(displayValue = "3", denominator = null, periodEnd = "2026-10-22")
        for (text in listOf("Your active days were $7.", "Your active days were 22%.")) {
            assertEquals(text, AssistantTerminalStatus.FAILED_VALIDATION, SentenceCitationValidator.validate(text, listOf(fact)).status)
        }
    }
}
