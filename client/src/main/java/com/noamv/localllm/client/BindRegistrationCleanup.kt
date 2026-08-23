package com.noamv.localllm.client

/**
 * Defers unbinding until the synchronous bindService call has returned.
 *
 * Android registers the ServiceConnection as part of bindService, including some paths
 * where that call reports false. A deadline or cancellation may arrive immediately before
 * or while the call is in progress, so trying to unbind at that instant can be too early.
 * This owner remembers cleanup and delivers it exactly once after registration can exist.
 */
internal class BindRegistrationCleanup(
    private val unbind: () -> Unit,
) {
    private val lock = Any()
    private var bindCallFinished = false
    private var cleanupRequested = false
    private var unbindDelivered = false

    fun requestCleanup() {
        val shouldUnbind = synchronized(lock) {
            cleanupRequested = true
            claimUnbindIfReady()
        }
        if (shouldUnbind) unbind()
    }

    fun <T> runBindingCall(block: () -> T): T = try {
        block()
    } finally {
        markBindCallFinished()
    }

    private fun markBindCallFinished() {
        val shouldUnbind = synchronized(lock) {
            bindCallFinished = true
            claimUnbindIfReady()
        }
        if (shouldUnbind) unbind()
    }

    private fun claimUnbindIfReady(): Boolean {
        if (!bindCallFinished || !cleanupRequested || unbindDelivered) return false
        unbindDelivered = true
        return true
    }
}
