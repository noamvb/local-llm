package com.noamv.localllm.engine

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.noamv.localllm.contract.EngineState
import com.noamv.localllm.contract.EngineStatus
import com.noamv.localllm.contract.InsightRequest
import com.noamv.localllm.model.ModelBackend
import com.noamv.localllm.model.ModelBuild
import com.noamv.localllm.model.ModelCatalog
import com.noamv.localllm.model.ModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [LlmEngine] backed by LiteRT-LM.
 *
 * The engine is expensive to construct, so one instance is retained until an explicit
 * unload. [EngineLifecycleCoordinator] is the sole owner of that instance: preparation,
 * generation, memory trimming, and close can never touch the native handle concurrently.
 */
class LiteRtEngine internal constructor(
    private val context: Context,
    private val store: ModelStore,
    private val successfulBuildStore: SuccessfulModelBuildStore,
) : LlmEngine {

    constructor(context: Context, store: ModelStore) : this(
        context = context,
        store = store,
        successfulBuildStore = PreferencesSuccessfulModelBuildStore(context),
    )

    /** The build this device should try first when no proven installed build exists. */
    val preferredBuild: ModelBuild =
        ModelCatalog.defaultFor(boardPlatform(), hasNpuDispatchLibraries(context))

    private val startupPolicy = ModelStartupPolicy(
        preferredBuild = preferredBuild,
        successfulBuildStore = successfulBuildStore,
        isInstalled = store::isInstalled,
    )

    private fun startupCandidates(): List<ModelBuild> = startupPolicy.candidates()

    private fun hasInstalledCandidate(): Boolean = startupPolicy.hasInstalledCandidate()

    private fun initialStatusBuild(): ModelBuild =
        startupCandidates().firstOrNull(store::isInstalled) ?: preferredBuild

    private val initialBuild = initialStatusBuild()

    private val _status = MutableStateFlow(
        EngineStatus(
            state = EngineState.MODEL_MISSING,
            modelId = initialBuild.id,
            modelDownloaded = hasInstalledCandidate(),
        ),
    )
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val _timings = MutableStateFlow(EngineTimings())
    override val timings: StateFlow<EngineTimings> = _timings.asStateFlow()

    private val lifecycle = EngineLifecycleCoordinator<Engine> { error ->
        Log.w(TAG, "Engine close failed", error)
    }

    val activeBuild: ModelBuild?
        get() = lifecycle.activeBuild

    override suspend fun prepare(onProgress: (Int, String) -> Unit) {
        try {
            lifecycle.prepare { loadFirstAvailable(onProgress) }
        } catch (outOfMemory: OutOfMemoryError) {
            publishOutOfMemory(activeBuild?.id ?: _status.value.modelId)
            throw outOfMemory
        }
    }

    private suspend fun loadFirstAvailable(
        onProgress: (Int, String) -> Unit,
    ): LoadedEngine<Engine> {
        val failures = mutableListOf<BackendInitializationException>()

        for (build in startupCandidates()) {
            try {
                val created = startWith(build, onProgress)
                try {
                    rememberSuccessfulBuild(build)
                    reclaimOtherBuilds(build)
                    _status.value = EngineStatus(
                        state = EngineState.READY,
                        modelId = build.id,
                        backend = build.backend.name,
                        downloadPercent = 100,
                        detail = "Ready",
                        modelDownloaded = true,
                    )
                    reportProgress(onProgress, 100, STAGE_READY)
                    return LoadedEngine(build, created)
                } catch (error: Throwable) {
                    runCatching { created.close() }
                    throw error
                }
            } catch (cancelled: CancellationException) {
                publishUnloaded(build.id, "Preparation cancelled")
                throw cancelled
            } catch (outOfMemory: OutOfMemoryError) {
                publishOutOfMemory(build.id)
                throw outOfMemory
            } catch (acquisition: ModelAcquisitionException) {
                // A network, storage, or verification failure says nothing about any
                // backend. Do not relabel it UNSUPPORTED or start another multi-GB
                // fallback download.
                publishUnloaded(build.id, acquisition.message.orEmpty())
                throw acquisition
            } catch (backend: BackendInitializationException) {
                Log.w(TAG, "Could not start ${build.id}", backend.cause)
                failures += backend
                // The verified artifact remains installed. Initialization can fail
                // transiently, and deleting it would turn a backend problem into another
                // multi-gigabyte acquisition.
            }
        }

        val failure = NoUsableBackendException(failures)
        _status.value = EngineStatus(
            state = EngineState.UNSUPPORTED,
            modelId = preferredBuild.id,
            detail = failure.message.orEmpty(),
            modelDownloaded = hasInstalledCandidate(),
        )
        throw failure
    }

    private suspend fun startWith(
        build: ModelBuild,
        onProgress: (Int, String) -> Unit,
    ): Engine {
        if (!store.isInstalled(build)) {
            _status.value = EngineStatus(
                state = EngineState.DOWNLOADING,
                modelId = build.id,
                detail = "Downloading ${build.displayName}",
                modelDownloaded = hasInstalledCandidate(),
            )
            try {
                store.ensureAvailable(build) { progress ->
                    _status.value = _status.value.copy(downloadPercent = progress.percent)
                    reportProgress(onProgress, progress.percent, STAGE_DOWNLOADING)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (outOfMemory: OutOfMemoryError) {
                throw outOfMemory
            } catch (error: Throwable) {
                throw ModelAcquisitionException(build, error)
            }
        }

        _status.value = EngineStatus(
            state = EngineState.INITIALISING,
            modelId = build.id,
            downloadPercent = 100,
            detail = "Loading ${build.displayName}",
            modelDownloaded = true,
        )
        reportProgress(onProgress, -1, STAGE_INITIALISING)

        val startInit = SystemClock.elapsedRealtime()
        var initializedBeforeContextReturn: Engine? = null
        val created = try {
            withContext(Dispatchers.IO) {
                val config = EngineConfig(
                    modelPath = store.fileFor(build).absolutePath,
                    backend = build.toBackend(context),
                    cacheDir = context.cacheDir.path,
                )
                initializeOwnedEngine(
                    create = { Engine(config) },
                    initialize = Engine::initialize,
                ).also { initializedBeforeContextReturn = it }
            }
        } catch (cancelled: CancellationException) {
            // withContext can observe cancellation after its block initialized the
            // resource but before it delivers the result. Close that otherwise-lost
            // engine explicitly.
            runCatching { initializedBeforeContextReturn?.close() }
            throw cancelled
        } catch (outOfMemory: OutOfMemoryError) {
            runCatching { initializedBeforeContextReturn?.close() }
            throw outOfMemory
        } catch (error: Throwable) {
            runCatching { initializedBeforeContextReturn?.close() }
            throw BackendInitializationException(build, error)
        }

        val elapsedInit = SystemClock.elapsedRealtime() - startInit
        try {
            _timings.update {
                it.copy(lastInitMillis = elapsedInit, lastInitBackend = build.backend.name)
            }
            Log.i(TAG, "init build=${build.id} backend=${build.backend} tookMs=$elapsedInit")
            return created
        } catch (error: Throwable) {
            runCatching { created.close() }
            throw error
        }
    }

    override fun generate(request: InsightRequest): Flow<String> = flow {
        val startedAt = SystemClock.elapsedRealtime()
        val warm = lifecycle.isReady
        val hadInstalledModel = hasInstalledCandidate()
        try {
            // Keep the historical timing boundary: prefill begins after preparation, so
            // time spent waiting behind another generation remains visible separately
            // from model load time. A trim between these two calls is still safe; use()
            // will observe the unloaded state and prepare again under the same coordinator.
            prepare()
            val postPrepareAt = SystemClock.elapsedRealtime()
            lifecycle.use(loader = { loadFirstAvailable { _, _ -> } }) { loaded ->
                val config = ConversationConfig(
                    systemInstruction = Contents.of(PromptBuilder.systemInstruction(request)),
                    // Low temperature and a tight topP keep a small model close to the
                    // supplied facts. Creative sampling is the wrong choice here.
                    samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.25),
                )
                loaded.handle.createConversation(config).use { conversation ->
                    var first = true
                    var fragmentCount = 0
                    emitAll(
                        conversation
                            .sendMessageAsync(PromptBuilder.userMessage(request))
                            .map { it.toString() }
                            .onEach {
                                if (first) {
                                    first = false
                                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                                    val prefill = SystemClock.elapsedRealtime() - postPrepareAt
                                    _timings.update { timings ->
                                        timings.copy(
                                            lastTimeToFirstTokenMillis = elapsed,
                                            lastPrefillMillis = prefill,
                                            lastRequestWasWarm = warm,
                                            lastRequestDownloaded = !hadInstalledModel,
                                        )
                                    }
                                    Log.i(
                                        TAG,
                                        "ttft warm=$warm downloaded=${!hadInstalledModel} " +
                                            "totalMs=$elapsed prefillMs=$prefill",
                                    )
                                }
                                fragmentCount++
                            }
                            .onCompletion {
                                val totalDuration = SystemClock.elapsedRealtime() - startedAt
                                Log.i(
                                    TAG,
                                    "generate completed: fragments=$fragmentCount totalMs=$totalDuration",
                                )
                            },
                    )
                }
            }
        } catch (outOfMemory: OutOfMemoryError) {
            // The coordinator has already closed and forgotten the poisoned native
            // handle. The artifact and the proven-build preference are deliberately kept.
            publishOutOfMemory(_status.value.modelId)
            throw outOfMemory
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun unload() {
        val previousBuild = activeBuild ?: startupCandidates().firstOrNull(store::isInstalled)
        lifecycle.unload()
        publishUnloaded(previousBuild?.id ?: preferredBuild.id, "Closed")
    }

    override fun close() {
        // AutoCloseable is retained for callers outside a coroutine. Android lifecycle
        // callbacks use the suspend unload path from the application scope, so the main
        // thread never waits for an in-progress native generation.
        runBlocking(Dispatchers.IO) { unload() }
    }

    private fun publishUnloaded(modelId: String?, detail: String) {
        _status.value = EngineStatus(
            state = EngineState.MODEL_MISSING,
            modelId = modelId,
            detail = detail,
            modelDownloaded = hasInstalledCandidate(),
        )
    }

    private fun publishOutOfMemory(modelId: String?) {
        _status.value = EngineStatus(
            state = EngineState.MODEL_MISSING,
            modelId = modelId,
            detail = "Engine released after running out of memory",
            modelDownloaded = hasInstalledCandidate(),
        )
    }

    private fun rememberSuccessfulBuild(build: ModelBuild) {
        try {
            if (!startupPolicy.recordSuccess(build)) {
                Log.w(TAG, "Could not persist successful build ${build.id}")
            }
        } catch (error: Exception) {
            // Persistence diagnostics must not invalidate an initialized native engine.
            Log.w(TAG, "Could not persist successful build ${build.id}", error)
        }
    }

    private fun reclaimOtherBuilds(build: ModelBuild) {
        try {
            val reclaimed = store.pruneExcept(build)
            if (reclaimed > 0) {
                Log.i(TAG, "Reclaimed ${reclaimed / 1_000_000} MB of unused model files")
            }
        } catch (error: Exception) {
            // A cleanup failure is recoverable and must not discard a proven engine.
            Log.w(TAG, "Could not prune unused model files", error)
        }
    }

    private fun reportProgress(
        callback: (Int, String) -> Unit,
        percent: Int,
        stage: String,
    ) {
        try {
            callback(percent, stage)
        } catch (error: Exception) {
            // Progress is advisory; callback failure cannot own engine lifecycle.
            Log.w(TAG, "Progress callback failed", error)
        }
    }

    private fun ModelBuild.toBackend(context: Context): Backend = when (backend) {
        ModelBackend.NPU -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
        ModelBackend.GPU -> Backend.GPU()
        ModelBackend.CPU -> Backend.CPU()
    }

    companion object {
        private const val TAG = "LiteRtEngine"
        const val STAGE_DOWNLOADING = "downloading"
        const val STAGE_INITIALISING = "initialising"
        const val STAGE_READY = "ready"

        /** Build.SOC_MODEL is available from API 31, which is this app's minimum. */
        fun boardPlatform(): String? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null
        }.getOrNull()

        /** True only when vendor NPU dispatch libraries are bundled in this APK. */
        fun hasNpuDispatchLibraries(context: Context): Boolean = runCatching {
            File(context.applicationInfo.nativeLibraryDir)
                .list()
                .orEmpty()
                .any { it.startsWith("libQnnHtp") || it.startsWith("libLiteRtDispatch") }
        }.getOrDefault(false)
    }
}
