package com.noamv.localllm.service

import com.noamv.localllm.contract.Fact
import com.noamv.localllm.contract.InsightContract
import com.noamv.localllm.contract.InsightRequest
import com.noamv.localllm.contract.InsightTask
import com.noamv.localllm.contract.Period
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Fail-closed validation performed before a request can enter the native-engine queue. */
internal object V1RequestValidator {
    const val MAX_REQUEST_BYTES = 32 * 1024
    const val MAX_FACTS_PER_PERIOD = 64
    const val MAX_TOTAL_FACTS = 96
    const val MAX_WORDS = 120

    private const val MAX_CLIENT_ID_CODE_POINTS = 64
    private const val MAX_CLIENT_ID_BYTES = 128
    private const val MAX_SUBJECT_CODE_POINTS = 256
    private const val MAX_SUBJECT_BYTES = 1_024
    private const val MAX_PERIOD_LABEL_CODE_POINTS = 128
    private const val MAX_PERIOD_LABEL_BYTES = 512
    private const val MAX_FACT_LABEL_CODE_POINTS = 128
    private const val MAX_FACT_LABEL_BYTES = 512
    private const val MAX_FACT_VALUE_CODE_POINTS = 256
    private const val MAX_FACT_VALUE_BYTES = 1_024
    private const val MAX_FACT_NOTE_CODE_POINTS = 256
    private const val MAX_FACT_NOTE_BYTES = 1_024

    private val clientIdPattern = Regex("[a-z0-9][a-z0-9._-]*")

    fun rawErrorMessage(requestJson: String): String? {
        val bytes = requestJson.toByteArray(Charsets.UTF_8).size
        return if (bytes > MAX_REQUEST_BYTES) {
            "Request JSON exceeds the $MAX_REQUEST_BYTES-byte contract v1 limit."
        } else {
            null
        }
    }

    fun errorMessage(request: InsightRequest): String? {
        if (request.contractVersion != InsightContract.VERSION) {
            return if (request.contractVersion > InsightContract.VERSION) {
                "Client asked for contract v${request.contractVersion}; " +
                    "this service implements v${InsightContract.VERSION}."
            } else {
                "Contract v${request.contractVersion} is not supported."
            }
        }
        if (request.resultSchema != null) {
            return "Structured result schemas are not implemented by contract v1."
        }
        validateString(
            field = "clientId",
            value = request.clientId,
            maxCodePoints = MAX_CLIENT_ID_CODE_POINTS,
            maxBytes = MAX_CLIENT_ID_BYTES,
        )?.let { return it }
        if (!clientIdPattern.matches(request.clientId)) {
            return "clientId must start with a lowercase letter or digit and contain only " +
                "lowercase letters, digits, '.', '_' or '-'."
        }
        validateString(
            field = "subject",
            value = request.subject,
            maxCodePoints = MAX_SUBJECT_CODE_POINTS,
            maxBytes = MAX_SUBJECT_BYTES,
        )?.let { return it }

        if (request.maxWords !in 1..MAX_WORDS) {
            return "maxWords must be between 1 and $MAX_WORDS."
        }
        if (request.facts.size > MAX_FACTS_PER_PERIOD) {
            return "facts may contain at most $MAX_FACTS_PER_PERIOD items."
        }
        if (request.comparisonFacts.size > MAX_FACTS_PER_PERIOD) {
            return "comparisonFacts may contain at most $MAX_FACTS_PER_PERIOD items."
        }
        if (request.facts.size + request.comparisonFacts.size > MAX_TOTAL_FACTS) {
            return "A request may contain at most $MAX_TOTAL_FACTS facts in total."
        }

        validatePeriod("period", request.period)?.let { return it }
        validatePeriod("comparisonPeriod", request.comparisonPeriod)?.let { return it }
        request.facts.forEachIndexed { index, fact ->
            validateFact("facts[$index]", fact)?.let { return it }
        }
        request.comparisonFacts.forEachIndexed { index, fact ->
            validateFact("comparisonFacts[$index]", fact)?.let { return it }
        }

        return when (request.task) {
            InsightTask.PERIOD_SUMMARY,
            InsightTask.NUDGE,
            -> when {
                request.period == null -> "${request.task.name} requires a period."
                request.facts.isEmpty() -> "${request.task.name} requires at least one fact."
                request.comparisonPeriod != null || request.comparisonFacts.isNotEmpty() ->
                    "${request.task.name} does not accept comparison data."
                request.task == InsightTask.NUDGE && !request.safety.lockScreenSafe ->
                    "NUDGE requires lockScreenSafe=true."
                else -> null
            }
            InsightTask.PERIOD_COMPARISON -> when {
                request.period == null || request.comparisonPeriod == null ->
                    "PERIOD_COMPARISON requires both period objects."
                request.facts.isEmpty() || request.comparisonFacts.isEmpty() ->
                    "PERIOD_COMPARISON requires facts for both periods."
                datedComparisonOverlaps(request.period, request.comparisonPeriod) ->
                    "comparisonPeriod must end before period starts when dates are supplied."
                else -> null
            }
        }
    }

