package com.noamv.localllm.client

import com.noamv.localllm.contract.LocalLlmError
import java.util.ArrayDeque

/** Raw events received over the oneway Binder callback. */
internal sealed interface CallbackEvent {
    data class Token(val value: String) : CallbackEvent
    data class Complete(val text: String, val resultJson: String?) : CallbackEvent
    data class Error(val code: Int, val message: String) : CallbackEvent
    data class Progress(val percent: Int, val stage: String) : CallbackEvent
}

/**
 * Owns every observable generation-delivery transition.
 *
 * Callback admission, request-ID validation, first-response admission, event delivery,
 * terminal selection and flow closure all run under one lock. A timeout or Binder death
 * therefore cannot close the conflated channel between an admitted event's trySend and its
 * terminal decision, and a first response and its deadline have exactly one winner.
 */
internal class GenerationEventGate(
    private val onFirstResponse: () -> Unit,
    private val deliverEvent: (GenerationEvent) -> Boolean,
    private val closeFlow: (Throwable?) -> Unit,
    private val onTerminalSelected: () -> Unit,
    private val maxPreAssignmentEvents: Int = MAX_PRE_ASSIGNMENT_EVENTS,
) {
    init {
        require(maxPreAssignmentEvents > 0)
    }

    private val lock = Any()
    private val queued = ArrayDeque<CallbackEvent>()
    private val draft = StringBuilder()
    private var expectedRequestId: String? = null
    private var provisionalRequestId: String? = null
    private var firstResponseSeen = false
    private var lastDraft: String? = null

    @Volatile
    private var terminalState: TerminalState? = null

    val isTerminal: Boolean
        get() = terminalState != null

    val serverFinished: Boolean
        get() = terminalState == TerminalState.SERVER_FINISHED

    val requiresRemoteCancellation: Boolean
        get() = when (terminalState) {
            TerminalState.CLIENT_FAILURE,
            TerminalState.COLLECTOR_CANCELLED,
            -> true
            TerminalState.SERVER_FINISHED,
            null,
            -> false
        }

    fun accept(requestId: String, event: CallbackEvent) {
        synchronized(lock) {
            if (terminalState != null) return

            val known = expectedRequestId
            val correlationFailure = when {
                known == null && provisionalRequestId == null -> {
                    provisionalRequestId = requestId
                    null
                }
                known == null && provisionalRequestId != requestId ->
                    protocolFailure("Callback request IDs changed before assignment")
                known != null && known != requestId ->
                    protocolFailure("Callback request ID did not match the assigned request")
                else -> null
            }
            if (correlationFailure != null) {
                terminateFailureLocked(correlationFailure, fromServer = false)
                return
            }

            if (known == null && queued.size >= maxPreAssignmentEvents) {
                terminateFailureLocked(
                    protocolFailure("Too many callbacks arrived before request ID assignment"),
                    fromServer = false,
                )
                return
            }

            if (!firstResponseSeen) {
                firstResponseSeen = true
                onFirstResponse()
            }

            if (known == null) {
                queued.addLast(event)
            } else {
                processLocked(event)
            }
        }
    }

    fun assignRequestId(requestId: String) {
        synchronized(lock) {
            if (terminalState != null) return
            when {
                requestId.isBlank() -> {
                    terminateFailureLocked(
                        protocolFailure("The service returned an empty request ID"),
                        fromServer = false,
                    )
                    return
                }
                provisionalRequestId != null && provisionalRequestId != requestId -> {
                    terminateFailureLocked(
                        protocolFailure("Callback request ID did not match the returned request ID"),
                        fromServer = false,
                    )
                    return
                }
            }

            expectedRequestId = requestId
            while (terminalState == null && queued.isNotEmpty()) {
                processLocked(queued.removeFirst())
            }
        }
    }

    /** Total deadline, bind death, negotiation failure, or another external terminal. */
    fun fail(error: Throwable): Boolean = synchronized(lock) {
        if (terminalState != null) return@synchronized false
        terminateFailureLocked(error, fromServer = false)
        true
    }

    /** First-response deadline: it loses once a valid callback has been admitted. */
    fun failIfNoFirstResponse(error: Throwable): Boolean = synchronized(lock) {
        if (terminalState != null || firstResponseSeen) return@synchronized false
        terminateFailureLocked(error, fromServer = false)
        true
    }

    /** Collector cancellation already owns channel closure; claim only cleanup state here. */
    fun cancelCollector(): Boolean = synchronized(lock) {
        if (terminalState != null) return@synchronized false
        terminalState = TerminalState.COLLECTOR_CANCELLED
        queued.clear()
        onTerminalSelected()
        true
    }

    private fun processLocked(event: CallbackEvent) {
        when (event) {
            is CallbackEvent.Token -> {
                draft.append(event.value)
                val snapshot = draft.toString()
                if (snapshot != lastDraft && deliverNonterminalLocked(GenerationEvent.Draft(snapshot))) {
                    lastDraft = snapshot
                }
            }
            is CallbackEvent.Complete -> {
                if (event.resultJson != null) {
                    terminateFailureLocked(
                        protocolFailure("Contract v1 received an unexpected structured result"),
                        fromServer = false,
                    )
                } else {
                    // onComplete.text is authoritative and is the one terminal value. The
                    // terminal claim precedes trySend, so no timeout can overwrite it.
                    terminateCompleteLocked(event.text)
                }
            }
            is CallbackEvent.Error -> terminateFailureLocked(
                LocalLlmClient.InferenceFailed(
                    event.code,
                    event.message.ifBlank { "Inference failed with code ${event.code}" },
                ),
                fromServer = true,
            )
            is CallbackEvent.Progress -> deliverNonterminalLocked(
                GenerationEvent.Progress(event.percent, event.stage),
            )
        }
    }

    private fun deliverNonterminalLocked(event: GenerationEvent): Boolean {
        if (runCatching { deliverEvent(event) }.getOrDefault(false)) return true
        terminateFailureLocked(deliveryFailure(), fromServer = false)
        return false
    }

    private fun terminateCompleteLocked(text: String) {
        if (terminalState != null) return
        terminalState = TerminalState.SERVER_FINISHED
        queued.clear()
        onTerminalSelected()

        val delivered = runCatching {
            deliverEvent(GenerationEvent.Complete(text))
        }.getOrDefault(false)
        closeFlow(if (delivered) null else deliveryFailure())
    }

    private fun terminateFailureLocked(error: Throwable, fromServer: Boolean) {
        if (terminalState != null) return
        terminalState = if (fromServer) {
            TerminalState.SERVER_FINISHED
        } else {
            TerminalState.CLIENT_FAILURE
        }
        queued.clear()
        onTerminalSelected()

        val delivered = runCatching {
            deliverEvent(GenerationEvent.Failure(error))
        }.getOrDefault(false)
        closeFlow(if (delivered) null else error)
    }

    private fun deliveryFailure() = LocalLlmClient.InferenceFailed(
        LocalLlmError.INTERNAL,
        "The client could not accept a generation event",
    )

    private fun protocolFailure(message: String) = LocalLlmClient.InferenceFailed(
        LocalLlmError.INTERNAL,
        "LocalLLM protocol error: $message",
    )

    private companion object {
        const val MAX_PRE_ASSIGNMENT_EVENTS = 64
    }

    private enum class TerminalState {
        SERVER_FINISHED,
        CLIENT_FAILURE,
        COLLECTOR_CANCELLED,
    }
}
