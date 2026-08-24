@file:OptIn(ExperimentalSerializationApi::class)

package com.noamv.localllm.contract.v2

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Wire contracts for the Version 2 on-device assistant platform.
 *
 * This contract establishes the boundary between LocalLLM and the client apps (Cannsheet,
 * Poop Schedule) for assistant turns, capabilities discovery, aggregate queries, fact evidence,
 * and shared history.
 *
 * Rule: Clients compute and return facts, never rows. LocalLLM writes language and validates
 * citations against returned facts.
 */
@OptIn(ExperimentalSerializationApi::class)
object AssistantContractV2 {
    const val VERSION = 2
    const val GRAMMAR_VERSION = 1

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = false
        classDiscriminatorMode = kotlinx.serialization.json.ClassDiscriminatorMode.ALL_JSON_OBJECTS
    }
}

/** Known client data sources. */
@Serializable
enum class AppSource {
    @SerialName("CANNSHEET") CANNSHEET,
    @SerialName("POOP_SCHEDULE") POOP_SCHEDULE,
    @SerialName("UNKNOWN") UNKNOWN,
}

/** Allowlisted machine metric IDs across supported sources. */
@Serializable
enum class MetricId(val wireName: String) {
    @SerialName("cannsheet.consumption_count") CANNSHEET_CONSUMPTION_COUNT("cannsheet.consumption_count"),
    @SerialName("cannsheet.active_days") CANNSHEET_ACTIVE_DAYS("cannsheet.active_days"),
    @SerialName("cannsheet.time_band_counts") CANNSHEET_TIME_BAND_COUNTS("cannsheet.time_band_counts"),
    @SerialName("cannsheet.weekday_counts") CANNSHEET_WEEKDAY_COUNTS("cannsheet.weekday_counts"),
    @SerialName("cannsheet.product_log_counts") CANNSHEET_PRODUCT_LOG_COUNTS("cannsheet.product_log_counts"),
    @SerialName("cannsheet.purchase_count") CANNSHEET_PURCHASE_COUNT("cannsheet.purchase_count"),
    @SerialName("cannsheet.recorded_spend") CANNSHEET_RECORDED_SPEND("cannsheet.recorded_spend"),
    @SerialName("cannsheet.recorded_spend_coverage") CANNSHEET_RECORDED_SPEND_COVERAGE("cannsheet.recorded_spend_coverage"),
    @SerialName("cannsheet.inventory_remaining") CANNSHEET_INVENTORY_REMAINING("cannsheet.inventory_remaining"),
    @SerialName("poop.entry_count") POOP_ENTRY_COUNT("poop.entry_count"),
    @SerialName("poop.active_days") POOP_ACTIVE_DAYS("poop.active_days"),
    @SerialName("poop.frequency_per_week") POOP_FREQUENCY_PER_WEEK("poop.frequency_per_week"),
    @SerialName("poop.average_interval") POOP_AVERAGE_INTERVAL("poop.average_interval"),
    @SerialName("poop.average_duration") POOP_AVERAGE_DURATION("poop.average_duration"),
    @SerialName("poop.time_band_counts") POOP_TIME_BAND_COUNTS("poop.time_band_counts"),
    @SerialName("poop.weekday_counts") POOP_WEEKDAY_COUNTS("poop.weekday_counts"),
    @SerialName("poop.bristol_type_counts") POOP_BRISTOL_TYPE_COUNTS("poop.bristol_type_counts"),
    @SerialName("poop.colour_counts") POOP_COLOUR_COUNTS("poop.colour_counts"),
    @SerialName("poop.size_counts") POOP_SIZE_COUNTS("poop.size_counts"),
    @SerialName("poop.symptom_counts") POOP_SYMPTOM_COUNTS("poop.symptom_counts"),
    @SerialName("poop.rating_averages") POOP_RATING_AVERAGES("poop.rating_averages"),
    @SerialName("UNKNOWN") UNKNOWN("unknown");

    companion object {
        fun fromWireName(name: String): MetricId =
            entries.firstOrNull { it.wireName == name } ?: UNKNOWN
    }
}