    private fun validateFact(field: String, fact: Fact): String? {
        validateString(
            field = "$field.label",
            value = fact.label,
            maxCodePoints = MAX_FACT_LABEL_CODE_POINTS,
            maxBytes = MAX_FACT_LABEL_BYTES,
        )?.let { return it }
        validateString(
            field = "$field.value",
            value = fact.value,
            maxCodePoints = MAX_FACT_VALUE_CODE_POINTS,
            maxBytes = MAX_FACT_VALUE_BYTES,
        )?.let { return it }
        fact.note?.let { note ->
            validateString(
                field = "$field.note",
                value = note,
                maxCodePoints = MAX_FACT_NOTE_CODE_POINTS,
                maxBytes = MAX_FACT_NOTE_BYTES,
            )?.let { return it }
        }
        return null
    }

    private fun validatePeriod(field: String, period: Period?): String? {
        period ?: return null
        validateString(
            field = "$field.label",
            value = period.label,
            maxCodePoints = MAX_PERIOD_LABEL_CODE_POINTS,
            maxBytes = MAX_PERIOD_LABEL_BYTES,
        )?.let { return it }

        if ((period.start == null) != (period.end == null)) {
            return "$field.start and $field.end must either both be present or both be absent."
        }
        val start = period.start ?: return null
        val end = requireNotNull(period.end)
        val parsedStart = parseDate(start) ?: return "$field.start must be YYYY-MM-DD."
        val parsedEnd = parseDate(end) ?: return "$field.end must be YYYY-MM-DD."
        if (parsedStart > parsedEnd) return "$field.start must not be after $field.end."
        return null
    }

    private fun parseDate(value: String): LocalDate? {
        if (value.length != ISO_DATE_LENGTH || hasForbiddenCharacters(value)) return null
        return try {
            LocalDate.parse(value).takeIf { it.toString() == value }
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun datedComparisonOverlaps(period: Period, comparisonPeriod: Period): Boolean {
        val currentStart = period.start?.let(::parseDate) ?: return false
        val comparisonEnd = comparisonPeriod.end?.let(::parseDate) ?: return false
        return comparisonEnd >= currentStart
    }

    private fun validateString(
        field: String,
        value: String,
        maxCodePoints: Int,
        maxBytes: Int,
    ): String? {
        if (value.isBlank()) return "$field must not be blank."
        if (value != value.trim()) return "$field must not have leading or trailing whitespace."
        if (hasForbiddenCharacters(value)) {
            return "$field contains a control, invisible format, or malformed Unicode character."
        }
        if (value.codePointCount(0, value.length) > maxCodePoints) {
            return "$field exceeds the $maxCodePoints-character limit."
        }
        if (value.toByteArray(Charsets.UTF_8).size > maxBytes) {
            return "$field exceeds the $maxBytes-byte UTF-8 limit."
        }
        return null
    }

    private fun hasForbiddenCharacters(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val type = Character.getType(codePoint)
            if (
                Character.isISOControl(codePoint) ||
                type == Character.FORMAT.toInt() ||
                type == Character.LINE_SEPARATOR.toInt() ||
                type == Character.PARAGRAPH_SEPARATOR.toInt() ||
                type == Character.SURROGATE.toInt()
            ) {
                return true
            }
            index += Character.charCount(codePoint)
        }
        return false
    }

    private const val ISO_DATE_LENGTH = 10
}
