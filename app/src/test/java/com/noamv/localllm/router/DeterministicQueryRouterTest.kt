package com.noamv.localllm.router

import com.noamv.localllm.contract.v2.AppSource
import com.noamv.localllm.contract.v2.LimitationId
import com.noamv.localllm.contract.v2.MetricId
import com.noamv.localllm.contract.v2.QueryComparison
import com.noamv.localllm.contract.v2.QueryGrouping
import com.noamv.localllm.contract.v2.QueryPeriod
import com.noamv.localllm.contract.v2.QueryResultMode
import com.noamv.localllm.contract.v2.RouterDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicQueryRouterTest {

    @Test
    fun testSpendQueryRoutesToCannsheetSpendMetrics() {
        val decision = DeterministicQueryRouter.route(
            question = "How much did I spend on cannabis this month?",
            defaultSource = AppSource.CANNSHEET,
        )

        assertTrue(decision is RouterDecision.Query)
        val query = (decision as RouterDecision.Query).query
        assertEquals(listOf(AppSource.CANNSHEET), query.sources)
        assertTrue(query.metrics.contains(MetricId.CANNSHEET_RECORDED_SPEND))
        assertTrue(query.period is QueryPeriod.LastDays)
        assertEquals(30, (query.period as QueryPeriod.LastDays).days)
        assertEquals(QueryComparison.NONE, query.comparison)
    }

    @Test
    fun testDigestiveBristolTypeAndFilterParsing() {
        val decision = DeterministicQueryRouter.route(
            question = "How many type 4 bowel movements did I log this week by day?",
            defaultSource = AppSource.POOP_SCHEDULE,
        )

        assertTrue(decision is RouterDecision.Query)
        val query = (decision as RouterDecision.Query).query
        assertEquals(listOf(AppSource.POOP_SCHEDULE), query.sources)
        assertTrue(query.metrics.contains(MetricId.POOP_BRISTOL_TYPE_COUNTS) || query.metrics.contains(MetricId.POOP_ENTRY_COUNT))
        assertTrue(query.period is QueryPeriod.LastDays)
        assertEquals(7, (query.period as QueryPeriod.LastDays).days)
        assertTrue(query.groupings.contains(QueryGrouping.DAY))
        assertEquals(1, query.filters.size)
        assertEquals("poop.bristol_type", query.filters[0].field)
        assertEquals("4", query.filters[0].value)
    }

    @Test
    fun testExplicitDateRangeAndComparison() {
        val decision = DeterministicQueryRouter.route(
            question = "Compare my weed consumption from 2026-08-01 to 2026-08-15 versus the prior period",
            defaultSource = AppSource.CANNSHEET,
        )

        assertTrue(decision is RouterDecision.Query)
        val query = (decision as RouterDecision.Query).query
        assertTrue(query.period is QueryPeriod.ExplicitDates)
        val period = query.period as QueryPeriod.ExplicitDates
        assertEquals("2026-08-01", period.start)
        assertEquals("2026-08-15", period.end)
        assertEquals(QueryComparison.PREVIOUS_EQUAL_PERIOD, query.comparison)
    }

    @Test
    fun testCrossAppPromptRoutesToBothSources() {
        val decision = DeterministicQueryRouter.route(
            question = "Compare my cannabis consumption with my poop schedule frequency",
            defaultSource = AppSource.CANNSHEET,
            allowCrossApp = true,
        )

        assertTrue(decision is RouterDecision.Query)
        val query = (decision as RouterDecision.Query).query
        assertTrue(query.sources.contains(AppSource.CANNSHEET))
        assertTrue(query.sources.contains(AppSource.POOP_SCHEDULE))
    }

    @Test
    fun testWriteActionIntentBlockedAsReadOnly() {
        val deleteDecision = DeterministicQueryRouter.route(
            question = "Delete my last cannabis log entry",
        )
        assertTrue(deleteDecision is RouterDecision.Unsupported)
        assertEquals(LimitationId.READ_ONLY, (deleteDecision as RouterDecision.Unsupported).limitationId)

        val createDecision = DeterministicQueryRouter.route(
            question = "Add a new purchase record for $50",
        )
        assertTrue(createDecision is RouterDecision.Unsupported)
        assertEquals(LimitationId.READ_ONLY, (createDecision as RouterDecision.Unsupported).limitationId)
    }

    @Test
    fun testMedicalDiagnosisIntentBlockedAsMedical() {
        val medicalDecision = DeterministicQueryRouter.route(
            question = "Do my frequent loose stools indicate IBS or infection?",
        )
        assertTrue(medicalDecision is RouterDecision.Unsupported)
        assertEquals(LimitationId.MEDICAL_OR_CAUSAL, (medicalDecision as RouterDecision.Unsupported).limitationId)
    }

    @Test
    fun testCrossAppWithoutConsentReturnsClarify() {
        val decision = DeterministicQueryRouter.route(
            question = "Compare my cannabis consumption with my poop schedule frequency",
            defaultSource = AppSource.CANNSHEET,
            allowCrossApp = false,
        )

        assertTrue(decision is RouterDecision.Clarify)
        assertEquals(com.noamv.localllm.contract.v2.ClarificationId.CHOOSE_SOURCE, (decision as RouterDecision.Clarify).clarificationId)
    }

    @Test
    fun testSpendScheduleDoesNotPullBothSources() {
        val decision = DeterministicQueryRouter.route(
            question = "what's my spend schedule",
            defaultSource = AppSource.CANNSHEET,
            allowCrossApp = false,
        )

        // Must not return two sources
        if (decision is RouterDecision.Query) {
            assertEquals(1, decision.query.sources.size)
        } else {
            assertTrue(decision is RouterDecision.Clarify)
        }
    }

    @Test
    fun testMaxSourcesAllowedIsEnforced() {
        val decision = DeterministicQueryRouter.route(
            question = "Compare my cannabis and bowel habits",
            defaultSource = AppSource.CANNSHEET,
            allowCrossApp = true,
            maxSourcesAllowed = 1,
        )

        assertTrue(decision is RouterDecision.Query)
        val query = (decision as RouterDecision.Query).query
        assertEquals(1, query.sources.size)
    }
}
