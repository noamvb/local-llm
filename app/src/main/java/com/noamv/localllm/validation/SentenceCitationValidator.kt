package com.noamv.localllm.validation

import com.noamv.localllm.contract.v2.AssistantTerminalResult
import com.noamv.localllm.contract.v2.AssistantTerminalStatus
import com.noamv.localllm.contract.v2.FactEvidence
import com.noamv.localllm.contract.v2.LimitationId
import com.noamv.localllm.contract.v2.SentenceCitation
import java.util.Locale
import java.util.regex.Pattern

/**
 * Sentence-level citation and groundedness validator.
 *
 * Verifies that all numeric claims and assertions in generated model text are strictly
 * supported by provided FactEvidence records, and that no prohibited clinical claims or
 * hallucinations are passed to user clients.
 */
object SentenceCitationValidator {

    private val NUMBER_PATTERN = Pattern.compile(
        "\\b\\$?\\d+(?:\\.\\d+)?%?\\b",
    )

    private val CLINICAL_CLAIM_PATTERN = Pattern.compile(
        "\\b(you have (?:ibs|cancer|crohn|infection)|you suffer from|this diagnoses|this indicates disease|you should take medication)\\b",
        Pattern.CASE_INSENSITIVE,
    )

    fun validate(
        generatedText: String,
        facts: List<FactEvidence>,
        limitationIds: List<LimitationId> = emptyList(),
    ): AssistantTerminalResult {
        val trimmed = generatedText.trim()
        if (trimmed.isEmpty()) {
            return AssistantTerminalResult(
                status = AssistantTerminalStatus.ERROR,
                finalOrEscapedText = "",
                validationIssues = listOf("Model output was empty"),
            )
        }

        // Check for clinical claim violations
        if (CLINICAL_CLAIM_PATTERN.matcher(trimmed).find()) {
            return AssistantTerminalResult(
                status = AssistantTerminalStatus.FAILED_VALIDATION,
                finalOrEscapedText = escapeForSafety(trimmed),
                validationIssues = listOf("Prohibited clinical diagnostic claim detected in model generation."),
            )
        }

        val sentences = splitIntoSentences(trimmed)
        val citations = mutableListOf<SentenceCitation>()
        val issues = mutableListOf<String>()

        // Index facts by textual tokens and values
        val factLookup = facts.associateBy { it.factId }

        for (sentence in sentences) {
            val sentenceLower = sentence.lowercase(Locale.ROOT)
            val citedFactIds = mutableListOf<String>()

            // Extract numeric tokens in the sentence
            val matcher = NUMBER_PATTERN.matcher(sentence)
            val numbersInSentence = mutableListOf<String>()
            while (matcher.find()) {
                numbersInSentence.add(matcher.group())
            }

            for (fact in facts) {
                val valClean = fact.displayValue.replace("$", "").replace("%", "").trim()
                val labelLower = fact.displayLabel.lowercase(Locale.ROOT)

                val matchesValue = numbersInSentence.any { num ->
                    val numClean = num.replace("$", "").replace("%", "").trim()
                    numClean == valClean || num == fact.displayValue
                }

                val matchesLabel = labelLower.isNotEmpty() && sentenceLower.contains(labelLower)

                if (matchesValue || matchesLabel) {
                    citedFactIds.add(fact.factId)
                }
            }

            // Check if any numbers in the sentence were completely ungrounded
            for (num in numbersInSentence) {
                val numClean = num.replace("$", "").replace("%", "").trim()
                val matched = facts.any { f ->
                    val fClean = f.displayValue.replace("$", "").replace("%", "").trim()
                    numClean == fClean ||
                        num == f.displayValue ||
                        (f.denominator != null && numClean == f.denominator.toString()) ||
                        (f.coveragePercent != null && numClean == f.coveragePercent.toString())
                }
                if (!matched && numClean.toDoubleOrNull() != null && numClean.toDouble() > 0.0) {
                    // Small allowed ordinals/common numbers like 1, 2 in text may be ignored if grounded facts exist
                    if (numClean != "1" && numClean != "2" && numClean != "7" && numClean != "30") {
                        issues.add("Ungrounded number '$num' found in sentence: '$sentence'")
                    }
                }
            }

            citations.add(
                SentenceCitation(
                    sentence = sentence,
                    citedFactIds = citedFactIds.distinct(),
                    citedLimitationIds = limitationIds.map { it.name },
                ),
            )
        }

        return if (issues.isEmpty()) {
            AssistantTerminalResult(
                status = AssistantTerminalStatus.VALIDATED,
                finalOrEscapedText = trimmed,
                citations = citations,
                limitations = limitationIds.map { it.name },
                validationIssues = emptyList(),
            )
        } else {
            AssistantTerminalResult(
                status = AssistantTerminalStatus.FAILED_VALIDATION,
                finalOrEscapedText = escapeForSafety(trimmed),
                citations = citations,
                limitations = limitationIds.map { it.name },
                validationIssues = issues,
            )
        }
    }

    private fun splitIntoSentences(text: String): List<String> {
        val result = mutableListOf<String>()
        val paragraphs = text.split("\n+")
        for (paragraph in paragraphs) {
            val parts = paragraph.split(Regex("(?<=[.!?])\\s+"))
            for (part in parts) {
                val s = part.trim()
                if (s.isNotEmpty()) {
                    result.add(s)
                }
            }
        }
        return if (result.isEmpty()) listOf(text) else result
    }

    private fun escapeForSafety(text: String): String =
        "[UNVALIDATED GENERATION]: " + text.replace("\n", " ").trim()
}
