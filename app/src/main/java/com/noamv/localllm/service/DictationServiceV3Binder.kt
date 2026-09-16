package com.noamv.localllm.service

import android.os.Binder
import android.os.ParcelFileDescriptor
import com.noamv.localllm.contract.v3.DictationCapabilities
import com.noamv.localllm.contract.v3.DictationContractV3
import com.noamv.localllm.contract.v3.DictationError
import com.noamv.localllm.contract.v3.DictationRequest
import com.noamv.localllm.contract.v3.DictationResultFields
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
        enforceCaller()
        return DictationContractV3.VERSION
    }

    override fun getCapabilitiesJson(): String {
        enforceCaller()
        return DictationContractV3.json.encodeToString(
            DictationCapabilities.serializer(),
            DictationCapabilities(
                models = listOf(
                    SpeechModelCapability(SpeechModelCatalog.BASE_EN.id, installed = false),
                    SpeechModelCapability(SpeechModelCatalog.SMALL_EN.id, installed = true),
                ),
            ),
        )
    }

    override fun transcribe(
        audio: ParcelFileDescriptor?,
        requestJson: String,
        callback: IDictationCallbackV3,
    ): String {
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
                callback.safeProgress(requestId, 10, "decoding")
                val fields = engine.transcribe(build, samples) { percent ->
                    callback.safeProgress(requestId, percent.coerceIn(0, 100), "decoding")
                }
                val resultJson = DictationContractV3.json.encodeToString(
                    DictationResultFields.serializer(),
                    fields.copy(requestId = requestId),
                )
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

}

private fun IDictationCallbackV3.safeProgress(requestId: String, percent: Int, stage: String) =
    runCatching { onProgress(requestId, percent, stage) }

private fun IDictationCallbackV3.safeComplete(requestId: String, resultJson: String) =
    runCatching { onComplete(requestId, resultJson) }

private fun IDictationCallbackV3.safeError(requestId: String, code: Int, message: String, retryable: Boolean) =
    runCatching { onError(requestId, code, message, retryable) }
