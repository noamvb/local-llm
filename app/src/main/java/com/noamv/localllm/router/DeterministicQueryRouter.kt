package com.noamv.localllm.router

import com.noamv.localllm.contract.v2.AggregateQuery
import com.noamv.localllm.contract.v2.AppSource
import com.noamv.localllm.contract.v2.AssistantContractV2
import com.noamv.localllm.contract.v2.ClarificationId
import com.noamv.localllm.contract.v2.LimitationId
import com.noamv.localllm.contract.v2.MetricId
import com.noamv.localllm.contract.v2.QueryComparison
import com.noamv.localllm.contract.v2.QueryFilter
import com.noamv.localllm.contract.v2.QueryGrouping
import com.noamv.localllm.contract.v2.QueryPeriod
import com.noamv.localllm.contract.v2.QueryResultMode
import com.noamv.localllm.contract.v2.RouterDecision
import java.util.Locale
import java.util.regex.Pattern

/**
 * Deterministic rules-based query router.
 *
 * Implements full query grammar parsing matching router_decision.draft-1.schema.json.
 * Fast, 100% reproducible, zero memory overhead, and acts as the immediate fallback
 * or primary router for assistant turns.
 */
object DeterministicQueryRouter {

    private val WRITE_INTENT_PATTERN = Pattern.compile(
        "^\\s*(?:please\\s+)?(delete|remove|erase|clear|create|insert|save|update|modify|change|set)\\b|" +
            "\\b(delete|remove|erase|clear)\\s+(?:my|the|all|this|last|a)\\b|" +
            "\\b(?:add|create|record|log|insert|save)\\s+(?:a|an|new|another)\\b",
        Pattern.CASE_INSENSITIVE,
    )

    private val MEDICAL_INTENT_PATTERN = Pattern.compile(
        "\\b(cure|diagnose|diagnosis|disease|cancer|ibs|crohn|infection|medical advice|treat|prescribe|illness|symptom of)\\b",
        Pattern.CASE_INSENSITIVE,
    )

    private val RAW_EXPORT_PATTERN = Pattern.compile(
        "\\b(raw\\s+rows|raw\\s+json|every\\s+entry|all\\s+database\\s+rows|raw\\s+database|export\\s+all|dump\\s+(?:all|table|database|database\\s+rows))\\b",
        Pattern.CASE_INSENSITIVE,
    )

    private val PROJECTION_PATTERN = Pattern.compile(
        "\\b(predict|future spend|forecast next year|how much will i spend in 2027)\\b",
        Pattern.CASE_INSENSITIVE,
    )

    private val EXPLICIT_DATE_RANGE_PATTERN = Pattern.compile(
        "(\\d{4}-\\d{2}-\\d{2})\\s*(?:to|until|through|\\.\\.)\\s*(\\d{4}-\\d{2}-\\d{2})",
        Pattern.CASE_INSENSITIVE,
    )

    private val BRISTOL_TYPE_FILTER_PATTERN = Pattern.compile(
        "\\b(?:type|bristol)\\s*([1-7])\\b",
        Pattern.CASE_INSENSITIVE,
    )

