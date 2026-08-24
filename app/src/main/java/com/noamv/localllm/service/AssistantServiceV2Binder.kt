package com.noamv.localllm.service

import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.noamv.localllm.contract.LocalLlmError
import com.noamv.localllm.contract.v2.AssistantCapabilities
import com.noamv.localllm.contract.v2.AssistantContractV2
import com.noamv.localllm.contract.v2.AssistantEvent
import com.noamv.localllm.contract.v2.AssistantTerminalResult
import com.noamv.localllm.contract.v2.AssistantTurnRequest
import com.noamv.localllm.contract.v2.HistoryPage
import com.noamv.localllm.contract.v2.HistoryQuery
import com.noamv.localllm.engine.InferencePriority
import com.noamv.localllm.engine.InferenceScheduler
import com.noamv.localllm.history.AssistantHistoryRepository
import com.noamv.localllm.orchestrator.AssistantOrchestratorV2
import com.noamv.localllm.privacy.AssistantAccessPolicy
import com.noamv.localllm.security.CallerAuthorizer
import com.noamv.localllm.v2.IAssistantCallbackV2
import com.noamv.localllm.v2.IAssistantServiceV2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class AssistantServiceV2Binder(
    private val scope: CoroutineScope,
    private val callerAuthorizer: (Int) -> String,
    private val accessPolicy: AssistantAccessPolicy,
    private val historyRepository: AssistantHistoryRepository,
    private val orchestrator: AssistantOrchestratorV2,
    private val scheduler: InferenceScheduler,
) : IAssistantServiceV2.Stub() {

    constructor(
        scope: CoroutineScope,
        callerAuthorizer: CallerAuthorizer,
        accessPolicy: AssistantAccessPolicy,
        historyRepository: AssistantHistoryRepository,
        orchestrator: AssistantOrchestratorV2,
        scheduler: InferenceScheduler,
    ) : this(
        scope = scope,
        callerAuthorizer = callerAuthorizer::enforceAuthorizedCaller,
        accessPolicy = accessPolicy,
        historyRepository = historyRepository,
        orchestrator = orchestrator,
        scheduler = scheduler,
    )

    private val inFlight = ConcurrentHashMap<String, InFlightV2Turn>()

    override fun getApiVersion(): Int {
        enforceCaller()
        return AssistantContractV2.VERSION
    }

    override fun getCapabilitiesJson(clientId: String?): String {
        enforceCaller()
        val caps = AssistantCapabilities(
            protocolVersion = AssistantContractV2.VERSION,
            grammarVersion = AssistantContractV2.GRAMMAR_VERSION,
            supportedModelRoles = listOf("ROUTER", "WRITER"),
            roleStates = mapOf("ROUTER" to "READY", "WRITER" to "READY"),
            supportsStructuredOutput = true,
            supportsStreamingDrafts = true,
            supportsHistory = true,
            providerVersions = mapOf("CANNSHEET" to 2, "POOP_SCHEDULE" to 2),
        )
        return AssistantContractV2.json.encodeToString(AssistantCapabilities.serializer(), caps)
    }

    override fun startTurn(requestJson: String, callback: IAssistantCallbackV2): String {
        val callerPackage = enforceCaller()

        val request = try {
            AssistantContractV2.json.decodeFromString(AssistantTurnRequest.serializer(), requestJson)
        } catch (_: Exception) {
            val fallbackId = UUID.randomUUID().toString()
            callback.safeError(fallbackId, LocalLlmError.INVALID_REQUEST, "Malformed AssistantTurnRequest JSON document", false)
            return fallbackId
        }

        val requestId = request.requestId.ifBlank { UUID.randomUUID().toString() }
        val record = InFlightV2Turn(
            requestId = requestId,
            callerUid = Binder.getCallingUid(),
            callback = callback,
        )

        val deathRecipient = IBinder.DeathRecipient {
            inFlight.remove(requestId)
            scheduler.cancel(requestId)
        }
        try {
            callback.asBinder().linkToDeath(deathRecipient, 0)
        } catch (_: RemoteException) {
            // Binder died before link
            return requestId
        }

        scope.launch {
            try {
                val result = orchestrator.executeTurn(
                    request = request.copy(initiatingClient = callerPackage),
                    onEvent = { event ->
                        val eventJson = AssistantContractV2.json.encodeToString(AssistantEvent.serializer(), event)
                        callback.safeEvent(requestId, eventJson)
                    },
                )
                val resultJson = AssistantContractV2.json.encodeToString(AssistantTerminalResult.serializer(), result)
                callback.safeComplete(requestId, resultJson)
            } catch (cancelled: CancellationException) {
                callback.safeError(requestId, LocalLlmError.CANCELLED, "Turn cancelled", false)
            } catch (error: Throwable) {
                Log.e(TAG, "Assistant turn failed", error)
                callback.safeError(requestId, LocalLlmError.INTERNAL, error.message ?: "Unknown error", true)
            } finally {
                inFlight.remove(requestId)
                runCatching { callback.asBinder().unlinkToDeath(deathRecipient, 0) }
            }
        }

        return requestId
    }

    override fun cancelTurn(requestId: String) {
        val callerUid = Binder.getCallingUid()
        enforceCaller()
        val record = inFlight[requestId] ?: return
        if (record.callerUid != callerUid) {
            throw SecurityException("Cannot cancel another client's assistant turn")
        }
        scheduler.cancel(requestId)
        inFlight.remove(requestId)
    }

    override fun getHistoryPage(queryJson: String): String {
        val callerPackage = enforceCaller()

        val query = try {
            AssistantContractV2.json.decodeFromString(HistoryQuery.serializer(), queryJson)
        } catch (_: Exception) {
            HistoryQuery()
        }

        val page = kotlinx.coroutines.runBlocking {
            historyRepository.getHistoryPage(query, clientFilter = callerPackage)
        }
        return AssistantContractV2.json.encodeToString(HistoryPage.serializer(), page)
    }

    private fun enforceCaller(): String =
        callerAuthorizer(Binder.getCallingUid())

    private fun IAssistantCallbackV2.safeEvent(requestId: String, eventJson: String) {
        runCatching { onEvent(requestId, eventJson) }
    }

    private fun IAssistantCallbackV2.safeComplete(requestId: String, resultJson: String) {
        runCatching { onComplete(requestId, resultJson) }
    }

    private fun IAssistantCallbackV2.safeError(requestId: String, code: Int, message: String, retryable: Boolean) {
        runCatching { onError(requestId, code, message, retryable) }
    }

    private class InFlightV2Turn(
        val requestId: String,
        val callerUid: Int,
        val callback: IAssistantCallbackV2,
    )

    companion object {
        private const val TAG = "AssistantServiceV2Binder"
    }
}
