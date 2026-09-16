package com.noamv.localllm.speech

import android.os.SystemClock
import android.util.Log
import android.content.Context
import com.noamv.localllm.contract.v3.DictationResultFields
import com.noamv.localllm.contract.v3.DictationTimings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class DictationModelNotInstalledException(build: SpeechModelBuild) :
    Exception("Speech model is not installed: ${build.id}")

class DictationBusyException : Exception("Dictation is busy")

class DictationEngineFailureException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

internal interface DictationEngine {
    suspend fun transcribe(
        build: SpeechModelBuild,
        samples: FloatArray,
        onProgress: (Int) -> Unit,
    ): DictationResultFields
}

class WhisperEngine internal constructor(
    private val context: Context,
    private val native: WhisperBackend = WhisperNative(),
) : DictationEngine, AutoCloseable {
    private val operationLock = Mutex()
    private var loaded: LoadedContext? = null
    private val closed = AtomicBoolean(false)

    /** Runs an owner operation only when no dictation is currently using the native context. */
    internal suspend fun tryWithOperationLock(operation: suspend () -> Unit): Boolean {
        if (!operationLock.tryLock()) return false
        try {
            operation()
            return true
        } finally {
            operationLock.unlock()
        }
    }

    override suspend fun transcribe(
        build: SpeechModelBuild,
        samples: FloatArray,
        onProgress: (Int) -> Unit,
    ): DictationResultFields {
        if (!operationLock.tryLock()) throw DictationBusyException()
        try {
            return withContext(Dispatchers.Default) {
                check(!closed.get()) { "Whisper engine is closed" }
                val loadStartMs = SystemClock.elapsedRealtime()
                Log.d(TAG, "model load start model=${build.id} t=$loadStartMs")
                val handle = ensureLoaded(build)
                Log.d(
                    TAG,
                    "model load end model=${build.id} t=${SystemClock.elapsedRealtime()} elapsedMs=${SystemClock.elapsedRealtime() - loadStartMs}",
                )
                onProgress(10)
                val text = try {
                    Log.d(TAG, "native transcribe start model=${build.id} t=${SystemClock.elapsedRealtime()}")
                    native.transcribe(
                        handle,
                        samples,
                        // Android may report the device-wide count even when the app is cpuset-limited.
                        minOf(MAX_THREADS, Runtime.getRuntime().availableProcessors()),
                        TEMPERATURE_INCREMENT,
                    )
                        .also {
                            Log.d(TAG, "native transcribe end model=${build.id} t=${SystemClock.elapsedRealtime()}")
                        }
                } catch (error: Throwable) {
                    throw DictationEngineFailureException("Whisper transcription failed", error)
                }
                if (text.isBlank()) throw DictationEngineFailureException("Whisper returned no text")
                onProgress(100)
                val timings = runCatching { native.timingsMs(handle) }.getOrDefault(LongArray(4))
                DictationResultFields(
                    requestId = "",
                    text = text.trim(),
                    model = build.id,
                    audioSeconds = samples.size / 16_000.0,
                    timingsMs = DictationTimings(
                        load = timings.getOrElse(0) { 0L },
                        encode = timings.getOrElse(1) { 0L },
                        decode = timings.getOrElse(2) { 0L },
                        total = timings.getOrElse(3) { 0L },
                    ),
                )
            }
        } finally {
            operationLock.unlock()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        loaded?.let { runCatching { native.freeContext(it.handle) } }
        loaded = null
    }

    private fun ensureLoaded(build: SpeechModelBuild): Long {
        loaded?.takeIf { it.buildId == build.id }?.let { return it.handle }
        loaded?.let { runCatching { native.freeContext(it.handle) } }
        val modelFile = File(context.filesDir, "models/${build.fileName}")
        if (!modelFile.isFile) throw DictationModelNotInstalledException(build)
        val handle = try {
            native.initContext(modelFile.absolutePath)
        } catch (error: Throwable) {
            throw DictationEngineFailureException("Whisper model initialization failed", error)
        }
        if (handle == 0L) throw DictationEngineFailureException("Whisper model initialization failed")
        loaded = LoadedContext(build.id, handle)
        return handle
    }

    private data class LoadedContext(val buildId: String, val handle: Long)

    private companion object {
        const val TAG = "DictationV3"
        const val MAX_THREADS = 4
        const val TEMPERATURE_INCREMENT = 0.0f
    }
}