    fun route(
        question: String,
        defaultSource: AppSource = AppSource.CANNSHEET,
        allowCrossApp: Boolean = false,
    ): RouterDecision {
        val trimmed = question.trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        // 1. Guardrail checks
        if (RAW_EXPORT_PATTERN.matcher(lower).find()) {
            return RouterDecision.Unsupported(LimitationId.RAW_DATA_UNAVAILABLE)
        }

        if (WRITE_INTENT_PATTERN.matcher(lower).find()) {
            return RouterDecision.Unsupported(LimitationId.READ_ONLY)
        }

        if (MEDICAL_INTENT_PATTERN.matcher(lower).find()) {
            return RouterDecision.Unsupported(LimitationId.MEDICAL_OR_CAUSAL)
        }

        if (PROJECTION_PATTERN.matcher(lower).find()) {
            return RouterDecision.Unsupported(LimitationId.PROJECTION_UNAVAILABLE)
        }

        // 2. Determine Sources
        val mentionsCannabis = lower.contains("cannabis") || lower.contains("weed") ||
            lower.contains("flower") || lower.contains("cart") || lower.contains("spend") ||
            lower.contains("strain") || lower.contains("thc") || lower.contains("cannsheet")
        val mentionsDigestive = lower.contains("poop") || lower.contains("bowel") ||
            lower.contains("stool") || lower.contains("bristol") || lower.contains("digestive") ||
            lower.contains("schedule")

        val sources = mutableListOf<AppSource>()
        if (mentionsCannabis && mentionsDigestive) {
            sources.add(AppSource.CANNSHEET)
            sources.add(AppSource.POOP_SCHEDULE)
        } else if (mentionsCannabis) {
            sources.add(AppSource.CANNSHEET)
            if (allowCrossApp) sources.add(AppSource.POOP_SCHEDULE)
        } else if (mentionsDigestive) {
            sources.add(AppSource.POOP_SCHEDULE)
            if (allowCrossApp) sources.add(AppSource.CANNSHEET)
        } else {
            sources.add(defaultSource)
        }

        // 3. Determine Period
        val period: QueryPeriod
        val dateMatcher = EXPLICIT_DATE_RANGE_PATTERN.matcher(lower)
        if (dateMatcher.find()) {
            period = QueryPeriod.ExplicitDates(dateMatcher.group(1)!!, dateMatcher.group(2)!!)
        } else if (lower.contains("all time") || lower.contains("ever") || lower.contains("overall")) {
            period = QueryPeriod.AllTime
        } else if (lower.contains("today") || lower.contains("yesterday") || lower.contains("24 hour")) {
            period = QueryPeriod.LastDays(1)
        } else if (lower.contains("this week") || lower.contains("last 7 days") || lower.contains("past 7 days") || lower.contains("past week")) {
            period = QueryPeriod.LastDays(7)
        } else if (lower.contains("90 days") || lower.contains("3 months") || lower.contains("quarter")) {
            period = QueryPeriod.LastDays(90)
        } else if (lower.contains("this month") || lower.contains("last 30 days") || lower.contains("past 30 days") || lower.contains("month")) {
            period = QueryPeriod.LastDays(30)
        } else {
            period = QueryPeriod.LastDays(30)
        }

        // 4. Comparison
        val comparison = if (
            lower.contains("compare") || lower.contains("compared to") ||
            lower.contains("vs prior") || lower.contains("versus") ||
            lower.contains("increase") || lower.contains("decrease") ||
            lower.contains("trend") || lower.contains("change")
        ) {
            QueryComparison.PREVIOUS_EQUAL_PERIOD
        } else {
            QueryComparison.NONE
        }

        // 5. Groupings
        val groupings = mutableListOf<QueryGrouping>()
        if (lower.contains("by day") || lower.contains("daily")) groupings.add(QueryGrouping.DAY)
        if (lower.contains("by week") || lower.contains("weekly")) groupings.add(QueryGrouping.WEEK)
        if (lower.contains("day of week") || lower.contains("weekday")) groupings.add(QueryGrouping.WEEKDAY)
        if (lower.contains("time of day") || lower.contains("time band") || lower.contains("morning vs night")) groupings.add(QueryGrouping.TIME_BAND)
        if (lower.contains("by product") || lower.contains("by strain")) groupings.add(QueryGrouping.PRODUCT)
        if (lower.contains("by bristol") || lower.contains("by type")) groupings.add(QueryGrouping.BRISTOL_TYPE)
        if (lower.contains("by colour") || lower.contains("by color")) groupings.add(QueryGrouping.COLOUR)
        if (lower.contains("by size")) groupings.add(QueryGrouping.SIZE)

        // 6. Filters
        val filters = mutableListOf<QueryFilter>()
        val bristolMatcher = BRISTOL_TYPE_FILTER_PATTERN.matcher(lower)
        if (bristolMatcher.find()) {
            val typeVal = bristolMatcher.group(1)!!
            filters.add(
                QueryFilter(
                    source = AppSource.POOP_SCHEDULE,
                    field = "poop.bristol_type",
                    operator = "EQUALS",
                    value = typeVal,
                ),
            )
        }

        // 7. Metrics
        val metrics = mutableListOf<MetricId>()
        if (sources.contains(AppSource.CANNSHEET)) {
            if (lower.contains("spend") || lower.contains("cost") || lower.contains("dollar") || lower.contains("price") || lower.contains("paid")) {
                metrics.add(MetricId.CANNSHEET_RECORDED_SPEND)
                metrics.add(MetricId.CANNSHEET_RECORDED_SPEND_COVERAGE)
                metrics.add(MetricId.CANNSHEET_PURCHASE_COUNT)
            }
            if (lower.contains("purchase") || lower.contains("bought")) {
                metrics.add(MetricId.CANNSHEET_PURCHASE_COUNT)
            }
            if (lower.contains("inventory") || lower.contains("remaining") || lower.contains("stash")) {
                metrics.add(MetricId.CANNSHEET_INVENTORY_REMAINING)
            }
            if (lower.contains("time") || lower.contains("morning") || lower.contains("evening") || lower.contains("night")) {
                metrics.add(MetricId.CANNSHEET_TIME_BAND_COUNTS)
            }
            if (lower.contains("weekday") || lower.contains("weekend") || lower.contains("day of week")) {
                metrics.add(MetricId.CANNSHEET_WEEKDAY_COUNTS)
            }
            if (lower.contains("product") || lower.contains("strain")) {
                metrics.add(MetricId.CANNSHEET_PRODUCT_LOG_COUNTS)
            }
            if (metrics.isEmpty() || lower.contains("use") || lower.contains("smoke") || lower.contains("vape") || lower.contains("consumption") || lower.contains("hit") || lower.contains("session")) {
                metrics.add(MetricId.CANNSHEET_CONSUMPTION_COUNT)
                metrics.add(MetricId.CANNSHEET_ACTIVE_DAYS)
            }
        }

        if (sources.contains(AppSource.POOP_SCHEDULE)) {
            if (lower.contains("bristol") || lower.contains("type")) {
                metrics.add(MetricId.POOP_BRISTOL_TYPE_COUNTS)
            }
            if (lower.contains("colour") || lower.contains("color")) {
                metrics.add(MetricId.POOP_COLOUR_COUNTS)
            }
            if (lower.contains("size")) {
                metrics.add(MetricId.POOP_SIZE_COUNTS)
            }
            if (lower.contains("symptom") || lower.contains("pain") || lower.contains("urgency") || lower.contains("blood")) {
                metrics.add(MetricId.POOP_SYMPTOM_COUNTS)
            }
            if (lower.contains("duration") || lower.contains("long") || lower.contains("minutes")) {
                metrics.add(MetricId.POOP_AVERAGE_DURATION)
            }
            if (lower.contains("interval") || lower.contains("between") || lower.contains("gap")) {
                metrics.add(MetricId.POOP_AVERAGE_INTERVAL)
            }
            if (lower.contains("frequency") || lower.contains("per week") || lower.contains("often")) {
                metrics.add(MetricId.POOP_FREQUENCY_PER_WEEK)
            }
            if (lower.contains("time") || lower.contains("morning") || lower.contains("evening") || lower.contains("night")) {
                metrics.add(MetricId.POOP_TIME_BAND_COUNTS)
            }
            if (metrics.isEmpty() || lower.contains("count") || lower.contains("times") || lower.contains("entry") || lower.contains("entries") || lower.contains("poop") || lower.contains("bowel")) {
                metrics.add(MetricId.POOP_ENTRY_COUNT)
                metrics.add(MetricId.POOP_ACTIVE_DAYS)
            }
        }

        // Deduplicate metrics and ensure limits
        val finalMetrics = metrics.distinct().take(8)

        // 8. Result Mode
        val resultMode = if (lower.contains("navigate") || lower.contains("show me") || lower.contains("go to")) {
            QueryResultMode.COUNT_AND_NAVIGATION
        } else if (lower.contains("explain") || lower.contains("why")) {
            QueryResultMode.EXPLANATION
        } else {
            QueryResultMode.FACTS
        }

        val aggregateQuery = AggregateQuery(
            grammarVersion = AssistantContractV2.GRAMMAR_VERSION,
            sources = sources.distinct().take(2),
            metrics = finalMetrics,
            period = period,
            comparison = comparison,
            groupings = groupings.distinct().take(3),
            filters = filters.take(4),
            resultMode = resultMode,
        )

        return RouterDecision.Query(aggregateQuery)
    }
}
