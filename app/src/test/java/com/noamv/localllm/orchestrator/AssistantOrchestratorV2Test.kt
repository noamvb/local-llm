package com.noamv.localllm.orchestrator

import com.noamv.localllm.contract.EngineState
import com.noamv.localllm.contract.EngineStatus
import com.noamv.localllm.contract.InsightRequest
import com.noamv.localllm.contract.v2.*
import com.noamv.localllm.engine.EngineTimings
import com.noamv.localllm.engine.LlmEngine
import com.noamv.localllm.engine.ModelResidencyCoordinator
import com.noamv.localllm.history.AssistantHistoryRepository
import com.noamv.localllm.history.FakeAssistantHistoryDao
import com.noamv.localllm.privacy.AssistantAccessPolicy
import com.noamv.localllm.privacy.FakeDataStore
import com.noamv.localllm.transfer.ModelRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AssistantOrchestratorV2Test {

    private lateinit var historyRepository: AssistantHistoryRepository
    private lateinit var accessPolicy: AssistantAccessPolicy
    private lateinit var residencyCoordinator: ModelResidencyCoordinator
    private lateinit var fakeEngine: FakeOrchestratorEngine

    @Before
    fun setUp() {
        historyRepository = AssistantHistoryRepository(FakeAssistantHistoryDao())
        accessPolicy = AssistantAccessPolicy(FakeDataStore())
        residencyCoordinator = ModelResidencyCoordinator { 4_000_000_000L } // 4 GB RAM available
        fakeEngine = FakeOrchestratorEngine("You spent $120.50 across 12 purchases this month.")
    }

    @Test
    fun testAccessPolicyBlockedRejectsTurn() = runTest {
        accessPolicy.setMasterEnabled(false)

        val orchestrator = AssistantOrchestratorV2(
            engine = fakeEngine,
            historyRepository = historyRepository,
            accessPolicy = accessPolicy,
            residencyCoordinator = residencyCoordinator,
            factProviderQuery = { _, _ -> ProviderFactsResult(sourceApp = AppSource.CANNSHEET, revision = "r1", asOfTime = 1000L, timezone = "UTC") },
        )

        val request = AssistantTurnRequest(
            requestId = "req-blocked",
            threadId = "thread-1",
            initiatingClient = "com.example.cannsheet",
            question = "How much did I spend?",
            defaultSource = AppSource.CANNSHEET,
        )

        val events = mutableListOf<AssistantEvent>()
        val result = orchestrator.executeTurn(request) { events.add(it) }

        assertEquals(AssistantTerminalStatus.ERROR, result.status)
        assertTrue(result.validationIssues.any { it.contains("privacy") || it.contains("policy") })
        assertTrue(events.any { it.eventType == AssistantEventType.FAILURE })
    }

    @Test
    fun testUnsupportedWriteActionReturnsLimitationAndPersists() = runTest {
        val orchestrator = AssistantOrchestratorV2(
            engine = fakeEngine,
            historyRepository = historyRepository,
            accessPolicy = accessPolicy,
            residencyCoordinator = residencyCoordinator,
            factProviderQuery = { _, _ -> ProviderFactsResult(sourceApp = AppSource.CANNSHEET, revision = "r1", asOfTime = 1000L, timezone = "UTC") },
        )

        val request = AssistantTurnRequest(
            requestId = "req-write",
            threadId = "thread-write",
            initiatingClient = "com.example.cannsheet",
            question = "Delete all my cannabis purchases",
            defaultSource = AppSource.CANNSHEET,
        )

        val events = mutableListOf<AssistantEvent>()
        val result = orchestrator.executeTurn(request) { events.add(it) }

        assertEquals(AssistantTerminalStatus.UNSUPPORTED, result.status)
        assertTrue(result.limitations.contains("READ_ONLY"))
        assertNotNull(result.historyId)
    }

    @Test
    fun testSuccessfulGroundedTurnFlow() = runTest {
        val facts = listOf(
            FactEvidence(
                factId = "f1",
                sourceApp = AppSource.CANNSHEET,
                sourceContractVersion = 2,
                metricId = "cannsheet.recorded_spend",
                displayLabel = "Spend",
                displayValue = "$120.50",
                timezone = "UTC",
                asOfTime = 2000L,
                sourceRevision = "rev-1",
            ),
            FactEvidence(
                factId = "f2",
                sourceApp = AppSource.CANNSHEET,
                sourceContractVersion = 2,
                metricId = "cannsheet.purchase_count",
                displayLabel = "Purchases",
                displayValue = "12",
                timezone = "UTC",
                asOfTime = 2000L,
                sourceRevision = "rev-1",
            ),
        )

        val orchestrator = AssistantOrchestratorV2(
            engine = fakeEngine,
            historyRepository = historyRepository,
            accessPolicy = accessPolicy,
            residencyCoordinator = residencyCoordinator,
            factProviderQuery = { _, _ ->
                ProviderFactsResult(
                    sourceApp = AppSource.CANNSHEET,
                    facts = facts,
                    revision = "rev-1",
                    asOfTime = 2000L,
                    timezone = "UTC",
                )
            },
        )

        val request = AssistantTurnRequest(
            requestId = "req-valid",
            threadId = "thread-valid",
            initiatingClient = "com.example.cannsheet",
            question = "How much did I spend this month?",
            defaultSource = AppSource.CANNSHEET,
        )

        val events = mutableListOf<AssistantEvent>()
        val result = orchestrator.executeTurn(request) { events.add(it) }

        assertEquals(AssistantTerminalStatus.VALIDATED, result.status)
        assertEquals("You spent $120.50 across 12 purchases this month.", result.finalOrEscapedText)
        assertNotNull(result.historyId)
        assertTrue(events.any { it.eventType == AssistantEventType.ROUTING })
        assertTrue(events.any { it.eventType == AssistantEventType.DRAFT })
        assertTrue(events.any { it.eventType == AssistantEventType.COMPLETE })
    }

    @Test
    fun testCrossAppDualSourceTurnFlow() = runTest {
        val crossEngine = FakeOrchestratorEngine("You spent $120.50 on cannabis and logged 14 bowel movements.")
        val cannsheetFacts = listOf(
            FactEvidence(
                factId = "f_can_1",
                sourceApp = AppSource.CANNSHEET,
                sourceContractVersion = 2,
                metricId = MetricId.CANNSHEET_RECORDED_SPEND.wireName,
                displayLabel = "Spend",
                displayValue = "$120.50",
                timezone = "UTC",
                asOfTime = 2000L,
                sourceRevision = "rev-can-1",
            ),
        )
        val poopFacts = listOf(
            FactEvidence(
                factId = "f_poop_1",
                sourceApp = AppSource.POOP_SCHEDULE,
                sourceContractVersion = 2,
                metricId = MetricId.POOP_ENTRY_COUNT.wireName,
                displayLabel = "Entries",
                displayValue = "14",
                timezone = "UTC",
                asOfTime = 2000L,
                sourceRevision = "rev-poop-1",
            ),
        )

        val orchestrator = AssistantOrchestratorV2(
            engine = crossEngine,
            historyRepository = historyRepository,
            accessPolicy = accessPolicy,
            residencyCoordinator = residencyCoordinator,
            factProviderQuery = { app, _ ->
                when (app) {
                    AppSource.CANNSHEET -> ProviderFactsResult(sourceApp = AppSource.CANNSHEET, facts = cannsheetFacts, revision = "rev-1", asOfTime = 2000L, timezone = "UTC")
                    AppSource.POOP_SCHEDULE -> ProviderFactsResult(sourceApp = AppSource.POOP_SCHEDULE, facts = poopFacts, revision = "rev-1", asOfTime = 2000L, timezone = "UTC")
                    else -> ProviderFactsResult(sourceApp = AppSource.UNKNOWN, revision = "rev-0", asOfTime = 0L, timezone = "UTC")
                }
            },
        )

        val request = AssistantTurnRequest(
            requestId = "req-cross",
            threadId = "thread-cross",
            initiatingClient = "com.example.cannsheet",
            question = "Summarize my spend and bowel movements this month",
            defaultSource = AppSource.CANNSHEET,
            maxSourcesAllowed = 2,
            allowCrossApp = true,
        )

        val events = mutableListOf<AssistantEvent>()
        val result = orchestrator.executeTurn(request) { events.add(it) }

        assertEquals(AssistantTerminalStatus.VALIDATED, result.status)
        assertNotNull(result.historyId)
        assertTrue(result.citations.isNotEmpty())
    }

    @Test
    fun testEmptyFactsReturnsPartialSourceStatus() = runTest {
        val orchestrator = AssistantOrchestratorV2(
            engine = fakeEngine,
            historyRepository = historyRepository,
            accessPolicy = accessPolicy,
            residencyCoordinator = residencyCoordinator,
            factProviderQuery = { app, _ ->
                ProviderFactsResult(
                    sourceApp = app,
                    facts = emptyList(),
                    revision = "rev-1",
                    asOfTime = 2000L,
                    timezone = "UTC",
                )
            },
        )

        val request = AssistantTurnRequest(
            requestId = "req-empty",
            threadId = "thread-empty",
            initiatingClient = "com.example.cannsheet",
            question = "How much did I spend this month?",
            defaultSource = AppSource.CANNSHEET,
        )

        val events = mutableListOf<AssistantEvent>()
        val result = orchestrator.executeTurn(request) { events.add(it) }

        assertEquals(AssistantTerminalStatus.PARTIAL_SOURCE, result.status)
        assertNotNull(result.historyId)
    }

    @Test
    fun testFailedValidationPersistsFailedOutputAndIssues() = runTest {
        val badEngine = FakeOrchestratorEngine("You spent $99999.00 across 500 purchases.")
        val facts = listOf(
            FactEvidence(
                factId = "f1",
                sourceApp = AppSource.CANNSHEET,
                sourceContractVersion = 2,
                metricId = "cannsheet.recorded_spend",
                displayLabel = "Spend",
                displayValue = "$120.50",
                timezone = "UTC",
                asOfTime = 2000L,
                sourceRevision = "rev-1",
            ),
        )

        val orchestrator = AssistantOrchestratorV2(
            engine = badEngine,
            historyRepository = historyRepository,
            accessPolicy = accessPolicy,
            residencyCoordinator = residencyCoordinator,
            factProviderQuery = { _, _ ->
                ProviderFactsResult(
                    sourceApp = AppSource.CANNSHEET,
                    facts = facts,
                    revision = "rev-1",
                    asOfTime = 2000L,
                    timezone = "UTC",
                )
            },
        )

        val request = AssistantTurnRequest(
            requestId = "req-bad",
            threadId = "thread-bad",
            initiatingClient = "com.example.cannsheet",
            question = "How much did I spend?",
            defaultSource = AppSource.CANNSHEET,
        )

        val events = mutableListOf<AssistantEvent>()
        val result = orchestrator.executeTurn(request) { events.add(it) }

        assertEquals(AssistantTerminalStatus.FAILED_VALIDATION, result.status)
        assertTrue(result.finalOrEscapedText.startsWith("[UNVALIDATED GENERATION]:"))
        assertTrue(result.validationIssues.isNotEmpty())
        assertNotNull(result.historyId)
    }

    @Test
    fun testBuildWriterPromptIncludesPriorTurnsContext() {
        val priorTurns = listOf(
            HistoryTurnRecord(
                historyId = 1L,
                threadId = "t1",
                timestamp = 1000L,
                question = "How many times did I smoke?",
                status = AssistantTerminalStatus.VALIDATED,
                resultText = "You had 10 sessions.",
                citations = emptyList(),
                citedFacts = emptyList(),
                sources = listOf(AppSource.CANNSHEET),
                period = null,
                asOfTime = 1000L,
                modelVersion = null,
            ),
        )
        val prompt = AssistantOrchestratorV2.buildWriterPrompt(
            question = "And what was my spend?",
            facts = listOf(
                FactEvidence(
                    factId = "f2",
                    sourceApp = AppSource.CANNSHEET,
                    sourceContractVersion = 2,
                    metricId = "cannsheet.spend",
                    displayLabel = "Spend",
                    displayValue = "$50",
                    timezone = "UTC",
                    asOfTime = 1000L,
                    sourceRevision = "rev-1",
                ),
            ),
            priorTurns = priorTurns,
        )

        assertTrue(prompt.contains("Previous Conversation Context"))
        assertTrue(prompt.contains("How many times did I smoke?"))
        assertTrue(prompt.contains("You had 10 sessions."))
        assertTrue(prompt.contains("Current Question: And what was my spend?"))
    }

    @Test
    fun testResidencyUnderSimulatedMemoryPressure() = runTest {
        val lowMemCoordinator = ModelResidencyCoordinator { 1_500_000_000L } // 1.5 GB < 2.5 GB threshold
        assertFalse(lowMemCoordinator.canDualReside())
        assertTrue(lowMemCoordinator.shouldUnloadOtherRoles(ModelRole.WRITER, setOf(ModelRole.ROUTER)))

        val highMemCoordinator = ModelResidencyCoordinator { 4_000_000_000L } // 4 GB >= 2.5 GB threshold
        assertTrue(highMemCoordinator.canDualReside())
        assertFalse(highMemCoordinator.shouldUnloadOtherRoles(ModelRole.WRITER, setOf(ModelRole.ROUTER)))
    }
}

class FakeOrchestratorEngine(
    private val textToGenerate: String,
) : LlmEngine {
    override val status = MutableStateFlow(
        EngineStatus(state = EngineState.READY, modelDownloaded = true),
    )
    override val timings = MutableStateFlow(EngineTimings())

    override suspend fun prepare(onProgress: (Int, String) -> Unit) = Unit
    override fun generate(request: InsightRequest): Flow<String> = flowOf(textToGenerate)
    override suspend fun unload() = Unit
    override fun close() = Unit
}