/** Time periods supported by the aggregate query grammar. */
@Serializable
@JsonClassDiscriminator("mode")
sealed interface QueryPeriod {
    @Serializable
    @SerialName("LAST_DAYS")
    data class LastDays(val days: Int) : QueryPeriod

    @Serializable
    @SerialName("EXPLICIT_DATES")
    data class ExplicitDates(val start: String, val end: String) : QueryPeriod

    @Serializable
    @SerialName("ALL_TIME")
    data object AllTime : QueryPeriod
}

/** Period comparison modes. */
@Serializable
enum class QueryComparison {
    @SerialName("NONE") NONE,
    @SerialName("PREVIOUS_EQUAL_PERIOD") PREVIOUS_EQUAL_PERIOD,
    @SerialName("UNKNOWN") UNKNOWN,
}

/** Groupings supported by the query grammar. */
@Serializable
enum class QueryGrouping {
    @SerialName("DAY") DAY,
    @SerialName("WEEK") WEEK,
    @SerialName("WEEKDAY") WEEKDAY,
    @SerialName("TIME_BAND") TIME_BAND,
    @SerialName("PRODUCT") PRODUCT,
    @SerialName("BRISTOL_TYPE") BRISTOL_TYPE,
    @SerialName("COLOUR") COLOUR,
    @SerialName("SIZE") SIZE,
    @SerialName("UNKNOWN") UNKNOWN,
}

/**
 * Filter clause for aggregate query. Handles string or numeric values transparently.
 */
@Serializable(with = QueryFilterSerializer::class)
data class QueryFilter(
    val source: AppSource,
    val field: String,
    val operator: String,
    val value: String,
)

object QueryFilterSerializer : KSerializer<QueryFilter> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("QueryFilter", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: QueryFilter) {
        val jsonOutput = encoder as? kotlinx.serialization.json.JsonEncoder
            ?: throw IllegalStateException("This serializer can only be used with Json")
        val bristolInt = if (value.field == "poop.bristol_type") value.value.toIntOrNull() else null
        val map = buildMap<String, JsonElement> {
            put("source", JsonPrimitive(value.source.name))
            put("field", JsonPrimitive(value.field))
            put("operator", JsonPrimitive(value.operator))
            if (bristolInt != null) {
                put("value", JsonPrimitive(bristolInt))
            } else {
                put("value", JsonPrimitive(value.value))
            }
        }
        jsonOutput.encodeJsonElement(JsonObject(map))
    }

    override fun deserialize(decoder: Decoder): QueryFilter {
        val jsonInput = decoder as? JsonDecoder
            ?: throw IllegalStateException("This serializer can only be used with Json")
        val jsonObject = jsonInput.decodeJsonElement() as JsonObject
        val sourceStr = (jsonObject["source"] as? JsonPrimitive)?.content ?: "UNKNOWN"
        val fieldStr = (jsonObject["field"] as? JsonPrimitive)?.content ?: ""
        val operatorStr = (jsonObject["operator"] as? JsonPrimitive)?.content ?: ""
        val valElement = jsonObject["value"] as? JsonPrimitive
        val valStr = valElement?.intOrNull?.toString() ?: valElement?.content ?: ""

        val source = try {
            AppSource.valueOf(sourceStr)
        } catch (_: IllegalArgumentException) {
            AppSource.UNKNOWN
        }
        return QueryFilter(
            source = source,
            field = fieldStr,
            operator = operatorStr,
            value = valStr,
        )
    }
}

/** Result mode requested for an aggregate query. */
@Serializable
enum class QueryResultMode {
    @SerialName("FACTS") FACTS,
    @SerialName("COUNT_AND_NAVIGATION") COUNT_AND_NAVIGATION,
    @SerialName("EXPLANATION") EXPLANATION,
    @SerialName("UNKNOWN") UNKNOWN,
}

/** Bounded typed query sent to client fact providers. */
@Serializable
data class AggregateQuery(
    val grammarVersion: Int = AssistantContractV2.GRAMMAR_VERSION,
    val sources: List<AppSource>,
    val metrics: List<MetricId>,
    val period: QueryPeriod,
    val comparison: QueryComparison = QueryComparison.NONE,
    val groupings: List<QueryGrouping> = emptyList(),
    val filters: List<QueryFilter> = emptyList(),
    val resultMode: QueryResultMode = QueryResultMode.FACTS,
)

