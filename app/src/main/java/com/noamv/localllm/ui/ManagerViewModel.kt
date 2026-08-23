package com.noamv.localllm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import com.noamv.localllm.LocalLlmApplication
import com.noamv.localllm.contract.EngineStatus
import com.noamv.localllm.contract.Fact
import com.noamv.localllm.contract.InsightRequest
import com.noamv.localllm.contract.InsightTask
import com.noamv.localllm.contract.Period
import com.noamv.localllm.engine.EngineTimings
import com.noamv.localllm.engine.GenerationOutputPolicy
import com.noamv.localllm.engine.InferenceAdmission
import com.noamv.localllm.engine.InferencePriority
import com.noamv.localllm.engine.InferenceQueueState
import com.noamv.localllm.engine.InferenceScheduler
import com.noamv.localllm.engine.LiteRtEngine
import com.noamv.localllm.engine.LlmEngine
import com.noamv.localllm.model.ModelBuild
import com.noamv.localllm.model.ModelCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class ManagerViewModel(
    private val engine: LlmEngine,
    private val build: ModelBuild,
    private val startPreparing: () -> Unit,
    private val scheduler: InferenceScheduler,
) : ViewModel() {

    val status: StateFlow<EngineStatus> = engine.status
    val timings: StateFlow<EngineTimings> = engine.timings

    private val _selfTest = MutableStateFlow<String?>(null)
    val selfTest: StateFlow<String?> = _selfTest.asStateFlow()
    private val activeSelfTestId = AtomicReference<String?>(null)
    private var selfTestJob: Job? = null

    val modelName: String get() = build.displayName
    val modelSizeGb: Double get() = build.sizeGb
    val chipset: String? get() = LiteRtEngine.boardPlatform()

    /**
     * Starts the download and load on the application scope rather than
     * [viewModelScope], so that closing this screen does not abandon a multi-gigabyte
     * transfer. Progress and any failure arrive through [status], which the screen
     * already renders.
     */
    fun prepare() = startPreparing()

    /**
     * Exercises the whole pipeline with fixed facts, so the owner can tell the model is
     * working without needing a client app installed.
     */
    fun runSelfTest() {
        selfTestJob?.cancel()
        val requestId = UUID.randomUUID().toString()
        activeSelfTestId.set(requestId)
        _selfTest.value = ""
        val request = InsightRequest(
            clientId = "localllm-selftest",
            task = InsightTask.PERIOD_SUMMARY,
            subject = "a self-test of the local model",
            period = Period(label = "the last 7 days"),
            facts = listOf(
                Fact("Entries recorded", "12"),
                Fact("Busiest day", "Tuesday", "4 entries"),
                Fact("Average per day", "1.7"),
            ),
            maxWords = 60,
        )
        lateinit var newJob: Job
        newJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val terminal = CompletableDeferred<InferenceQueueState>()
            val admission = scheduler.submit(
                requestId = requestId,
                priority = InferencePriority.OPEN_SCREEN,
                onState = { state ->
                    if (state.isTerminal()) terminal.complete(state)
                },
            ) {
                val builder = StringBuilder()
                try {
                    engine.generate(request).collect { fragment ->
                        GenerationOutputPolicy.append(builder, fragment)
                        updateSelfTest(requestId, builder.toString())
                    }
                    updateSelfTest(
                        requestId,
                        GenerationOutputPolicy.validatedTerminalText(request, builder.toString()),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    updateSelfTest(requestId, "Self-test failed: ${error.message.orEmpty()}")
                    throw error
                }
            }

            when (admission) {
                is InferenceAdmission.Accepted -> try {
                    when (terminal.await()) {
                        InferenceQueueState.EXPIRED ->
                            updateSelfTest(requestId, "Self-test queue wait expired. Retry later.")
                        InferenceQueueState.FAILED -> {
                            if (_selfTest.value.isNullOrEmpty()) {
                                updateSelfTest(requestId, "Self-test failed unexpectedly.")
                            }
                        }
                        InferenceQueueState.CANCELLED,
                        InferenceQueueState.COMPLETED,
                        -> Unit
                        InferenceQueueState.QUEUED,
                        InferenceQueueState.ACTIVE,
                        -> error("Nonterminal scheduler state completed the self-test")
                    }
                } finally {
                    if (!terminal.isCompleted) scheduler.cancel(requestId)
                }
                InferenceAdmission.Busy ->
                    updateSelfTest(requestId, "Self-test busy. Retry after current work finishes.")
                InferenceAdmission.Closed ->
                    updateSelfTest(requestId, "Self-test unavailable while inference is closing.")
            }
        }
        selfTestJob = newJob
        newJob.invokeOnCompletion {
            activeSelfTestId.compareAndSet(requestId, null)
            if (selfTestJob === newJob) selfTestJob = null
        }
        newJob.start()
    }

    private fun updateSelfTest(requestId: String, value: String) {
        if (activeSelfTestId.get() == requestId) _selfTest.value = value
    }

    private fun InferenceQueueState.isTerminal(): Boolean = when (this) {
        InferenceQueueState.COMPLETED,
        InferenceQueueState.CANCELLED,
        InferenceQueueState.FAILED,
        InferenceQueueState.EXPIRED,
        -> true
        InferenceQueueState.QUEUED,
        InferenceQueueState.ACTIVE,
        -> false
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as LocalLlmApplication
                ManagerViewModel(
                    engine = app.engine,
                    build = ModelCatalog.defaultFor(
                        board = LiteRtEngine.boardPlatform(),
                        npuDispatchAvailable = LiteRtEngine.hasNpuDispatchLibraries(app),
                    ),
                    startPreparing = { app.prepareModel() },
                    scheduler = app.inferenceScheduler,
                )
            }
        }
    }
}
