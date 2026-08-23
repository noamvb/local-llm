package com.noamv.localllm.client

/**
 * Linearizes every terminal outcome of one service bind.
 *
 * Package verification may still be running when a timeout, cancellation, connection
 * callback, or Binder death arrives on another thread. Keeping the pending/delivered/
 * terminal transition behind one lock prevents a dead session from being published after
 * a failure has already won.
 */
internal class BindDeliveryGate<T : Any> {
    private val lock = Any()
    private var state: State<T> = State.Pending

    fun tryDeliver(value: T): Boolean = synchronized(lock) {
        if (state !== State.Pending) return@synchronized false
        state = State.Delivered(value)
        true
    }

    /** A deadline applies only while delivery is pending; it cannot kill a winning bind. */
    fun failPending(): Boolean = synchronized(lock) {
        if (state !== State.Pending) return@synchronized false
        state = State.Terminal
        true
    }

    /** Connection/death callbacks either fail the pending continuation or kill its session. */
    fun failConnection(): FailureTarget<T> = synchronized(lock) {
        when (val current = state) {
            State.Pending -> {
                state = State.Terminal
                FailureTarget.PendingContinuation
            }
            is State.Delivered -> {
                state = State.Terminal
                FailureTarget.DeliveredValue(current.value)
            }
            State.Terminal -> FailureTarget.Ignore
        }
    }

    /** Claims pending cancellation or returns the already delivered value that must close. */
    fun cancel(): T? = synchronized(lock) {
        when (val current = state) {
            State.Pending -> {
                state = State.Terminal
                null
            }
            is State.Delivered -> {
                state = State.Terminal
                current.value
            }
            State.Terminal -> null
        }
    }

    internal sealed interface FailureTarget<out T> {
        data object PendingContinuation : FailureTarget<Nothing>
        data class DeliveredValue<T>(val value: T) : FailureTarget<T>
        data object Ignore : FailureTarget<Nothing>
    }

    private sealed interface State<out T> {
        data object Pending : State<Nothing>
        data class Delivered<T>(val value: T) : State<T>
        data object Terminal : State<Nothing>
    }
}
