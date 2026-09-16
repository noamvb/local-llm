package com.noamv.localllm.client.v3

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.noamv.localllm.contract.v3.DictationCapabilities
import com.noamv.localllm.contract.v3.DictationContractV3
import com.noamv.localllm.contract.v3.DictationRequest
import com.noamv.localllm.contract.v3.DictationResultFields
import com.noamv.localllm.v3.IDictationCallbackV3
import com.noamv.localllm.v3.IDictationServiceV3
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

typealias DictationResult = DictationResultFields

class DictationClientV3(
    context: Context,
    private val servicePackageName: String = "com.noamv.localllm",
    private val bindTimeoutMillis: Long = 5_000L,
) {
    private val appContext = context.applicationContext ?: context

    class Unavailable(message: String, cause: Throwable? = null) : Exception(message, cause)
    class DictationFailed(val code: Int, message: String, val retryable: Boolean) : Exception(message)

    suspend fun transcribe(
        wavFile: File,
        model: String = "whisper-base-en",
    ): DictationResult = withTimeout(bindTimeoutMillis) {
        withService { service ->
            if (service.apiVersion != DictationContractV3.VERSION) {
                throw Unavailable("Incompatible dictation API version: ${service.apiVersion}")
            }
            val descriptor = try {
                ParcelFileDescriptor.open(wavFile, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (error: Exception) {
                throw Unavailable("Could not open WAV file", error)
            }
            suspendCancellableCoroutine { continuation ->
                val terminal = AtomicBoolean(false)
                val requestId = AtomicReference<String?>(null)
                val callback = object : IDictationCallbackV3.Stub() {
                    override fun onProgress(requestId: String?, percent: Int, stage: String?) = Unit

                    override fun onComplete(requestId: String?, resultJson: String?) {
                        if (!terminal.compareAndSet(false, true)) return
                        try {
                            if (resultJson == null) throw Unavailable("LocalLLM returned no dictation result")
                            continuation.resume(
                                DictationContractV3.json.decodeFromString(
                                    DictationResultFields.serializer(),
                                    resultJson,
                                ),
                            )
                        } catch (error: Throwable) {
                            continuation.resumeWithException(error)
                        }
                    }

                    override fun onError(requestId: String?, errorCode: Int, message: String?, retryable: Boolean) {
                        if (!terminal.compareAndSet(false, true)) return
                        continuation.resumeWithException(
                            DictationFailed(errorCode, message ?: "Dictation failed", retryable),
                        )
                    }
                }
                try {
                    requestId.set(
                        service.transcribe(
                            descriptor,
                            DictationContractV3.json.encodeToString(
                                DictationRequest.serializer(),
                                DictationRequest(model = model),
                            ),
                            callback,
                        ),
                    )
                } catch (error: Throwable) {
                    descriptor.close()
                    if (terminal.compareAndSet(false, true)) continuation.resumeWithException(Unavailable("Failed to start dictation", error))
                    return@suspendCancellableCoroutine
                }
                continuation.invokeOnCancellation {
                    if (terminal.compareAndSet(false, true)) {
                        requestId.get()?.let { runCatching { service.cancel(it) } }
                        runCatching { descriptor.close() }
                    }
                }
            }
        }
    }

    suspend fun capabilities(): DictationCapabilities = withTimeout(bindTimeoutMillis) {
        withService { service ->
            if (service.apiVersion != DictationContractV3.VERSION) {
                throw Unavailable("Incompatible dictation API version: ${service.apiVersion}")
            }
            DictationContractV3.json.decodeFromString(
                DictationCapabilities.serializer(),
                service.capabilitiesJson,
            )
        }
    }

    private suspend fun <T> withService(block: suspend (IDictationServiceV3) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val unbound = AtomicBoolean(false)
            lateinit var connection: ServiceConnection
            fun cleanup() {
                if (unbound.compareAndSet(false, true)) runCatching { appContext.unbindService(connection) }
            }
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val service = binder?.let(IDictationServiceV3.Stub::asInterface)
                    if (service == null) {
                        cleanup()
                        if (continuation.isActive) continuation.resumeWithException(Unavailable("Dictation service returned no Binder"))
                        return
                    }
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                        try {
                            val value = block(service)
                            if (continuation.isActive) continuation.resume(value)
                        } catch (error: Throwable) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        } finally {
                            cleanup()
                        }
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    cleanup()
                    if (continuation.isActive) continuation.resumeWithException(Unavailable("Dictation service disconnected"))
                }

                override fun onNullBinding(name: ComponentName?) {
                    cleanup()
                    if (continuation.isActive) continuation.resumeWithException(Unavailable("Dictation service returned a null binding"))
                }

                override fun onBindingDied(name: ComponentName?) {
                    cleanup()
                    if (continuation.isActive) continuation.resumeWithException(Unavailable("Dictation service binding died"))
                }
            }
            val intent = Intent("com.noamv.localllm.v3.action.BIND_DICTATION").apply {
                component = ComponentName(servicePackageName, "com.noamv.localllm.service.InferenceService")
            }
            val bound = try {
                appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (error: Throwable) {
                cleanup()
                continuation.resumeWithException(Unavailable("Failed to bind dictation service", error))
                return@suspendCancellableCoroutine
            }
            if (!bound) {
                cleanup()
                continuation.resumeWithException(Unavailable("bindService returned false for dictation service"))
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { cleanup() }
        }
}
