package com.noamv.localllm.client

import java.util.concurrent.atomic.AtomicReference

/**
 * Prevents a queued generation submission from beginning after its collector or deadline
 * has stopped the turn. A Binder call whose [runIfOpen] begin-CAS already won remains a
 * synchronous in-progress call and is cancelled by request ID when it returns.
 */
internal class GenerationSubmissionGate {
    private val state = AtomicReference(State.OPEN)

    val isStopped: Boolean get() = state.get() == State.STOPPED

    fun stop() {
        state.set(State.STOPPED)
    }

    /**
     * The successful CAS is the linearization point at which synchronous Binder submission
     * begins. If [stop] wins first, [block] is never invoked. If this CAS wins first, a later
     * stop cannot interrupt Binder, so the caller cancels the returned request ID instead.
     */
    fun <T> runIfOpen(block: () -> T): T? {
        if (!state.compareAndSet(State.OPEN, State.SUBMISSION_BEGUN)) return null
        return block()
    }

    private enum class State {
        OPEN,
        SUBMISSION_BEGUN,
        STOPPED,
    }
}
