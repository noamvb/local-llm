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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [LlmEngine] backed by LiteRT-LM.
 *
 * The engine is expensive to construct — initialize() can take around ten seconds — so
 * one instance is held for the process lifetime and reused across requests. A fresh
 * Conversation is created per request so that no state leaks between the two client
 * apps; conversations are cheap, engines are not.
 */
class LiteRtEngine(
    private val context: Context,
    private val store: ModelStore,
) : LlmEngine {

    /** The build this device should try first. */
    val preferredBuild: ModelBuild =
        ModelCatalog.defaultFor(boardPlatform(), hasNpuDispatchLibraries(context))

    private val _status = MutableStateFlow(
        EngineStatus(
            state = EngineState.MODEL_MISSING,
            modelId = preferredBuild.id,
            modelDownloaded = store.isInstalled(preferredBuild),
        ),
    )
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val _timings = MutableStateFlow(EngineTimings())
    override val timings: StateFlow<EngineTimings> = _timings.asStateFlow()

    private val lifecycleLock = Mutex()
    private val generationLock = Mutex()

    @Volatile
    private var engine: Engine? = null

    @Volatile
    var activeBuild: ModelBuild? = null
        private set

    override suspend fun prepare(onProgress: (Int, String) -> Unit) {
        lifecycleLock.withLock {
            if (engine != null) return

            val candidates = ModelCatalog.fallbacksFor(preferredBuild)
            val failures = mutableListOf<String>()

            for (build in candidates) {
                try {
                    startWith(build, onProgress)
                    return
                } catch (cancelled: CancellationException) {
                    // An interrupted attempt says nothing about the build. Leaving the
                    // screen cancels this coroutine, and treating that as a failure used
                    // to delete the model and its partial file, so a download that was
                    // 90% done restarted from zero on the next attempt. Rethrow instead:
                    // ModelStore keeps the .part file, and the next call resumes it.
                    closeQuietly()
                    throw cancelled
                } catch (error: Throwable) {
                    Log.w(TAG, "Could not start ${build.id}", error)
                    failures += "${build.displayName}: ${error.message.orEmpty().lines().first()}"
                    closeQuietly()
                    // The file is deliberately kept. A start failure can be transient — a
                    // busy GPU, a low-memory moment — and discarding a verified model to
                    // find that out costs the user a multi-gigabyte download. Once some
                    // build does start, pruneExcept reclaims the ones that did not.
                }
            }

            _status.value = EngineStatus(
                state = EngineState.UNSUPPORTED,
                modelId = preferredBuild.id,
                detail = "No model could be started. ${failures.joinToString("; ")}",
                // Report the file as present if it is. Defaulting this to false told every
                // client the model was missing for the rest of the process lifetime, which
                // silently hid their insight cards long after the cause had cleared.
                modelDownloaded = store.isInstalled(preferredBuild),
            )
            error("No usable model backend on this device. ${failures.joinToString("; ")}")
        }
    }

    private suspend fun startWith(build: ModelBuild, onProgress: (Int, String) -> Unit) {
        if (!store.isInstalled(build)) {
            _status.value = EngineStatus(
                state = EngineState.DOWNLOADING,
                modelId = build.id,
                detail = "Downloading ${build.displayName}",
            )
            store.ensureAvailable(build) { progress ->
                _status.value = _status.value.copy(downloadPercent = progress.percent)
                onProgress(progress.percent, STAGE_DOWNLOADING)
            }
        }

        _status.value = EngineStatus(
            state = EngineState.INITIALISING,
            modelId = build.id,
            downloadPercent = 100,
            detail = "Loading ${build.displayName}",
            modelDownloaded = true,
        )
        onProgress(-1, STAGE_INITIALISING)

        val startInit = SystemClock.elapsedRealtime()
        val created = withContext(Dispatchers.IO) {
            val config = EngineConfig(
                modelPath = store.fileFor(build).absolutePath,
                backend = build.toBackend(context),
                cacheDir = context.cacheDir.path,
            )
            Engine(config).apply { initialize() }
        }
        val elapsedInit = SystemClock.elapsedRealtime() - startInit
        _timings.update {
            it.copy(lastInitMillis = elapsedInit, lastInitBackend = build.backend.name)
        }
        Log.i(TAG, "init build=${build.id} backend=${build.backend} tookMs=$elapsedInit")

        engine = created
        activeBuild = build
        // Reclaim any earlier build now that this one is proven to start.
        val reclaimed = store.pruneExcept(build)
        if (reclaimed > 0) Log.i(TAG, "Reclaimed ${reclaimed / 1_000_000} MB of unused model files")
        _status.value = EngineStatus(
            state = EngineState.READY,
            modelId = build.id,
            backend = build.backend.name,
            downloadPercent = 100,
            detail = "Ready",
            modelDownloaded = true,
        )
        onProgress(100, STAGE_READY)
    }

    override fun generate(request: InsightRequest): Flow<String> = flow {
        val startedAt = SystemClock.elapsedRealtime()
        val warm = engine != null
        val downloaded = _status.value.modelDownloaded || store.isInstalled(preferredBuild)
        prepare()
        val postPrepareAt = SystemClock.elapsedRealtime()
        val active = engine ?: error("Engine is not initialised")

        generationLock.withLock {
            val config = ConversationConfig(
                systemInstruction = Contents.of(PromptBuilder.systemInstruction(request)),
                // Low temperature and a tight topP keep a small model close to the facts
                // it was given. Creative sampling is exactly the wrong choice here.
                samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.25),
            )
            active.createConversation(config).use { conversation ->
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
                                        lastRequestDownloaded = !downloaded,
                                    )
                                }
                                Log.i(
                                    TAG,
                                    "ttft warm=$warm downloaded=${!downloaded} totalMs=$elapsed prefillMs=$prefill",
                                )
                            }
                            fragmentCount++
                        }
                        .onCompletion {
                            val totalDuration = SystemClock.elapsedRealtime() - startedAt
                            Log.i(TAG, "generate completed: fragments=$fragmentCount totalMs=$totalDuration")
                        },
                )
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun close() {
        closeQuietly()
        _status.value = _status.value.copy(
            state = EngineState.MODEL_MISSING,
            detail = "Closed",
            modelDownloaded = activeOrPreferredInstalled(),
        )
    }

    private fun activeOrPreferredInstalled(): Boolean =
        store.isInstalled(activeBuild ?: preferredBuild)

    private fun closeQuietly() {
        runCatching { engine?.close() }.onFailure { Log.w(TAG, "Engine close failed", it) }
        engine = null
        activeBuild = null
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

        /**
         * The SoC identifier, used to decide whether a chipset-specific NPU build applies.
         * Build.SOC_MODEL is available from API 31, which is this app's minimum.
         */
        fun boardPlatform(): String? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null
        }.getOrNull()

        /**
         * True when vendor NPU dispatch libraries are bundled in this APK.
         *
         * They are not part of litertlm-android. Obtaining them means downloading the
         * QAIRT SDK and building the dispatch shim with Bazel, so unless that has been
         * done deliberately this returns false and the GPU build is used instead.
         */
        fun hasNpuDispatchLibraries(context: Context): Boolean = runCatching {
            File(context.applicationInfo.nativeLibraryDir)
                .list()
                .orEmpty()
                .any { it.startsWith("libQnnHtp") || it.startsWith("libLiteRtDispatch") }
        }.getOrDefault(false)
    }
}
