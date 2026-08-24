package com.noamv.localllm.transfer

import com.noamv.localllm.model.IncompleteModelDownloadException
import com.noamv.localllm.model.InsufficientModelStorageException
import com.noamv.localllm.model.InvalidModelRangeException
import com.noamv.localllm.model.ModelChecksumException
import com.noamv.localllm.model.ModelDownloadHttpException
import com.noamv.localllm.model.ModelDownloadResponseLimitException
import com.noamv.localllm.model.ModelDownloadTooLargeException
import com.noamv.localllm.model.ModelNetworkException
import com.noamv.localllm.model.ModelPromotionException
import com.noamv.localllm.model.ModelStorageException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class ModelRole(val displayName: String) {
    ROUTER("Router"),
    WRITER("Writer"),
}

internal data class ModelTransferDescriptor(
    val role: ModelRole,
    val modelId: String,
    val modelName: String,
    val expectedBytes: Long,
)

internal enum class TransferNetworkPolicy {
    UNMETERED_WIFI,
    ALLOW_METERED_ONCE,
}

internal enum class TransferNetworkTransport {
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    OTHER,
}

internal data class TransferNetworkSnapshot(
    val validated: Boolean,
    val internetCapable: Boolean,
    val metered: Boolean,
    val transports: Set<TransferNetworkTransport>,
)

internal enum class TransferNetworkBlockReason {
    NO_VALIDATED_NETWORK,
    REQUIRES_UNMETERED_WIFI,
}

internal class TransferNetworkPolicyException(
    val reason: TransferNetworkBlockReason,
) : IOException("The explicit model-transfer network policy is no longer satisfied.")

internal class TransferNetworkMonitorException(cause: RuntimeException) :
    IOException("The model-transfer network infrastructure failed.", cause)

internal sealed interface TransferNetworkDecision {
    data object Allowed : TransferNetworkDecision
    data class Blocked(val reason: TransferNetworkBlockReason) : TransferNetworkDecision
}

internal fun evaluateTransferNetwork(
    policy: TransferNetworkPolicy,
    snapshot: TransferNetworkSnapshot,
): TransferNetworkDecision {
    if (!snapshot.validated || !snapshot.internetCapable) {
        return TransferNetworkDecision.Blocked(TransferNetworkBlockReason.NO_VALIDATED_NETWORK)
    }
    if (policy == TransferNetworkPolicy.ALLOW_METERED_ONCE) return TransferNetworkDecision.Allowed
    return if (!snapshot.metered && TransferNetworkTransport.WIFI in snapshot.transports) {
        TransferNetworkDecision.Allowed
    } else {
        TransferNetworkDecision.Blocked(TransferNetworkBlockReason.REQUIRES_UNMETERED_WIFI)
    }
}

internal enum class ModelTransferPhase {
    IDLE,
    STARTING,
    DOWNLOADING,
    VERIFYING,
    INSTALLING,
    COMPLETED,
    CANCELLED,
    POLICY_BLOCKED,
    TIMED_OUT,
    FAILED,
}

internal enum class TransferAcquisitionPath {
    LOCAL_VERIFY_AND_PROMOTE,
    NETWORK_REQUIRED,
}

/** A complete retained partial takes ModelStore's existing no-request promotion path. */
internal fun acquisitionPath(status: ModelTransferStatus): TransferAcquisitionPath =
    if (status.bytes.expectedBytes > 0L &&
        status.bytes.availableBytes == status.bytes.expectedBytes
    ) {
        TransferAcquisitionPath.LOCAL_VERIFY_AND_PROMOTE
    } else {
        TransferAcquisitionPath.NETWORK_REQUIRED
    }

internal data class TransferByteSnapshot(
    val expectedBytes: Long,
    val partialBytesAtStart: Long,
    val availableBytes: Long,
    val transferredThisRunBytes: Long,
    val remainingBytes: Long,
) {
    companion object {
        fun create(
            expectedBytes: Long,
            partialBytesAtStart: Long,
            availableBytes: Long,
            transferredThisRunBytes: Long =
                (availableBytes - partialBytesAtStart).coerceAtLeast(0L),
        ): TransferByteSnapshot {
            val expected = expectedBytes.coerceAtLeast(0L)
            val partial = partialBytesAtStart.coerceIn(0L, expected)
            val available = availableBytes.coerceIn(0L, expected)
            return TransferByteSnapshot(
                expectedBytes = expected,
                partialBytesAtStart = partial,
                availableBytes = available,
                transferredThisRunBytes = transferredThisRunBytes.coerceAtLeast(0L),
                remainingBytes = (expected - available).coerceAtLeast(0L),
            )
        }
    }
}

internal enum class TransferStopReason {
    OWNER_CANCELLED,
    NETWORK_POLICY_LOST,
    CRITICAL_MEMORY,
    SERVICE_DESTROYED,
    SERVICE_TIMEOUT,
}

internal enum class PromotionCommitState {
    ACTIVE,
    CANCELLED,
    COMMITTING,
    COMMITTED,
    FAILED,
}

