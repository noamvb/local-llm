package com.noamv.localllm.validation

import com.noamv.localllm.contract.v2.AssistantTerminalResult
import com.noamv.localllm.contract.v2.AssistantTerminalStatus
import com.noamv.localllm.contract.v2.FactEvidence
import com.noamv.localllm.contract.v2.LimitationId
import com.noamv.localllm.contract.v2.SentenceCitation
import java.math.BigDecimal
import java.util.Locale
import java.util.regex.Pattern

/**
 * Sentence-level citation and groundedness validator.
 *
 * Enforces all 11 architecture invariant 9 checks:
 * 1. Non-empty output
 * 2. Control-character rejection (\p{Cc}, \u0000)
 * 3. Bidirectional-override rejection (U+202A–202E, U+2066–2069, U+200E, U+200F, U+061C)
 * 4. Prompt/system-instruction leakage detection
 * 5. Refusal-text detection
 * 6. Clinical diagnostic and causal claim rejection
 * 7. Navigation target validation
 * 8. Every assertive sentence cites >= 1 fact (citedFactIds.isEmpty() check)
 * 9. Per-sentence metric and unit comparison (fact.unit)
 * 10. Number normalization via BigDecimal (comma-grouped, signed, decimal normalization)
 * 11. Safety escaping of unvalidated drafts
 */
object SentenceCitationValidator {

    private val NUMBER_PATTERN = Pattern.compile(
        "(?:\\$|\\b)[+-]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?%?\\b",
    )

    private val CLINICAL_OR_CAUSAL_PATTERN = Pattern.compile(
        "\\b(you have (?:ibs|cancer|crohn|infection|disease)|you suffer from|this diagnoses|this indicates disease|you should take medication|" +
            "caused by|caused|due to|because of|led to|linked to|associated with|results in|responsible for)\\b",
        Pattern.CASE_INSENSITIVE,
    )

    private val BIDI_OVERRIDE_PATTERN = Pattern.compile(
        "[\u202A-\u202E\u2066-\u2069\u200E\u200F\u061C]",
    )

    private val CONTROL_CHAR_PATTERN = Pattern.compile(
        "[\\p{Cc}&&[^\n\r\t]]|\\u0000",
    )

    private val PROMPT_LEAKAGE_PATTERN = Pattern.compile(
        "\\b(system prompt|system instruction|developer prompt|internal guidelines|you are a helpful assistant|ignore previous instructions)\\b",
        Pattern.CASE_INSENSITIVE,
    )

    private val REFUSAL_PATTERN = Pattern.compile(
        "\\b(i cannot answer|as an ai language model|i am unable to assist|i apologize, but i cannot|i'm unable to answer)\\b",
        Pattern.CASE_INSENSITIVE,
    )

