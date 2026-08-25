package com.noamv.localllm.contract.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AssistantContractV2Test {

    @Test
    fun testAssistantTurnRequestSerialization() {
        val request = AssistantTurnRequest(
            requestId = "req-123",
            threadId = "thread-456",
            initiatingClient = "com.example.cannsheet",
            question = "How much did I spend on flower this month?",
            defaultSource = AppSource.CANNSHEET,
            maxSourcesAllowed = 1,
            allowCrossApp = false,
            responseLengthPolicy = ResponseLengthPolicy.NORMAL,
            deadlineMillis = 45_000L,
        )

        val jsonString = AssistantContractV2.json.encodeToString(AssistantTurnRequest.serializer(), request)
        val decoded = AssistantContractV2.json.decodeFromString(AssistantTurnRequest.serializer(), jsonString)

        assertEquals(request, decoded)
        assertEquals("req-123", decoded.requestId)
        assertEquals(AppSource.CANNSHEET, decoded.defaultSource)
    }

    @Test
    fun testAggregateQueryLastDaysSerialization() {
        val query = AggregateQuery(
            grammarVersion = 1,
            sources = listOf(AppSource.CANNSHEET),
            metrics = listOf(MetricId.CANNSHEET_RECORDED_SPEND, MetricId.CANNSHEET_PURCHASE_COUNT),
            period = QueryPeriod.LastDays(30),
            comparison = QueryComparison.PREVIOUS_EQUAL_PERIOD,
            groupings = listOf(QueryGrouping.TIME_BAND),
            filters = listOf(
                QueryFilter(
                    source = AppSource.CANNSHEET,
                    field = "cannsheet.product_name",
                    operator = "EXACT_NAME",
                    value = "OG Kush",
                ),
            ),
            resultMode = QueryResultMode.FACTS,
        )

        val jsonString = AssistantContractV2.json.encodeToString(AggregateQuery.serializer(), query)
        val decoded = AssistantContractV2.json.decodeFromString(AggregateQuery.serializer(), jsonString)

        assertEquals(query, decoded)
        assertTrue(decoded.period is QueryPeriod.LastDays)
        assertEquals(30, (decoded.period as QueryPeriod.LastDays).days)
        assertEquals(1, decoded.filters.size)
        assertEquals("OG Kush", decoded.filters[0].value)
    }

    @Test
    fun testQueryFilterLeadingZeroStringRoundtrip() {
        val filter = QueryFilter(
            source = AppSource.CANNSHEET,
            field = "cannsheet.product_code",
            operator = "EQUALS",
            value = "007",
        )

        val jsonString = AssistantContractV2.json.encodeToString(QueryFilter.serializer(), filter)
        val decoded = AssistantContractV2.json.decodeFromString(QueryFilter.serializer(), jsonString)

        assertEquals("007", decoded.value)
        assertEquals(filter, decoded)
    }

    @Test
    fun testAggregateQueryExplicitDatesAndBristolFilter() {
        val query = AggregateQuery(
            grammarVersion = 1,
            sources = listOf(AppSource.POOP_SCHEDULE),
            metrics = listOf(MetricId.POOP_ENTRY_COUNT, MetricId.POOP_BRISTOL_TYPE_COUNTS),
            period = QueryPeriod.ExplicitDates(start = "2026-08-01", end = "2026-08-15"),
            comparison = QueryComparison.NONE,
            groupings = listOf(QueryGrouping.BRISTOL_TYPE),
            filters = listOf(
                QueryFilter(
                    source = AppSource.POOP_SCHEDULE,
                    field = "poop.bristol_type",
                    operator = "EQUALS",
                    value = "4",
                ),
            ),
            resultMode = QueryResultMode.COUNT_AND_NAVIGATION,
        )

        val jsonString = AssistantContractV2.json.encodeToString(AggregateQuery.serializer(), query)
        val decoded = AssistantContractV2.json.decodeFromString(AggregateQuery.serializer(), jsonString)

        assertEquals(query, decoded)
        assertTrue(decoded.period is QueryPeriod.ExplicitDates)
        val period = decoded.period as QueryPeriod.ExplicitDates
        assertEquals("2026-08-01", period.start)
        assertEquals("2026-08-15", period.end)
        assertEquals(QueryResultMode.COUNT_AND_NAVIGATION, decoded.resultMode)
    }

    @Test
    fun testRouterDecisionPolymorphicSerialization() {
        val queryDecision = RouterDecision.Query(
            query = AggregateQuery(
                sources = listOf(AppSource.CANNSHEET, AppSource.POOP_SCHEDULE),
                metrics = listOf(MetricId.CANNSHEET_ACTIVE_DAYS, MetricId.POOP_ACTIVE_DAYS),
                period = QueryPeriod.AllTime,
                resultMode = QueryResultMode.FACTS,
            ),
        )

        val clarifyDecision = RouterDecision.Clarify(
            clarificationId = ClarificationId.CHOOSE_SOURCE,
        )

        val unsupportedDecision = RouterDecision.Unsupported(
            limitationId = LimitationId.MEDICAL_OR_CAUSAL,
        )

        val queryJson = AssistantContractV2.json.encodeToString(RouterDecision.serializer(), queryDecision)
        val clarifyJson = AssistantContractV2.json.encodeToString(RouterDecision.serializer(), clarifyDecision)
        val unsupportedJson = AssistantContractV2.json.encodeToString(RouterDecision.serializer(), unsupportedDecision)

        val decodedQuery = AssistantContractV2.json.decodeFromString(RouterDecision.serializer(), queryJson)
        val decodedClarify = AssistantContractV2.json.decodeFromString(RouterDecision.serializer(), clarifyJson)
        val decodedUnsupported = AssistantContractV2.json.decodeFromString(RouterDecision.serializer(), unsupportedJson)

        assertEquals(queryDecision, decodedQuery)
        assertEquals(clarifyDecision, decodedClarify)
        assertEquals(unsupportedDecision, decodedUnsupported)
    }

    @Test
    fun testFactEvidenceAndProviderResultSerialization() {
        val fact = FactEvidence(
            factId = "fact-001",
            sourceApp = AppSource.CANNSHEET,
            sourceContractVersion = 2,
            metricId = "cannsheet.recorded_spend",
            displayLabel = "Total Spend",
            displayValue = "$120.50",
            unit = "USD",
            denominator = 5L,
            coveragePercent = 100,
            qualifier = "All known-cost purchases",
            tieState = null,
            periodStart = "2026-08-01",
            periodEnd = "2026-08-23",
            timezone = "America/New_York",
            asOfTime = 1756000000000L,
            sourceRevision = "rev-99",
            completenessWarning = null,
            navigationTarget = "cannsheet://insights/spend",
        )

        val providerResult = ProviderFactsResult(
            contractVersion = 2,
            sourceApp = AppSource.CANNSHEET,
            facts = listOf(fact),
            revision = "rev-99",
            asOfTime = 1756000000000L,
            timezone = "America/New_York",
            warnings = emptyList(),
            navigationTarget = "cannsheet://insights",
        )

        val jsonString = AssistantContractV2.json.encodeToString(ProviderFactsResult.serializer(), providerResult)
        val decoded = AssistantContractV2.json.decodeFromString(ProviderFactsResult.serializer(), jsonString)

        assertEquals(providerResult, decoded)
        assertEquals(1, decoded.facts.size)
        assertEquals("$120.50", decoded.facts[0].displayValue)
    }

    @Test
    fun testTerminalResultWithCitationsSerialization() {
        val citation = SentenceCitation(
            sentence = "You logged 12 purchases totaling $120.50 in the last 30 days.",
            citedFactIds = listOf("fact-001", "fact-002"),
            citedLimitationIds = emptyList(),
        )

        val terminalResult = AssistantTerminalResult(
            status = AssistantTerminalStatus.VALIDATED,
            finalOrEscapedText = "You logged 12 purchases totaling $120.50 in the last 30 days.",
            citations = listOf(citation),
            limitations = emptyList(),
            validationIssues = emptyList(),
            historyId = 42L,
        )

        val jsonString = AssistantContractV2.json.encodeToString(AssistantTerminalResult.serializer(), terminalResult)
        val decoded = AssistantContractV2.json.decodeFromString(AssistantTerminalResult.serializer(), jsonString)

        assertEquals(terminalResult, decoded)
        assertEquals(AssistantTerminalStatus.VALIDATED, decoded.status)
        assertEquals(1, decoded.citations.size)
        assertEquals(listOf("fact-001", "fact-002"), decoded.citations[0].citedFactIds)
    }

    @Test
    fun testHistoryPageSerialization() {
        val summary = HistoryThreadSummary(
            threadId = "thread-1",
            title = "Monthly Cannabis Spend",
            createdAt = 1756000000000L,
            updatedAt = 1756000005000L,
            turnCount = 2,
        )

        val turn = HistoryTurnRecord(
            historyId = 101L,
            threadId = "thread-1",
            timestamp = 1756000005000L,
            question = "How much was spent?",
            status = AssistantTerminalStatus.VALIDATED,
            resultText = "Recorded spend is $120.50.",
            citations = listOf(
                SentenceCitation(
                    sentence = "Recorded spend is $120.50.",
                    citedFactIds = listOf("fact-001"),
                ),
            ),
            citedFacts = emptyList(),
            sources = listOf(AppSource.CANNSHEET),
            period = "2026-08-01..2026-08-23",
            asOfTime = 1756000000000L,
            modelVersion = "gemma-4-E2B-it",
            validationIssues = emptyList(),
        )

        val historyPage = HistoryPage(
            threads = listOf(summary),
            turns = listOf(turn),
            nextCursor = "cursor-next",
            hasMore = false,
        )

        val jsonString = AssistantContractV2.json.encodeToString(HistoryPage.serializer(), historyPage)
        val decoded = AssistantContractV2.json.decodeFromString(HistoryPage.serializer(), jsonString)

        assertEquals(historyPage, decoded)
        assertEquals(1, decoded.threads.size)
        assertEquals(1, decoded.turns.size)
    }

    @Test
    fun testForwardCompatibilityUnknownFieldsIgnored() {
        val extraJson = """
            {
                "contractVersion": 2,
                "requestId": "req-999",
                "threadId": "thread-888",
                "initiatingClient": "com.example.cannsheet",
                "question": "Show my stats",
                "defaultSource": "CANNSHEET",
                "maxSourcesAllowed": 2,
                "allowCrossApp": true,
                "responseLengthPolicy": "NORMAL",
                "deadlineMillis": 60000,
                "futureFieldUnknown": "some-future-value",
                "anotherUnknownObject": {"nested": true}
            }
        """.trimIndent()

        val decoded = AssistantContractV2.json.decodeFromString(AssistantTurnRequest.serializer(), extraJson)
        assertEquals("req-999", decoded.requestId)
        assertTrue(decoded.allowCrossApp)
    }

    @Test
    fun testRouterCorpusSeedJsonLinesConformance() {
        val corpusFile = File("research/functiongemma_router/corpus/router_seed.v0.jsonl")
        if (!corpusFile.exists()) return

        corpusFile.forEachLine { line ->
            if (line.isNotBlank()) {
                val element = AssistantContractV2.json.parseToJsonElement(line) as? kotlinx.serialization.json.JsonObject
                assertNotNull(element)
                val decisionElement = element?.get("target") ?: element
                assertNotNull(decisionElement)
                // Decode router decision from JSON element
                val decoded = AssistantContractV2.json.decodeFromJsonElement(RouterDecision.serializer(), decisionElement!!)
                assertNotNull(decoded)
            }
        }
    }
}
