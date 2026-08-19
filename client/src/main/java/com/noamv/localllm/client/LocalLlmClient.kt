package com.noamv.localllm.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.noamv.localllm.IInsightCallback
import com.noamv.localllm.IInsightService
import com.noamv.localllm.contract.EngineStatus
import com.noamv.localllm.contract.InsightContract
import com.noamv.localllm.contract.InsightRequest
import com.noamv.localllm.contract.LocalLlmError
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Client-side helper for talking to the LocalLLM inference service.
 *
 * Copy the whole `client/src/main` tree into the client app's module. The .aidl files
 * must keep the `com/noamv/localllm/` package path exactly, and the module needs
 * `buildFeatures { aidl = true }` in its build.gradle.kts.
 *
 * The client app's manifest also needs, at the top level:
 *
 *     <uses-permission android:name="com.noamv.localllm.permission.INFERENCE" />
 *     <queries>
 *         <package android:name="com.noamv.localllm" />
 *     </queries>
 *
 * The `<queries>` entry is required from Android 11 onward. Without it the service is
 * invisible to this app and bindService returns false with no other explanation.
 */
class LocalLlmClient(private val context: Context) {

    /** The LocalLLM app is absent, or present but refused the bind. */
    class Unavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Terminal failure reported by the service. Codes are [LocalLlmError] values. */
    class InferenceFailed(val code: Int, message: String) : Exception(message)

    /** True when the LocalLLM app is installed and visible to this app. */
    fun isInstalled(): Boolean =
        context.packageManager.resolveService(serviceIntent(), 0) != null

    /**
     * Holds the service binding open so a model loaded during the status check is still
     * loaded when generate() runs moments later.
     *
     * Without this the client binds twice — once for engineStatus(), once for generate() —
     * and LocalLLM has no bindings in the gap, which makes it a prime candidate for being
     * killed with a freshly loaded 2 GB model still in memory.
     *
     * Open it when the screen that may show an insight becomes visible; close it when that
     * screen goes away. Closing is mandatory: an unclosed handle keeps LocalLLM resident.
     */
    fun warmup(): AutoCloseable {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) = Unit
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val bound = runCatching {
            context.bindService(serviceIntent(), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)

        val isClosed = AtomicBoolean(false)
        return AutoCloseable {
            if (isClosed.compareAndSet(false, true)) {
                // Always unbind: bindService registers the connection even when it returns false,
                // and skipping it leaks that registration. unbindService throws
                // IllegalArgumentException for a connection that was never registered — which is
                // why this stays wrapped.
                runCatching { context.unbindService(connection) }
            }
        }
    }

    /**
     * Reads engine state without generating anything. Use it to decide whether to show
     * an insight card at all, or a "model not downloaded yet" prompt instead.
     */
    suspend fun engineStatus(): EngineStatus = withService { service ->
        InsightContract.json.decodeFromString(EngineStatus.serializer(), service.engineState)
    }

    /**
     * Runs one request and emits generated text fragments.
     *
     * The service connection is held for the lifetime of the flow and released when
     * collection ends, including on cancellation, which also cancels the generation.
     */
    fun generate(request: InsightRequest): Flow<String> = callbackFlow {
        val requestIdRef = AtomicReference<String?>(null)
        val serviceRef = AtomicReference<IInsightService?>(null)

        val callback = object : IInsightCallback.Stub() {
            override fun onToken(requestId: String, token: String) {
                trySend(token)
            }

            override fun onComplete(requestId: String, text: String, resultJson: String?) {
                // A non-streaming request delivers everything here and nothing earlier.
                if (!request.stream) trySend(text)
                close()
            }

            override fun onError(requestId: String, code: Int, message: String) {
                close(InferenceFailed(code, message.ifBlank { "Inference failed with code $code" }))
            }

            override fun onProgress(requestId: String, percent: Int, stage: String) = Unit
        }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val service = IInsightService.Stub.asInterface(binder)
                serviceRef.set(service)
                runCatching {
                    requestIdRef.set(
                        service.requestInsight(
                            InsightContract.json.encodeToString(InsightRequest.serializer(), request),
                            callback,
                        ),
                    )
                }.onFailure { close(it) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceRef.set(null)
                // The service process died mid-generation. Surface it rather than hanging.
                close(
                    InferenceFailed(
                        LocalLlmError.INTERNAL,
                        "The model service stopped unexpectedly",
                    ),
                )
            }
        }

        val bound = runCatching {
            context.bindService(serviceIntent(), connection, Context.BIND_AUTO_CREATE)
        }.getOrElse { false }

        if (!bound) close(Unavailable("Could not bind the LocalLLM inference service"))

        awaitClose {
            requestIdRef.get()?.let { id -> runCatching { serviceRef.get()?.cancel(id) } }
            runCatching { context.unbindService(connection) }
        }
    }

    private suspend fun <T> withService(block: (IInsightService) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val result = runCatching { block(IInsightService.Stub.asInterface(binder)) }
                    runCatching { context.unbindService(this) }
                    if (continuation.isActive) continuation.resumeWith(result)
                }

                override fun onServiceDisconnected(name: ComponentName?) = Unit
            }

            val bound = runCatching {
                context.bindService(serviceIntent(), connection, Context.BIND_AUTO_CREATE)
            }.getOrElse { false }

            if (!bound) {
                continuation.failIfActive(
                    Unavailable("The LocalLLM app is not installed, or refused the connection"),
                )
            }

            continuation.invokeOnCancellation { runCatching { context.unbindService(connection) } }
        }

    private fun <T> CancellableContinuation<T>.failIfActive(error: Throwable) {
        if (isActive) resumeWith(Result.failure(error))
    }

    private fun serviceIntent(): Intent = Intent(ACTION_BIND_INFERENCE).setPackage(LOCALLLM_PACKAGE)

    companion object {
        const val LOCALLLM_PACKAGE = "com.noamv.localllm"
        const val ACTION_BIND_INFERENCE = "com.noamv.localllm.action.BIND_INFERENCE"
    }
}
