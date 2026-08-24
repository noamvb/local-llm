package com.noamv.localllm.transfer

/** Lets process-level critical trim synchronously reach the foreground service's Job. */
internal class ForegroundTransferCancellationRegistry {
    private val lock = Any()
    private var active: Registration? = null

    fun register(sessionId: Long, cancel: (TransferStopReason) -> Unit): AutoCloseable {
        val registration = Registration(sessionId, cancel)
        synchronized(lock) {
            check(active == null) { "Only one foreground model transfer may be registered." }
            active = registration
        }
        return AutoCloseable {
            synchronized(lock) {
                if (active === registration) active = null
            }
        }
    }

    fun cancel(reason: TransferStopReason): Boolean {
        val callback = synchronized(lock) { active?.cancel } ?: return false
        callback(reason)
        return true
    }

    private data class Registration(
        val sessionId: Long,
        val cancel: (TransferStopReason) -> Unit,
    )
}
