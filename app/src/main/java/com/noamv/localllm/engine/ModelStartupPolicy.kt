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

    fun hasInstalledCandidate(): Boolean = candidates().any(isInstalled)

    fun recordSuccess(build: ModelBuild): Boolean = successfulBuildStore.write(build)
}