    private val NAVIGATION_TARGET_PATTERN = Pattern.compile(
        "\\b(?:cannsheet|poop)://[a-zA-Z0-9_/.-]+",
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

        // 1. Control characters
        if (CONTROL_CHAR_PATTERN.matcher(trimmed).find()) {
            return AssistantTerminalResult(
                status = AssistantTerminalStatus.FAILED_VALIDATION,
                finalOrEscapedText = escapeForSafety(trimmed),
                validationIssues = listOf("Control characters detected in generation."),
            )
        }

        // 2. Bidirectional override characters
        if (BIDI_OVERRIDE_PATTERN.matcher(trimmed).find()) {
            return AssistantTerminalResult(
                status = AssistantTerminalStatus.FAILED_VALIDATION,
                finalOrEscapedText = escapeForSafety(trimmed),
                validationIssues = listOf("Bidirectional override characters (e.g. U+202A..U+202E, U+2066..U+2069, U+200E, U+200F, U+061C) detected."),
            )
        }

        // 3. Prompt or system instruction leakage
        if (PROMPT_LEAKAGE_PATTERN.matcher(trimmed).find()) {
            return AssistantTerminalResult(
                status = AssistantTerminalStatus.FAILED_VALIDATION,
                finalOrEscapedText = escapeForSafety(trimmed),
                validationIssues = listOf("System prompt or instruction leakage detected in generation."),
            )
        }

        // 4. Model refusal text
        if (REFUSAL_PATTERN.matcher(trimmed).find()) {
            return AssistantTerminalResult(
                status = AssistantTerminalStatus.FAILED_VALIDATION,
                finalOrEscapedText = escapeForSafety(trimmed),
                validationIssues = listOf("Model refusal text detected in generation."),
            )
        }

        // 5. Clinical diagnostic or causal claims
        if (CLINICAL_OR_CAUSAL_PATTERN.matcher(trimmed).find()) {
            return AssistantTerminalResult(
                status = AssistantTerminalStatus.FAILED_VALIDATION,
                finalOrEscapedText = escapeForSafety(trimmed),
                validationIssues = listOf("Prohibited clinical diagnostic claim or causal language detected in generation."),
            )
        }

        // 6. Navigation target check if mentioned
        if (trimmed.contains("navigationTarget", ignoreCase = true) || trimmed.contains("://")) {
            val invalidNav = validateNavigationTargets(trimmed)
            if (invalidNav != null) {
                return AssistantTerminalResult(
                    status = AssistantTerminalStatus.FAILED_VALIDATION,
                    finalOrEscapedText = escapeForSafety(trimmed),
                    validationIssues = listOf("Invalid navigation target: $invalidNav"),
                )
            }
        }

        val sentences = splitIntoSentences(trimmed)
        val citations = mutableListOf<SentenceCitation>()
        val issues = mutableListOf<String>()

        // Pre-parse facts into normalized numeric and unit representations
        val parsedFacts = facts.map { fact ->
            ParsedFact(
                fact = fact,
                valueNumber = parseNormalizedNumber(fact.displayValue),
                denominatorNumber = fact.denominator?.let { BigDecimal(it) },
                coverageNumber = fact.coveragePercent?.let { BigDecimal(it) },
            )
        }

        for (sentence in sentences) {
            val sentenceLower = sentence.lowercase(Locale.ROOT)
            val citedFactIds = mutableListOf<String>()

            // Extract numeric tokens in the sentence
            val matcher = NUMBER_PATTERN.matcher(sentence)
            val numbersInSentence = mutableListOf<String>()
            while (matcher.find()) {
                numbersInSentence.add(matcher.group())
            }

            for (parsed in parsedFacts) {
                val fact = parsed.fact
                val labelLower = fact.displayLabel.lowercase(Locale.ROOT)
                val metricLower = fact.metricId.lowercase(Locale.ROOT)

                val matchesLabel = (labelLower.isNotEmpty() && sentenceLower.contains(labelLower)) ||
                    (metricLower.isNotEmpty() && sentenceLower.contains(metricLower.substringAfter('.')))

                val matchesNumber = numbersInSentence.any { numToken ->
                    val numParsed = parseNormalizedNumber(numToken) ?: return@any false
                    val matchesVal = parsed.valueNumber != null && numParsed.compareTo(parsed.valueNumber) == 0
                    val matchesDenom = parsed.denominatorNumber != null && numParsed.compareTo(parsed.denominatorNumber) == 0
                    val matchesCov = parsed.coverageNumber != null && numParsed.compareTo(parsed.coverageNumber) == 0

                    if (matchesVal || matchesDenom || matchesCov) {
                        // Check unit compatibility if unit is present
                        isUnitCompatible(numToken, fact.unit, fact.displayValue)
                    } else {
                        false
                    }
                }

                if (matchesLabel || matchesNumber) {
                    citedFactIds.add(fact.factId)
                }
            }

            // T4a: Assertive sentence must cite >= 1 fact
            val isAssertive = isAssertiveSentence(sentence)
            if (isAssertive && citedFactIds.isEmpty()) {
                issues.add("Sentence makes assertions without citing any verified fact: '$sentence'")
            }

            // T4b, T4e: Check if numbers in the sentence are grounded and unit-compatible
            for (numToken in numbersInSentence) {
                val numParsed = parseNormalizedNumber(numToken)
                if (numParsed != null) {
                    val matchingFact = parsedFacts.firstOrNull { parsed ->
                        val matchesNum = (parsed.valueNumber != null && numParsed.compareTo(parsed.valueNumber) == 0) ||
                            (parsed.denominatorNumber != null && numParsed.compareTo(parsed.denominatorNumber) == 0) ||
                            (parsed.coverageNumber != null && numParsed.compareTo(parsed.coverageNumber) == 0)
                        matchesNum && isUnitCompatible(numToken, parsed.fact.unit, parsed.fact.displayValue)
                    }

                    if (matchingFact == null) {
                        // Check if it is a common benign numeral like 1, 2, 7, 30 in a grounded sentence
                        val isStandardPeriodOrOrdinal = numParsed in ALLOWED_ORDINAL_NUMBERS
                        if (!isStandardPeriodOrOrdinal || citedFactIds.isEmpty()) {
                            issues.add("Ungrounded or unit-mismatched number '$numToken' found in sentence: '$sentence'")
                        }
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

    private fun isAssertiveSentence(sentence: String): Boolean {
        val trimmed = sentence.trim()
        if (trimmed.length < 10) return false
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower.startsWith("here is") || lower.startsWith("here are") || lower.startsWith("summary:") || lower.startsWith("notes:")) {
            return false
        }
        return true
    }

    private fun parseNormalizedNumber(token: String): BigDecimal? {
        val clean = token.replace("$", "")
            .replace("%", "")
            .replace(",", "")
            .trim()
        return try {
            BigDecimal(clean)
        } catch (_: Exception) {
            null
        }
    }

    private fun isUnitCompatible(numToken: String, factUnit: String?, factDisplayValue: String): Boolean {
        if (numToken.startsWith("$")) {
            val factHasDollar = factDisplayValue.contains("$") || factUnit.equals("USD", ignoreCase = true) || factUnit.equals("$", ignoreCase = true)
            if (!factHasDollar) return false
        }
        if (numToken.endsWith("%")) {
            val factHasPercent = factDisplayValue.contains("%") || factUnit.equals("%", ignoreCase = true) || factUnit.equals("percent", ignoreCase = true)
            if (!factHasPercent) return false
        }
        return true
    }

    private fun validateNavigationTargets(text: String): String? {
        val uriMatcher = Pattern.compile("[a-zA-Z0-9]+://[^\\s\"'<>]+").matcher(text)
        while (uriMatcher.find()) {
            val uri = uriMatcher.group()
            if (!NAVIGATION_TARGET_PATTERN.matcher(uri).matches()) {
                return uri
            }
        }
        return null
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

    internal fun escapeForSafety(text: String): String {
        val safe = text
            .replace(BIDI_OVERRIDE_PATTERN.toRegex(), "")
            .replace(CONTROL_CHAR_PATTERN.toRegex(), "")
            .replace("\n", " ")
            .trim()
        return "[UNVALIDATED GENERATION]: $safe"
    }

    private data class ParsedFact(
        val fact: FactEvidence,
        val valueNumber: BigDecimal?,
        val denominatorNumber: BigDecimal?,
        val coverageNumber: BigDecimal?,
    )

    private val ALLOWED_ORDINAL_NUMBERS = setOf(
        BigDecimal("1"),
        BigDecimal("2"),
        BigDecimal("7"),
        BigDecimal("30"),
        BigDecimal("90"),
    )
}

