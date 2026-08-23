package com.noamv.localllm.service

import com.noamv.localllm.engine.InferenceAdmission

/** Result of coordinating Binder lifetime with synchronous scheduler registration. */
internal sealed interface RegistrationResult {
    data class Admitted(val admission: InferenceAdmission.Accepted) : RegistrationResult
    data object Busy : RegistrationResult
    data object Closed : RegistrationResult
    data object CancelledBeforeAdmission : RegistrationResult
}

/**
 * Linearizes callback death/cancellation with scheduler registration.
 *
 * [InferenceAdmission] is synchronous, but cancellation can arrive on another Binder thread
 * immediately before or while that call is registering its lazy job. Holding one monitor
 * across registration gives those events a definite order: cancellation either prevents
 * submission entirely, or observes an admitted entry and removes/cancels that exact entry.
 */
internal class RequestLifecycleGate(
    private val requestId: String,
    private val cancelAdmitted: (String) -> Boolean,
) {
    private val lock = Any()
    private var state = State.REGISTERING
    private var cleanupClaimed = false

    fun register(submit: () -> InferenceAdmission): RegistrationResult {
        var cancelAfterRegistration = false
        val result = synchronized(lock) {
            when (state) {
                State.CANCELLED_BEFORE_ADMISSION ->
                    return@synchronized RegistrationResult.CancelledBeforeAdmission
                State.REGISTERING -> Unit
                State.ADMITTED, State.CANCEL_REQUESTED, State.TERMINAL ->
                    error("Request was registered more than once")
            }

            when (val admission = submit()) {
                is InferenceAdmission.Accepted -> {
                    when (state) {
                        State.REGISTERING -> state = State.ADMITTED
                        State.CANCELLED_BEFORE_ADMISSION -> {
                            state = State.CANCEL_REQUESTED
                            cancelAfterRegistration = true
                        }
                        // A synchronous/unconfined test scheduler may finish before
                        // submit() returns. Preserve that already-terminal result.
                        State.TERMINAL -> Unit
                        State.ADMITTED, State.CANCEL_REQUESTED ->
                            error("Request admission state corrupted")
                    }
                    RegistrationResult.Admitted(admission)
                }
                InferenceAdmission.Busy -> {
                    if (state == State.CANCELLED_BEFORE_ADMISSION) {
                        RegistrationResult.CancelledBeforeAdmission
                    } else {
                        state = State.TERMINAL
                        RegistrationResult.Busy
                    }
                }
                InferenceAdmission.Closed -> {
                    if (state == State.CANCELLED_BEFORE_ADMISSION) {
                        RegistrationResult.CancelledBeforeAdmission
                    } else {
                        state = State.TERMINAL
                        RegistrationResult.Closed
                    }
                }
            }
        }
        if (cancelAfterRegistration) cancelAdmitted(requestId)
        return result
    }

    /**
     * Requests cancellation exactly once. Returns true when this call changed lifetime.
     */
    fun cancel(onCancellationClaimed: () -> Unit = {}): Boolean {
        val cancelScheduler = synchronized(lock) {
            when (state) {
                State.REGISTERING -> {
                    state = State.CANCELLED_BEFORE_ADMISSION
                    onCancellationClaimed()
                    false
                }
                State.ADMITTED -> {
                    state = State.CANCEL_REQUESTED
                    onCancellationClaimed()
                    true
                }
                State.CANCELLED_BEFORE_ADMISSION,
                State.CANCEL_REQUESTED,
                State.TERMINAL,
                -> return false
            }
        }
        if (cancelScheduler) cancelAdmitted(requestId)
        return true
    }

    /** Scheduler completion/expiry owns final cleanup, and may race any caller thread. */
    fun terminal(): Boolean = synchronized(lock) {
        if (cleanupClaimed) {
            false
        } else {
            cleanupClaimed = true
            state = State.TERMINAL
            true
        }
    }

    private enum class State {
        REGISTERING,
        CANCELLED_BEFORE_ADMISSION,
        ADMITTED,
        CANCEL_REQUESTED,
        TERMINAL,
    }
}

/**
 * One owner for nonterminal callback admission and the sole terminal callback.
 *
 * Binder callback methods are oneway, but multiple service/scheduler threads can still
 * invoke the proxy concurrently. The monitor prevents a token/progress callback from
 * being admitted after completion or failure and prevents two terminal results.
 */
internal class ServiceCallbackGate(
    private val deliverToken: (String) -> Boolean,
    private val deliverProgress: (Int, String) -> Boolean,
    private val deliverComplete: (String) -> Boolean,
    private val deliverError: (Int, String) -> Boolean,
) {
    private val lock = Any()
    private var terminal = false

    fun token(fragment: String): Boolean = synchronized(lock) {
        !terminal && deliverToken(fragment)
    }

    fun progress(percent: Int, stage: String): Boolean = synchronized(lock) {
        !terminal && deliverProgress(percent, stage)
    }

    fun complete(text: String): Boolean = synchronized(lock) {
        if (terminal) return false
        terminal = true
        deliverComplete(text)
    }

    fun error(code: Int, message: String): Boolean = synchronized(lock) {
        if (terminal) return false
        terminal = true
        deliverError(code, message)
    }

    val isTerminal: Boolean
        get() = synchronized(lock) { terminal }
}
