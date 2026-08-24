package com.noamv.localllm.engine

import com.noamv.localllm.model.ModelBuild
import com.noamv.localllm.transfer.TransferNetworkBlockReason
import okhttp3.Call

internal enum class ArtifactAcquisitionStage {
    DOWNLOADING,
    VERIFYING,
    INSTALLING,
}

internal data class ArtifactAcquisitionProgress(
    val build: ModelBuild,
    val stage: ArtifactAcquisitionStage,
    val availableBytes: Long,
    val totalBytes: Long,
    val transferredThisRunBytes: Long,
)

internal data class ArtifactAcquisitionByteSnapshot(
    val build: ModelBuild,
    val availableBytes: Long,
    val transferredThisRunBytes: Long,
    val promotionCommitted: Boolean,
)

internal data class ModelAcquisitionTransport(
    val callFactory: Call.Factory,
    val validateNetwork: () -> Unit,
    val terminalNetworkBlockReason: () -> TransferNetworkBlockReason?,
    val commitPromotion: ((() -> Unit) -> Boolean) = { promotion ->
        promotion()
        true
    },
)

/**
 * Owner-only boundary for installing a model artifact.
 *
 * Binder service, prewarm, and generation code depend only on [LlmEngine], whose
 * preparation contract is installed-artifact-only. Keeping acquisition in a separate
 * type makes a network transfer impossible to reach from those paths by accident.
 */
internal interface ModelAcquirer {
    /** Installs the preferred artifact only when no compatible artifact is installed. */
    suspend fun acquirePreferredArtifact(
        transport: ModelAcquisitionTransport,
        onProgress: (ArtifactAcquisitionProgress) -> Unit = {},
        onTerminalSnapshot: (ArtifactAcquisitionByteSnapshot) -> Unit = {},
    )
}