/**
 * Arbitrates the only irreversible transfer step against cancellation.
 *
 * The short synchronized section decides who owns the outcome. File promotion then runs
 * outside the lock so a platform timeout watchdog never waits on file I/O.
 * Once promotion has claimed COMMITTING, cancellation cannot publish a contradictory
 * CANCELLED terminal; the promotion path finishes as COMMITTED or FAILED instead.
 */
internal class PromotionCommitArbiter {
    private val lock = Any()
    private var current = PromotionCommitState.ACTIVE

    fun cancel(): Boolean = synchronized(lock) {
        when (current) {
            PromotionCommitState.ACTIVE -> {
                current = PromotionCommitState.CANCELLED
                true
            }
            PromotionCommitState.CANCELLED -> true
            PromotionCommitState.COMMITTING,
            PromotionCommitState.COMMITTED,
            PromotionCommitState.FAILED,
            -> false
        }
    }

    fun commit(promotion: () -> Unit): Boolean {
        synchronized(lock) {
            if (current != PromotionCommitState.ACTIVE) return false
            current = PromotionCommitState.COMMITTING
        }
        try {
            promotion()
        } catch (error: Throwable) {
            synchronized(lock) {
                if (current == PromotionCommitState.COMMITTING) {
                    current = PromotionCommitState.FAILED
                }
            }
            throw error
        }
        synchronized(lock) {
            check(current == PromotionCommitState.COMMITTING)
            current = PromotionCommitState.COMMITTED
        }
        return true
    }

    fun state(): PromotionCommitState = synchronized(lock) { current }
}

internal enum class ModelTransferFailureCategory {
    NETWORK,
    STORAGE,
    INSUFFICIENT_STORAGE,
    CHECKSUM,
    PROTOCOL,
    TIMEOUT,
    CANCELLED,
    MEMORY,
    INTERNAL,
}

internal data class ModelTransferStatus(
    val sessionId: Long = 0L,
    val descriptor: ModelTransferDescriptor,
    val policy: TransferNetworkPolicy = TransferNetworkPolicy.UNMETERED_WIFI,
    val phase: ModelTransferPhase = ModelTransferPhase.IDLE,
    val bytes: TransferByteSnapshot = TransferByteSnapshot.create(
        expectedBytes = descriptor.expectedBytes,
        partialBytesAtStart = 0L,
        availableBytes = 0L,
    ),
    val blockReason: TransferNetworkBlockReason? = null,
    val stopReason: TransferStopReason? = null,
    val failureCategory: ModelTransferFailureCategory? = null,
) {
    val isActive: Boolean
        get() = phase in setOf(
            ModelTransferPhase.STARTING,
            ModelTransferPhase.DOWNLOADING,
            ModelTransferPhase.VERIFYING,
            ModelTransferPhase.INSTALLING,
        )
}

