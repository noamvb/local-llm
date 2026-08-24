package com.noamv.localllm.engine

import com.noamv.localllm.model.ModelBuild
import com.noamv.localllm.model.ModelCatalog

/** Resolves startup candidates from durable success evidence and installed artifacts. */
internal class ModelStartupPolicy(
    private val preferredBuild: ModelBuild,
    private val successfulBuildStore: SuccessfulModelBuildStore,
    private val isInstalled: (ModelBuild) -> Boolean,
) {
    fun candidates(): List<ModelBuild> = ModelCatalog.startupOrder(
        primary = preferredBuild,
        lastSuccessfulBuildId = successfulBuildStore.readBuildId(),
        isInstalled = isInstalled,
    )

    /** Preparation may inspect only these candidates; missing builds are acquisition work. */
    fun installedCandidates(): List<ModelBuild> = candidates().filter(isInstalled)

    fun hasInstalledCandidate(): Boolean = installedCandidates().isNotEmpty()

    /**
     * Owner acquisition fetches one preferred artifact only when no compatible artifact
     * already exists. Backend failure never advances this into a fallback download.
     */
    fun ownerAcquisitionTarget(): ModelBuild? =
        preferredBuild.takeIf { installedCandidates().isEmpty() }

    fun recordSuccess(build: ModelBuild): Boolean = successfulBuildStore.write(build)
}
