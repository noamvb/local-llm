package com.noamv.localllm.transfer

import java.util.concurrent.atomic.AtomicLong

internal data class ActiveTransferSession(
    val id: Long,
    val policy: TransferNetworkPolicy,
)

internal sealed interface TransferStartDecision {
    data class Started(val session: ActiveTransferSession) : TransferStartDecision
    data class Coalesced(val session: ActiveTransferSession) : TransferStartDecision
}

/**
 * Pure ownership state for the one foreground transfer admitted in this process.
 *
 * Repeated starts never widen a running session's network policy. A terminal callback
 * from an old session cannot clear a later one because every transition is session-ID
 * checked under one lock.
 */
internal class ModelTransferSessionOwner {
    private val lock = Any()
    private var active: ActiveTransferSession? = null

    fun start(policy: TransferNetworkPolicy): TransferStartDecision = synchronized(lock) {
        active?.let { return TransferStartDecision.Coalesced(it) }
        val session = ActiveTransferSession(ProcessTransferSessionIds.next(), policy)
        active = session
        TransferStartDecision.Started(session)
    }

    fun active(): ActiveTransferSession? = synchronized(lock) { active }

    fun finish(sessionId: Long): Boolean = synchronized(lock) {
        if (active?.id != sessionId) return false
        active = null
        true
    }
}

/** Service recreation shares one ID domain while old in-process callbacks can survive. */
private object ProcessTransferSessionIds {
    private val nextId = AtomicLong(0L)

    fun next(): Long = nextId.incrementAndGet()
}