internal class ModelTransferStatusCoordinator(
    private val descriptor: ModelTransferDescriptor,
) {
    private val lock = Any()
    private val _status = MutableStateFlow(ModelTransferStatus(descriptor = descriptor))
    val status: StateFlow<ModelTransferStatus> = _status.asStateFlow()

    fun begin(
        sessionId: Long,
        policy: TransferNetworkPolicy,
        activeDescriptor: ModelTransferDescriptor = descriptor,
        partialBytes: Long,
    ) {
        synchronized(lock) {
            _status.value = ModelTransferStatus(
                sessionId = sessionId,
                descriptor = activeDescriptor,
                policy = policy,
                phase = ModelTransferPhase.STARTING,
                bytes = TransferByteSnapshot.create(
                    expectedBytes = activeDescriptor.expectedBytes,
                    partialBytesAtStart = partialBytes,
                    availableBytes = partialBytes,
                ),
            )
        }
    }

    fun publish(
        sessionId: Long,
        phase: ModelTransferPhase,
        availableBytes: Long = status.value.bytes.availableBytes,
        transferredThisRunBytes: Long = status.value.bytes.transferredThisRunBytes,
    ) {
        synchronized(lock) {
            val current = _status.value.takeIf {
                it.sessionId == sessionId && it.isActive
            } ?: return
            _status.value = current.copy(
                phase = phase,
                bytes = TransferByteSnapshot.create(
                    expectedBytes = current.descriptor.expectedBytes,
                    partialBytesAtStart = current.bytes.partialBytesAtStart,
                    availableBytes = availableBytes,
                    transferredThisRunBytes = transferredThisRunBytes,
                ),
                blockReason = null,
                stopReason = null,
                failureCategory = null,
            )
        }
    }

    /** Refreshes byte truth for this exact run without changing its active/terminal phase. */
    fun refreshStorageBytes(
        sessionId: Long,
        availableBytes: Long,
        transferredThisRunBytes: Long,
    ) {
        synchronized(lock) {
            val current = _status.value.takeIf {
                it.sessionId == sessionId && it.phase != ModelTransferPhase.IDLE
            } ?: return
            _status.value = current.copy(
                bytes = TransferByteSnapshot.create(
                    expectedBytes = current.descriptor.expectedBytes,
                    partialBytesAtStart = current.bytes.partialBytesAtStart,
                    availableBytes = availableBytes,
                    transferredThisRunBytes = transferredThisRunBytes,
                ),
            )
        }
    }

    /**
     * Records the irreversible promotion winner with exact storage bytes. This may replace
     * an earlier same-session timeout/cancel/block callback because the shared commit
     * arbiter proves that promotion claimed the outcome first.
     */
    fun completeCommittedPromotion(
        sessionId: Long,
        availableBytes: Long,
        transferredThisRunBytes: Long,
    ) {
        synchronized(lock) {
            val current = _status.value.takeIf {
                it.sessionId == sessionId && it.phase != ModelTransferPhase.IDLE
            } ?: return
            _status.value = current.copy(
                phase = ModelTransferPhase.COMPLETED,
                bytes = TransferByteSnapshot.create(
                    expectedBytes = current.descriptor.expectedBytes,
                    partialBytesAtStart = current.bytes.partialBytesAtStart,
                    availableBytes = availableBytes,
                    transferredThisRunBytes = transferredThisRunBytes,
                ),
                blockReason = null,
                stopReason = null,
                failureCategory = null,
            )
        }
    }

    fun block(sessionId: Long, reason: TransferNetworkBlockReason) {
        updateTerminal(sessionId) { it.copy(
            phase = ModelTransferPhase.POLICY_BLOCKED,
            blockReason = reason,
        ) }
    }

    fun cancel(sessionId: Long, reason: TransferStopReason) {
        updateTerminal(sessionId) { current ->
            current.copy(
                phase = if (reason == TransferStopReason.SERVICE_TIMEOUT) {
                    ModelTransferPhase.TIMED_OUT
                } else {
                    ModelTransferPhase.CANCELLED
                },
                stopReason = reason,
            )
        }
    }

    fun fail(sessionId: Long, error: Throwable) {
        updateTerminal(sessionId) { it.copy(
            phase = ModelTransferPhase.FAILED,
            failureCategory = classifyTransferFailure(error),
        ) }
    }

    fun failSetup(
        sessionId: Long,
        policy: TransferNetworkPolicy,
        error: Throwable,
    ) {
        synchronized(lock) {
            val current = _status.value.takeIf { it.sessionId == sessionId } ?:
                ModelTransferStatus(
                    sessionId = sessionId,
                    descriptor = descriptor,
                    policy = policy,
                    phase = ModelTransferPhase.STARTING,
                )
            _status.value = current.copy(
                phase = ModelTransferPhase.FAILED,
                blockReason = null,
                stopReason = null,
                failureCategory = classifyTransferFailure(error),
            )
        }
    }

    private fun updateTerminal(
        sessionId: Long,
        transform: (ModelTransferStatus) -> ModelTransferStatus,
    ) {
        synchronized(lock) {
            val current = _status.value.takeIf {
                it.sessionId == sessionId && it.isActive
            } ?: return
            _status.value = transform(current)
        }
    }
}

internal fun classifyTransferFailure(error: Throwable): ModelTransferFailureCategory {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth++ < MAX_FAILURE_CAUSE_DEPTH) {
        when (current) {
            is TimeoutCancellationException -> return ModelTransferFailureCategory.TIMEOUT
            is CancellationException -> return ModelTransferFailureCategory.CANCELLED
            is OutOfMemoryError -> return ModelTransferFailureCategory.MEMORY
            is InsufficientModelStorageException ->
                return ModelTransferFailureCategory.INSUFFICIENT_STORAGE
            is ModelChecksumException -> return ModelTransferFailureCategory.CHECKSUM
            is ModelStorageException,
            is ModelPromotionException,
            -> return ModelTransferFailureCategory.STORAGE
            is ModelNetworkException,
            is TransferNetworkPolicyException,
            is TransferNetworkMonitorException,
            -> return ModelTransferFailureCategory.NETWORK
            is ModelDownloadHttpException,
            is InvalidModelRangeException,
            is ModelDownloadTooLargeException,
            is IncompleteModelDownloadException,
            is ModelDownloadResponseLimitException,
            -> return ModelTransferFailureCategory.PROTOCOL
        }
        current = current.cause
    }
    return ModelTransferFailureCategory.INTERNAL
}

internal fun findTransferNetworkBlockReason(error: Throwable): TransferNetworkBlockReason? {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth++ < MAX_FAILURE_CAUSE_DEPTH) {
        if (current is TransferNetworkPolicyException) return current.reason
        current = current.cause
    }
    return null
}

internal fun resolveTransferNetworkBlockReason(
    error: Throwable,
    leaseTerminalReason: TransferNetworkBlockReason?,
): TransferNetworkBlockReason? = leaseTerminalReason ?: findTransferNetworkBlockReason(error)

private const val MAX_FAILURE_CAUSE_DEPTH = 64