/** Structured decisions from router parsing. */
@Serializable
@JsonClassDiscriminator("decision")
sealed interface RouterDecision {
    @Serializable
    @SerialName("QUERY")
    data class Query(val query: AggregateQuery) : RouterDecision

    @Serializable
    @SerialName("CLARIFY")
    data class Clarify(val clarificationId: ClarificationId) : RouterDecision

    @Serializable
    @SerialName("UNSUPPORTED")
    data class Unsupported(val limitationId: LimitationId) : RouterDecision
}

@Serializable
enum class ClarificationId {
    @SerialName("CHOOSE_SOURCE") CHOOSE_SOURCE,
    @SerialName("CHOOSE_METRIC") CHOOSE_METRIC,
    @SerialName("CHOOSE_DATE") CHOOSE_DATE,
    @SerialName("CHOOSE_PRODUCT") CHOOSE_PRODUCT,
    @SerialName("SPLIT_REQUEST") SPLIT_REQUEST,
    @SerialName("UNKNOWN_SOURCE") UNKNOWN_SOURCE,
    @SerialName("UNKNOWN_METRIC") UNKNOWN_METRIC,
    @SerialName("UNKNOWN_PRODUCT") UNKNOWN_PRODUCT,
    @SerialName("UNKNOWN") UNKNOWN,
}

@Serializable
enum class LimitationId {
    @SerialName("READ_ONLY") READ_ONLY,
    @SerialName("MEDICAL_OR_CAUSAL") MEDICAL_OR_CAUSAL,
    @SerialName("RAW_DATA_UNAVAILABLE") RAW_DATA_UNAVAILABLE,
    @SerialName("PROJECTION_UNAVAILABLE") PROJECTION_UNAVAILABLE,
    @SerialName("OUT_OF_GRAMMAR") OUT_OF_GRAMMAR,
    @SerialName("UNSAFE_OR_MALFORMED") UNSAFE_OR_MALFORMED,
    @SerialName("UNKNOWN") UNKNOWN,
}

/** Grounded fact evidence produced by client app providers. */
@Serializable
data class FactEvidence(
    val factId: String,
    val sourceApp: AppSource,
    val sourceContractVersion: Int = AssistantContractV2.VERSION,
    val metricId: String,
    val displayLabel: String,
    val displayValue: String,
    val unit: String? = null,
    val denominator: Long? = null,
    val coveragePercent: Int? = null,
    val qualifier: String? = null,
    val tieState: String? = null,
    val periodStart: String? = null,
    val periodEnd: String? = null,
    val timezone: String,
    val asOfTime: Long,
    val sourceRevision: String,
    val completenessWarning: String? = null,
    val navigationTarget: String? = null,
)

/** Response from client fact providers. */
@Serializable
data class ProviderFactsResult(
    val contractVersion: Int = AssistantContractV2.VERSION,
    val sourceApp: AppSource,
    val facts: List<FactEvidence> = emptyList(),
    val revision: String,
    val asOfTime: Long,
    val timezone: String,
    val warnings: List<String> = emptyList(),
    val navigationTarget: String? = null,
)

/** Capabilities advertised by client fact providers. */
@Serializable
data class ProviderCapabilities(
    val providerVersion: Int = AssistantContractV2.VERSION,
    val sourceApp: AppSource,
    val supportedMetrics: List<String> = emptyList(),
    val supportedGroupings: List<String> = emptyList(),
    val supportedFilters: List<String> = emptyList(),
)

/** Response length policy for assistant turns. */
@Serializable
enum class ResponseLengthPolicy {
    @SerialName("CONCISE") CONCISE,
    @SerialName("NORMAL") NORMAL,
    @SerialName("DETAILED") DETAILED,
    @SerialName("UNKNOWN") UNKNOWN,
}

