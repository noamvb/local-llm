package com.noamv.localllm.engine

import android.content.Context
import android.os.Build
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    private val selectedBuild: ModelBuild = ModelCatalog.defaultFor(boardPlatform()),
) : LlmEngine {

    private val _status = MutableStateFlow(
        EngineStatus(state = EngineState.MODEL_MISSING, modelId = selectedBuild.id),
    )
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val lifecycleLock = Mutex()
    private val generationLock = Mutex()

    @Volatile
    private var engine: Engine? = null

    override suspend fun prepare(onProgress: (Int, String) -> Unit) {
        lifecycleLock.withLock {
            if (engine != null) return

            if (!store.isInstalled(selectedBuild)) {
                _status.value = _status.value.copy(
                    state = EngineState.DOWNLOADING,
                    detail = "Downloading ${selectedBuild.displayName}",
                )
                store.ensureAvailable(selectedBuild) { progress ->
                    _status.value = _status.value.copy(downloadPercent = progress.percent)
                    onProgress(progress.percent, STAGE_DOWNLOADING)
                }
            }

            _status.value = _status.value.copy(
                state = EngineState.INITIALISING,
                downloadPercent = 100,
                detail = "Loading the model",
            )
            onProgress(-1, STAGE_INITIALISING)

            val created = withContext(Dispatchers.IO) {
                val config = EngineConfig(
                    modelPath = store.fileFor(selectedBuild).absolutePath,
                    backend = selectedBuild.toBackend(context),
                    cacheDir = context.cacheDir.path,
                )
                Engine(config).apply { initialize() }
            }
            engine = created

            _status.value = EngineStatus(
                state = EngineState.READY,
                modelId = selectedBuild.id,
                backend = selectedBuild.backend.name,
                downloadPercent = 100,
                detail = "Ready",
            )
            onProgress(100, STAGE_READY)
        }
    }

    override fun generate(request: InsightRequest): Flow<String> = flow {
        prepare()
        val active = engine ?: error("Engine is not initialised")

        generationLock.withLock {
            val config = ConversationConfig(
                systemInstruction = Contents.of(PromptBuilder.systemInstruction(request)),
                // Low temperature and a tight topP keep a small model close to the facts
                // it was given. Creative sampling is exactly the wrong choice here.
                samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.25),
            )
            active.createConversation(config).use { conversation ->
                emitAll(
                    conversation
                        .sendMessageAsync(PromptBuilder.userMessage(request))
                        .map { it.toString() },
                )
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun close() {
        runCatching { engine?.close() }
            .onFailure { Log.w(TAG, "Engine close failed", it) }
        engine = null
        _status.value = _status.value.copy(state = EngineState.MODEL_MISSING, detail = "Closed")
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
    }
}
