package com.noamv.localllm.client

/**
 * Runs an action exactly once after both a value and a request are present, regardless of
 * ordering. Used when flow cancellation can beat requestInsight() returning its ID.
 */
internal class DeferredOneShotAction<T>(
    private val action: (T) -> Unit,
) {
    private val lock = Any()
    private var value: T? = null
    private var hasValue = false
    private var requested = false
    private var delivered = false

    fun assign(value: T) {
        val toDeliver = synchronized(lock) {
            if (!hasValue) {
                this.value = value
                hasValue = true
            }
            takeIfReady()
        }
        toDeliver?.let(action)
    }

    fun request() {
        val toDeliver = synchronized(lock) {
            requested = true
            takeIfReady()
        }
        toDeliver?.let(action)
    }

    @Suppress("UNCHECKED_CAST")
    private fun takeIfReady(): T? {
        if (!requested || !hasValue || delivered) return null
        delivered = true
        return value as T
    }
}
