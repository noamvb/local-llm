package com.noamv.localllm.transfer

internal data class TransferNotificationPresentation(
    val title: String,
    val content: String,
    val expandedText: String,
    val progress: Int,
    val indeterminate: Boolean,
    val showCancel: Boolean,
)

internal fun notificationPresentation(status: ModelTransferStatus): TransferNotificationPresentation {
    val stage = when (status.phase) {
        ModelTransferPhase.IDLE -> "Idle"
        ModelTransferPhase.STARTING -> "Starting"
        ModelTransferPhase.DOWNLOADING -> "Downloading"
        ModelTransferPhase.VERIFYING -> "Verifying"
        ModelTransferPhase.INSTALLING -> "Installing"
        ModelTransferPhase.COMPLETED -> "Complete"
        ModelTransferPhase.CANCELLED -> "Cancelled"
        ModelTransferPhase.POLICY_BLOCKED -> "Network policy not met"
        ModelTransferPhase.TIMED_OUT -> "Timed out"
        ModelTransferPhase.FAILED -> "Transfer failed"
    }
    val bytes = status.bytes
    val sizeKnown = bytes.expectedBytes > 0L
    val progress = if (bytes.expectedBytes > 0L) {
        ((bytes.availableBytes * 100L) / bytes.expectedBytes).toInt().coerceIn(0, 100)
    } else {
        0
    }
    val model = status.descriptor.modelName
        .filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
        .take(96)
    val expanded = buildString {
        append("Role: ${status.descriptor.role.displayName}\n")
        append("Model: $model\n")
        append("Stage: $stage")
        if (sizeKnown) {
            append("\nExpected: ${bytes.expectedBytes} bytes\n")
            append("Partial at start: ${bytes.partialBytesAtStart} bytes\n")
            append("Transferred this run: ${bytes.transferredThisRunBytes} bytes\n")
            append("Available: ${bytes.availableBytes} bytes\n")
            append("Remaining: ${bytes.remainingBytes} bytes")
        }
        status.failureCategory?.let { append("\nFailure category: ${it.name.lowercase()}") }
    }
    return TransferNotificationPresentation(
        title = "${status.descriptor.role.displayName} model transfer",
        content = if (sizeKnown) {
            "$stage - ${bytes.remainingBytes} bytes remaining"
        } else {
            "$stage transfer"
        },
        expandedText = expanded,
        progress = progress,
        indeterminate = !sizeKnown || status.phase in setOf(
            ModelTransferPhase.STARTING,
            ModelTransferPhase.VERIFYING,
            ModelTransferPhase.INSTALLING,
        ),
        showCancel = status.isActive,
    )
}
