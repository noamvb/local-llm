package com.noamv.localllm.service

import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.noamv.localllm.contract.v3.DictationCapabilities
import com.noamv.localllm.contract.v3.DictationContractV3
import com.noamv.localllm.contract.v3.DictationError
import com.noamv.localllm.contract.v3.DictationRequest
import com.noamv.localllm.contract.v3.DictationResultFields
import com.noamv.localllm.contract.v3.StructureRequest
import com.noamv.localllm.contract.v3.StructureResultFields
import com.noamv.localllm.engine.LlmEngine
import com.noamv.localllm.engine.StructurePrompts
import com.noamv.localllm.engine.StructureOutputParser
import com.noamv.localllm.contract.v3.SpeechModelCapability
import com.noamv.localllm.speech.AudioFormatException
import com.noamv.localllm.speech.DictationBusyException
import com.noamv.localllm.speech.DictationEngine
import com.noamv.localllm.speech.DictationEngineFailureException
import com.noamv.localllm.speech.DictationModelNotInstalledException
import com.noamv.localllm.speech.SpeechModelCatalog
import com.noamv.localllm.speech.WavReader
import com.noamv.localllm.v3.IDictationCallbackV3
import com.noamv.localllm.v3.IDictationServiceV3
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class DictationServiceV3Binder(
    private val scope: CoroutineScope,
    private val callerAuthorizer: (Int) -> String,
    private val engine: DictationEngine,
    private val llmEngine: LlmEngine,
    private val prewarmModel: () -> Unit = {},
    private val onInferenceActivity: () -> Unit = {},
    private val getCallingUid: () -> Int = { Binder.getCallingUid() },
    private val readAudio: (ParcelFileDescriptor?) -> FloatArray = { descriptor ->
        requireNotNull(descriptor) { "audio descriptor is missing" }
        ParcelFileDescriptor.AutoCloseInputStream(
            ParcelFileDescriptor.dup(descriptor.fileDescriptor),
        ).use(WavReader::read)
    },
) : IDictationServiceV3.Stub() {
    private val inFlight = ConcurrentHashMap<String, InFlightDictation>()
    private val active = AtomicBoolean(false)

    override fun getApiVersion(): Int {
        return authorizedServiceCall(
            authorize = ::enforceCaller,
            afterAuthorization = prewarmModel,
            call = { DictationContractV3.VERSION },
        )
    }

    override fun getCapabilitiesJson(): String {
        return authorizedServiceCall(
            authorize = ::enforceCaller,
            afterAuthorization = prewarmModel,
            call = {
                DictationContractV3.json.encodeToString(
                    DictationCapabilities.serializer(),
                    DictationCapabilities(
                        models = listOf(
                            SpeechModelCapability(SpeechModelCatalog.BASE_EN.id, installed = false),
                            SpeechModelCapability(SpeechModelCatalog.SMALL_EN.id, installed = true),
                        ),
                    ),
                )
            },
        )
    }

    override fun transcribe(
        audio: ParcelFileDescriptor?,
        requestJson: String,
        callback: IDictationCallbackV3,
    ): String {
        val requestStartMs = SystemClock.elapsedRealtime()
        Log.d(TAG, "transcribe entry t=$requestStartMs")
        enforceCaller()
        val requestId = UUID.randomUUID().toString()
        val request = try {
            DictationContractV3.json.decodeFromString(DictationRequest.serializer(), requestJson)
        } catch (_: Exception) {
            callback.safeError(requestId, DictationError.BAD_REQUEST, "Malformed dictation request JSON", false)
            return requestId
        }
        if (request.language != "en") {
            callback.safeError(requestId, DictationError.BAD_REQUEST, "language must be en", false)
            return requestId
        }
        val build = SpeechModelCatalog.byId(request.model)
        if (build == null) {
            callback.safeError(requestId, DictationError.MODEL_NOT_INSTALLED, "Unknown speech model", false)
            return requestId
        }
        if (request.timeoutMs !in 5_000..120_000) {
            // The contract clamps this optional client hint; retaining the normalized value
            // makes the accepted request deterministic even though the native call is bounded
            // by its coroutine lifetime in this first implementation.
        }
        if (!active.compareAndSet(false, true)) {
            callback.safeError(requestId, DictationError.BUSY, "Dictation is busy", true)
            return requestId
        }

        val record = InFlightDictation(requestId, callback)
        inFlight[requestId] = record
        val job = scope.launch {
            try {
                val samples = readAudio(audio)
                Log.d(
                    TAG,
                    "after readAudio requestId=$requestId t=${SystemClock.elapsedRealtime()} samples=${samples.size}",
                )
                callback.safeProgress(requestId, 10, "decoding")
                Log.d(TAG, "before engine.transcribe requestId=$requestId t=${SystemClock.elapsedRealtime()}")
                val fields = engine.transcribe(build, samples) { percent ->
                    callback.safeProgress(requestId, percent.coerceIn(0, 100), "decoding")
                }
                val totalMs = SystemClock.elapsedRealtime() - requestStartMs
                Log.d(
                    TAG,
                    "after engine.transcribe requestId=$requestId t=${SystemClock.elapsedRealtime()} totalMs=$totalMs",
                )
                val resultJson = DictationContractV3.json.encodeToString(
                    DictationResultFields.serializer(),
                    fields.copy(
                        requestId = requestId,
                        timingsMs = fields.timingsMs.copy(total = totalMs),
                    ),
                )
                Log.d(TAG, "before callback.onComplete requestId=$requestId t=${SystemClock.elapsedRealtime()}")
                record.complete(resultJson)
            } catch (_: CancellationException) {
                record.error(DictationError.CANCELLED, "Dictation cancelled", false)
            } catch (error: Throwable) {
                val mapped = mapError(error)
                record.error(mapped.first, mapped.second, mapped.third)
            } finally {
                inFlight.remove(requestId, record)
                active.set(false)
                runCatching { audio?.close() }
            }
        }
        record.job = job
        return requestId
    }

    override fun structure(
        requestJson: String,
        callback: IDictationCallbackV3,
    ): String {
        enforceCaller()
        val requestId = UUID.randomUUID().toString()
        val request = try {
            DictationContractV3.json.decodeFromString(StructureRequest.serializer(), requestJson)
        } catch (_: Exception) {
            callback.safeError(requestId, DictationError.BAD_REQUEST, "Malformed structure request JSON", false)
            return requestId
        }
        if (request.text.isBlank() || request.text.length > 500) {
            callback.safeError(requestId, DictationError.BAD_REQUEST, "text must be 1..500 characters", false)
            return requestId
        }
        if (request.kinds.isEmpty() || request.kinds.any { it !in ALLOWED_STRUCTURE_KINDS }) {
            callback.safeError(requestId, DictationError.BAD_REQUEST, "kinds must contain only todo or note", false)
            return requestId
        }
        if (!active.compareAndSet(false, true)) {
            callback.safeError(requestId, DictationError.BUSY, "Dictation is busy", true)
            return requestId
        }

        val record = InFlightDictation(requestId, callback)
        inFlight[requestId] = record
        val job = scope.launch {
            try {
                val raw = llmEngine.structure(StructurePrompts.forDictation(request.text, request.kinds))
                Log.d(TAG, "structure raw requestId=$requestId chars=${raw.length} text=${raw.take(400)}")
                val parsed = StructureOutputParser.parse(raw, request.kinds.toSet())
                val resultJson = DictationContractV3.json.encodeToString(
                    StructureResultFields.serializer(),
                    parsed.copy(
                        requestId = requestId,
                        confidence = (kotlin.math.round(parsed.confidence * 100.0) / 100.0),
                        model = llmEngine.status.value.modelId ?: "unknown",
                    ),
                )
                record.complete(resultJson)
            } catch (_: CancellationException) {
                record.error(DictationError.CANCELLED, "Dictation cancelled", false)
            } catch (error: Throwable) {
                record.error(DictationError.ENGINE_FAILURE, error.message ?: "Structure engine failed", false)
            } finally {
                inFlight.remove(requestId, record)
                active.set(false)
                // v3 structure bypasses InferenceScheduler, so explicitly refresh the
                // application's idle-residency clock after this LLM activity.
                onInferenceActivity()
            }
        }
        record.job = job
        return requestId
    }

    override fun cancel(requestId: String) {
        enforceCaller()
        val record = inFlight[requestId] ?: return
        record.error(DictationError.CANCELLED, "Dictation cancelled", false)
        record.job?.cancel()
    }

    private fun enforceCaller(): String = callerAuthorizer(getCallingUid())

    private fun mapError(error: Throwable): Triple<Int, String, Boolean> = when (error) {
        is DictationBusyException -> Triple(DictationError.BUSY, "Dictation is busy", true)
        is DictationModelNotInstalledException -> Triple(DictationError.MODEL_NOT_INSTALLED, error.message ?: "Speech model is not installed", false)
        is AudioFormatException -> Triple(DictationError.AUDIO_FORMAT, error.message ?: "Unsupported WAV audio", false)
        is DictationEngineFailureException -> Triple(DictationError.ENGINE_FAILURE, error.message ?: "Whisper engine failed", false)
        else -> Triple(DictationError.ENGINE_FAILURE, error.message ?: "Whisper engine failed", false)
    }

    private class InFlightDictation(
        private val requestId: String,
        private val callback: IDictationCallbackV3,
    ) {
        private val terminal = AtomicBoolean(false)
        var job: Job? = null

        fun complete(resultJson: String) {
            if (terminal.compareAndSet(false, true)) callback.safeComplete(requestId, resultJson)
        }

        fun error(code: Int, message: String, retryable: Boolean) {
            if (terminal.compareAndSet(false, true)) callback.safeError(requestId, code, message, retryable)
        }
    }

    private companion object {
        const val TAG = "DictationV3"
        val ALLOWED_STRUCTURE_KINDS = setOf("todo", "note")
    }

}

private fun IDictationCallbackV3.safeProgress(requestId: String, percent: Int, stage: String) =
    runCatching { onProgress(requestId, percent, stage) }

private fun IDictationCallbackV3.safeComplete(requestId: String, resultJson: String) =
    runCatching { onComplete(requestId, resultJson) }

private fun IDictationCallbackV3.safeError(requestId: String, code: Int, message: String, retryable: Boolean) =
    runCatching { onError(requestId, code, message, retryable) }