/** Request initiated by a client app for an assistant turn. */
@Serializable
data class AssistantTurnRequest(
    val contractVersion: Int = AssistantContractV2.VERSION,
    val requestId: String,
    val threadId: String,
    val initiatingClient: String,
    val question: String,
    val defaultSource: AppSource,
    val maxSourcesAllowed: Int = 1,
    val allowCrossApp: Boolean = false,
    val responseLengthPolicy: ResponseLengthPolicy = ResponseLengthPolicy.NORMAL,
    val deadlineMillis: Long = 90_000L,
)

/** Host assistant capabilities document. */
@Serializable
data class AssistantCapabilities(
    val protocolVersion: Int = AssistantContractV2.VERSION,
    val grammarVersion: Int = AssistantContractV2.GRAMMAR_VERSION,
    val supportedModelRoles: List<String> = listOf("ROUTER", "WRITER"),
    val roleStates: Map<String, String> = emptyMap(),
    val supportsStructuredOutput: Boolean = true,
    val supportsStreamingDrafts: Boolean = true,
    val supportsHistory: Boolean = true,
    val providerVersions: Map<String, Int> = emptyMap(),
)

/** Intermediate assistant event types. */
@Serializable
enum class AssistantEventType {
    @SerialName("ROUTING") ROUTING,
    @SerialName("CLARIFICATION_REQUIRED") CLARIFICATION_REQUIRED,
    @SerialName("QUEUED") QUEUED,
    @SerialName("MODEL_LOADING") MODEL_LOADING,
    @SerialName("DRAFT") DRAFT,
    @SerialName("PROVIDER_STATUS") PROVIDER_STATUS,
    @SerialName("COMPLETE") COMPLETE,
    @SerialName("FAILURE") FAILURE,
    @SerialName("UNKNOWN") UNKNOWN,
}

/** Streaming or intermediate turn event. */
@Serializable
data class AssistantEvent(
    val eventType: AssistantEventType,
    val draftText: String? = null,
    val progressPercent: Int = -1,
    val stage: String? = null,
    val detail: String? = null,
)

/** Terminal status of an assistant turn. */
@Serializable
enum class AssistantTerminalStatus {
    @SerialName("VALIDATED") VALIDATED,
    @SerialName("FAILED_VALIDATION") FAILED_VALIDATION,
    @SerialName("UNSUPPORTED") UNSUPPORTED,
    @SerialName("PARTIAL_SOURCE") PARTIAL_SOURCE,
    @SerialName("TIMEOUT") TIMEOUT,
    @SerialName("CANCELLED") CANCELLED,
    @SerialName("ERROR") ERROR,
    @SerialName("UNKNOWN") UNKNOWN,
}

/** Structured sentence-to-evidence citation. */
@Serializable
data class SentenceCitation(
    val sentence: String,
    val citedFactIds: List<String> = emptyList(),
    val citedLimitationIds: List<String> = emptyList(),
)

/** Authoritative terminal outcome of an assistant turn. */
@Serializable
data class AssistantTerminalResult(
    val status: AssistantTerminalStatus,
    val finalOrEscapedText: String,
    val citations: List<SentenceCitation> = emptyList(),
    val limitations: List<String> = emptyList(),
    val validationIssues: List<String> = emptyList(),
    val historyId: Long? = null,
)

/** Query for paginated shared conversation history. */
@Serializable
data class HistoryQuery(
    val threadId: String? = null,
    val cursor: String? = null,
    val limit: Int = 20,
)

/** Summary of a conversation thread. */
@Serializable
data class HistoryThreadSummary(
    val threadId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val turnCount: Int,
)

/** Detailed record of a single persisted turn. */
@Serializable
data class HistoryTurnRecord(
    val historyId: Long,
    val threadId: String,
    val timestamp: Long,
    val question: String,
    val status: AssistantTerminalStatus,
    val resultText: String,
    val citations: List<SentenceCitation> = emptyList(),
    val citedFacts: List<FactEvidence> = emptyList(),
    val sources: List<AppSource> = emptyList(),
    val period: String? = null,
    val asOfTime: Long = 0L,
    val modelVersion: String? = null,
    val validationIssues: List<String> = emptyList(),
)

/** Bounded page of history records. */
@Serializable
data class HistoryPage(
    val threads: List<HistoryThreadSummary> = emptyList(),
    val turns: List<HistoryTurnRecord> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)
