package com.noamv.localllm.engine

/**
 * Owner-only boundary for installing a model artifact.
 *
 * Binder service, prewarm, and generation code depend only on [LlmEngine], whose
 * preparation contract is installed-artifact-only. Keeping acquisition in a separate
 * type makes a network transfer impossible to reach from those paths by accident.
 */
internal interface ModelAcquirer {
    /** Installs the preferred artifact only when no compatible artifact is installed. */
    suspend fun acquirePreferredArtifact()
}
