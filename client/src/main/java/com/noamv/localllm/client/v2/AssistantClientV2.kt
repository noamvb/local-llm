package com.noamv.localllm.client.v2

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.noamv.localllm.client.TrustedServiceResolver
import com.noamv.localllm.contract.LocalLlmError
import com.noamv.localllm.contract.v2.AssistantCapabilities
import com.noamv.localllm.contract.v2.AssistantContractV2
import com.noamv.localllm.contract.v2.AssistantEvent
import com.noamv.localllm.contract.v2.AssistantTerminalResult
import com.noamv.localllm.contract.v2.AssistantTurnRequest
import com.noamv.localllm.contract.v2.HistoryPage
import com.noamv.localllm.contract.v2.HistoryQuery
import com.noamv.localllm.v2.IAssistantCallbackV2
import com.noamv.localllm.v2.IAssistantServiceV2
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface AssistantTurnEvent {
    data class Progress(val event: AssistantEvent) : AssistantTurnEvent
    data class Complete(val result: AssistantTerminalResult) : AssistantTurnEvent
    data class Error(val code: Int, val message: String, val retryable: Boolean = false) : AssistantTurnEvent
}

class AssistantClientV2(
    context: Context,
    private val bindTimeoutMillis: Long = 5_000L,
) {
    private val appContext = context.applicationContext ?: context
    private val resolver = TrustedServiceResolver(appContext)

    class Unavailable(message: String, cause: Throwable? = null) : Exception(message, cause)
    class InferenceFailed(val code: Int, message: String) : Exception(message)

    fun isInstalled(): Boolean = runCatching { resolver.resolve() }.isSuccess

    fun submitTurn(request: AssistantTurnRequest): Flow<AssistantTurnEvent> = callbackFlow {
        val terminalState = AtomicBoolean(false)
        val session = try {
            bindSession(
                onDeath = { error ->
                    if (terminalState.compareAndSet(false, true)) {
                        trySend(AssistantTurnEvent.Error(LocalLlmError.INTERNAL, error.message ?: "Service died"))
                        close()
                    }
                },
            )
        } catch (e: Exception) {
            trySend(AssistantTurnEvent.Error(LocalLlmError.MODEL_NOT_READY, e.message ?: "Failed to bind to Assistant service"))
            close()
            return@callbackFlow
        }

        val apiVersion = try {
            session.service.apiVersion
        } catch (e: Exception) {
            -1
        }
        if (apiVersion != AssistantContractV2.VERSION) {
            if (terminalState.compareAndSet(false, true)) {
                trySend(AssistantTurnEvent.Error(LocalLlmError.INVALID_REQUEST, "Incompatible Assistant API version: expected ${AssistantContractV2.VERSION}, got $apiVersion"))
                close()
            }
            session.unbind()
            return@callbackFlow
        }

        val requestJson = AssistantContractV2.json.encodeToString(AssistantTurnRequest.serializer(), request)
        val callback = object : IAssistantCallbackV2.Stub() {
            override fun onEvent(requestId: String?, eventJson: String?) {
                if (requestId != request.requestId || terminalState.get()) return
                if (eventJson != null) {
                    try {
                        val event = AssistantContractV2.json.decodeFromString(AssistantEvent.serializer(), eventJson)
                        trySend(AssistantTurnEvent.Progress(event))
                    } catch (_: Exception) {}
                }
            }

            override fun onComplete(requestId: String?, resultJson: String?) {
                if (requestId != request.requestId) return
                if (!terminalState.compareAndSet(false, true)) return
                if (resultJson != null) {
                    try {
                        val result = AssistantContractV2.json.decodeFromString(AssistantTerminalResult.serializer(), resultJson)
                        trySend(AssistantTurnEvent.Complete(result))
                    } catch (ex: Exception) {
                        trySend(AssistantTurnEvent.Error(LocalLlmError.INTERNAL, "Malformed result: ${ex.message}"))
                    }
                } else {
                    trySend(AssistantTurnEvent.Error(LocalLlmError.INTERNAL, "LocalLLM returned null terminal result"))
                }
                close()
            }

            override fun onError(requestId: String?, errorCode: Int, message: String?, retryable: Boolean) {
                if (requestId != null && requestId != request.requestId) return
                if (!terminalState.compareAndSet(false, true)) return
                trySend(AssistantTurnEvent.Error(errorCode, message ?: "Assistant error", retryable))
                close()
            }
        }

        try {
            session.service.startTurn(requestJson, callback)
        } catch (e: Exception) {
            if (terminalState.compareAndSet(false, true)) {
                trySend(AssistantTurnEvent.Error(LocalLlmError.INTERNAL, e.message ?: "Failed to start turn"))
                close()
            }
        }

        awaitClose {
            try {
                session.service.cancelTurn(request.requestId)
            } catch (_: Exception) {}
            session.unbind()
        }
    }.buffer(Channel.UNLIMITED)

    suspend fun getCapabilities(clientId: String = appContext.packageName): AssistantCapabilities? {
        val session = runCatching { bindSession() }.getOrNull() ?: return null
        return try {
            val json = session.service.getCapabilitiesJson(clientId) ?: return null
            AssistantContractV2.json.decodeFromString(AssistantCapabilities.serializer(), json)
        } catch (_: Exception) {
            null
        } finally {
            session.unbind()
        }
    }

    suspend fun getHistory(query: HistoryQuery): HistoryPage? {
        val session = runCatching { bindSession() }.getOrNull() ?: return null
        return try {
            val queryJson = AssistantContractV2.json.encodeToString(HistoryQuery.serializer(), query)
            val json = session.service.getHistoryPage(queryJson) ?: return null
            AssistantContractV2.json.decodeFromString(HistoryPage.serializer(), json)
        } catch (_: Exception) {
            null
        } finally {
            session.unbind()
        }
    }

    private suspend fun bindSession(onDeath: ((Throwable) -> Unit)? = null): BoundSession =
        withTimeout(bindTimeoutMillis) {
            val resolved = resolver.resolve()
            val intent = Intent("com.noamv.localllm.v2.action.BIND_ASSISTANT").apply {
                component = ComponentName(resolved.packageName, "com.noamv.localllm.service.InferenceService")
            }

            suspendCancellableCoroutine { cont ->
                val unbindOnce = AtomicBoolean(false)
                val deathRecipientRef = AtomicReference<Pair<IBinder, IBinder.DeathRecipient>?>(null)
                lateinit var connection: ServiceConnection

                fun cleanup() {
                    deathRecipientRef.getAndSet(null)?.let { (b, r) ->
                        runCatching { b.unlinkToDeath(r, 0) }
                    }
                    if (!unbindOnce.getAndSet(true)) {
                        runCatching { appContext.unbindService(connection) }
                    }
                }

                fun connectionLost(error: Throwable) {
                    cleanup()
                    if (cont.isActive) {
                        cont.resumeWithException(error)
                    } else {
                        onDeath?.invoke(error)
                    }
                }

                connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        try {
                            resolver.verifyConnected(name)
                            if (binder == null) {
                                connectionLost(Unavailable("Assistant service returned null Binder"))
                                return
                            }

                            val service = IAssistantServiceV2.Stub.asInterface(binder)
                                ?: throw Unavailable("Assistant service returned invalid Binder")

                            val recipient = IBinder.DeathRecipient {
                                connectionLost(Unavailable("Assistant service process died"))
                            }
                            binder.linkToDeath(recipient, 0)
                            deathRecipientRef.set(binder to recipient)

                            if (cont.isActive) {
                                cont.resume(BoundSession(service, ::cleanup))
                            } else {
                                cleanup()
                            }
                        } catch (e: Throwable) {
                            connectionLost(Unavailable("Failed to verify or initialize Assistant connection", e))
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        connectionLost(Unavailable("The Assistant service disconnected"))
                    }

                    override fun onNullBinding(name: ComponentName?) {
                        connectionLost(Unavailable("Assistant service returned a null binding"))
                    }

                    override fun onBindingDied(name: ComponentName?) {
                        connectionLost(Unavailable("The Assistant service binding died"))
                    }
                }

                val bound = try {
                    appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                } catch (sec: SecurityException) {
                    cleanup()
                    if (cont.isActive) cont.resumeWithException(Unavailable("Permission denied binding to assistant service", sec))
                    return@suspendCancellableCoroutine
                }

                if (!bound) {
                    cleanup()
                    if (!unbindOnce.getAndSet(true)) {
                        runCatching { appContext.unbindService(connection) }
                    }
                    if (cont.isActive) cont.resumeWithException(Unavailable("bindService returned false for Assistant service"))
                    return@suspendCancellableCoroutine
                }

                cont.invokeOnCancellation {
                    cleanup()
                }
            }
        }

    private class BoundSession(
        val service: IAssistantServiceV2,
        val unbind: () -> Unit,
    )
}
