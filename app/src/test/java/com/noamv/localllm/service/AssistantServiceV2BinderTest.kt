package com.noamv.localllm.service

import com.noamv.localllm.contract.EngineState
import com.noamv.localllm.contract.EngineStatus
import com.noamv.localllm.contract.InsightRequest
import com.noamv.localllm.contract.LocalLlmError
import com.noamv.localllm.contract.v2.AppSource
import com.noamv.localllm.contract.v2.AssistantContractV2
import com.noamv.localllm.contract.v2.AssistantTerminalResult
import com.noamv.localllm.contract.v2.AssistantTerminalStatus
import com.noamv.localllm.contract.v2.AssistantTurnRequest
import com.noamv.localllm.contract.v2.HistoryPage
import com.noamv.localllm.contract.v2.HistoryQuery
import com.noamv.localllm.contract.v2.ProviderFactsResult
import com.noamv.localllm.engine.EngineTimings
import com.noamv.localllm.engine.InferenceScheduler
import com.noamv.localllm.engine.LlmEngine
import com.noamv.localllm.engine.ModelResidencyCoordinator
import com.noamv.localllm.history.AssistantHistoryRepository
import com.noamv.localllm.history.FakeAssistantHistoryDao
import com.noamv.localllm.orchestrator.AssistantOrchestratorV2
import com.noamv.localllm.privacy.AssistantAccessPolicy
import com.noamv.localllm.privacy.FakeDataStore
import com.noamv.localllm.v2.IAssistantCallbackV2
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantServiceV2BinderTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var fakeDao: FakeAssistantHistoryDao
    private lateinit var historyRepository: AssistantHistoryRepository
    private lateinit var fakeDataStore: FakeDataStore
    private lateinit var accessPolicy: AssistantAccessPolicy
    private lateinit var scheduler: InferenceScheduler
    private lateinit var binder: AssistantServiceV2Binder

    private var callerPackage = "com.noamv.cannsheet.mobile"

    @Before
    fun setUp() {
        fakeDao = FakeAssistantHistoryDao()
        historyRepository = AssistantHistoryRepository(fakeDao)
        fakeDataStore = FakeDataStore()
        accessPolicy = AssistantAccessPolicy(fakeDataStore)
        scheduler = InferenceScheduler(testScope)

        val residencyCoordinator = ModelResidencyCoordinator { 4_000_000_000L }
        val dummyEngine = createDummyEngine()
        val orchestrator = AssistantOrchestratorV2(
            engine = dummyEngine,
            historyRepository = historyRepository,
            accessPolicy = accessPolicy,
            residencyCoordinator = residencyCoordinator,
            factProviderQuery = { _, _ ->
                ProviderFactsResult(
                    sourceApp = AppSource.CANNSHEET,
                    revision = "r1",
                    asOfTime = 1000L,
                    timezone = "UTC",
                )
            },
        )

        binder = AssistantServiceV2Binder(
            scope = testScope,
            callerAuthorizer = { callerPackage },
            accessPolicy = accessPolicy,
            historyRepository = historyRepository,
            orchestrator = orchestrator,
            scheduler = scheduler,
            getCallingUid = { 1000 },
        )
    }

    @Test
    fun testDisabledClientGetsAccessDeniedErrorOnStartTurn() = runTest {
        accessPolicy.setCannsheetEnabled(false)

        val callback = FakeCallback()
        val request = AssistantTurnRequest(
            requestId = "req-1",
            threadId = "thread-1",
            initiatingClient = "com.noamv.cannsheet.mobile",
            question = "How much weed did I buy?",
            defaultSource = AppSource.CANNSHEET,
        )
        val requestJson = AssistantContractV2.json.encodeToString(AssistantTurnRequest.serializer(), request)

        val reqId = binder.startTurn(requestJson, callback)
        assertEquals("req-1", reqId)
        assertEquals(LocalLlmError.INVALID_REQUEST, callback.lastErrorCode)
        assertTrue(callback.lastErrorMessage?.contains("disabled") == true)
    }

    @Test
    fun testDisabledClientGetsEmptyHistoryPage() = runTest {
        // Record a turn first
        historyRepository.recordTurn(
            threadId = "thread-1",
            turnId = "turn-1",
            initiatingClient = "com.noamv.cannsheet.mobile",
            question = "Q",
            result = AssistantTerminalResult(AssistantTerminalStatus.VALIDATED, "Ans"),
            citedFacts = emptyList(),
            sources = listOf(AppSource.CANNSHEET),
            period = null,
            asOfTime = 1000L,
            modelVersion = null,
        )

        // Disable Cannsheet
        accessPolicy.setCannsheetEnabled(false)

        val queryJson = AssistantContractV2.json.encodeToString(HistoryQuery.serializer(), HistoryQuery())
        val pageJson = binder.getHistoryPage(queryJson)
        val page = AssistantContractV2.json.decodeFromString(HistoryPage.serializer(), pageJson)

        assertEquals(0, page.threads.size)
        assertEquals(0, page.turns.size)
    }

    @Test
    fun testCancelTurnInvokesSchedulerCancel() = runTest {
        val callback = FakeCallback()
        val request = AssistantTurnRequest(
            requestId = "req-cancel-1",
            threadId = "thread-cancel",
            initiatingClient = "com.noamv.cannsheet.mobile",
            question = "Tell me about my cannabis spend",
            defaultSource = AppSource.CANNSHEET,
        )
        val requestJson = AssistantContractV2.json.encodeToString(AssistantTurnRequest.serializer(), request)

        binder.startTurn(requestJson, callback)
        binder.cancelTurn("req-cancel-1")

        // Turn must not write a VALIDATED history row
        val turns = fakeDao.getTurnsForThread(request.threadId)
        assertFalse(turns.any { it.terminalStatus == "VALIDATED" })
    }

    private fun createDummyEngine(): LlmEngine = object : LlmEngine {
        override val status = MutableStateFlow(
            EngineStatus(
                state = EngineState.READY,
                modelDownloaded = true,
            ),
        )
        override val timings = MutableStateFlow(EngineTimings())

        override suspend fun prepare(onProgress: (Int, String) -> Unit) = Unit
        override fun generate(request: InsightRequest): Flow<String> = flowOf("Sample response")
        override suspend fun unload() = Unit
        override fun close() = Unit
    }

    private class FakeCallback : IAssistantCallbackV2.Stub() {
        var lastErrorCode: Int? = null
        var lastErrorMessage: String? = null
        var lastResultJson: String? = null

        override fun onEvent(requestId: String?, eventJson: String?) {}
        override fun onComplete(requestId: String?, resultJson: String?) {
            lastResultJson = resultJson
        }
        override fun onError(requestId: String?, errorCode: Int, errorMessage: String?, retryable: Boolean) {
            lastErrorCode = errorCode
            lastErrorMessage = errorMessage
        }
    }
}
