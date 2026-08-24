package com.noamv.localllm.engine

import com.noamv.localllm.model.ModelBuild
import com.noamv.localllm.model.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelStartupPolicyTest {

    @Test
    fun `successful fallback is persisted and reused by a recreated startup policy`() {
        val durableStore = InMemorySuccessfulBuildStore()
        val installed = mutableSetOf(ModelCatalog.E2B_GPU.id, ModelCatalog.E2B_CPU.id)
        val firstProcess = policy(durableStore, installed)

        assertTrue(firstProcess.recordSuccess(ModelCatalog.E2B_CPU))

        // A new policy instance represents a new LocalLLM process reading the same
        // SharedPreferences-backed value.
        val recreatedProcess = policy(durableStore, installed)
        assertEquals(ModelCatalog.E2B_CPU.id, recreatedProcess.candidates().first().id)
    }

    @Test
    fun `persisted fallback remains downloaded when the preferred artifact is absent`() {
        val durableStore = InMemorySuccessfulBuildStore(ModelCatalog.E2B_CPU.id)
        val recreatedProcess = policy(
            durableStore = durableStore,
            installed = setOf(ModelCatalog.E2B_CPU.id),
        )

        assertTrue(recreatedProcess.hasInstalledCandidate())
        assertEquals(ModelCatalog.E2B_CPU.id, recreatedProcess.candidates().first().id)
        assertEquals(
            listOf(ModelCatalog.E2B_CPU.id),
            recreatedProcess.installedCandidates().map { it.id },
        )
        assertNull(recreatedProcess.ownerAcquisitionTarget())
    }

    @Test
    fun `missing preparation has no candidates and owner acquisition targets only preferred`() {
        val startup = policy(InMemorySuccessfulBuildStore(), emptySet())

        assertTrue(startup.installedCandidates().isEmpty())
        assertEquals(ModelCatalog.E2B_GPU.id, startup.ownerAcquisitionTarget()?.id)
    }

    @Test
    fun `installed backend failure cannot turn a missing fallback into preparation work`() {
        val startup = policy(
            durableStore = InMemorySuccessfulBuildStore(),
            installed = setOf(ModelCatalog.E2B_GPU.id),
        )

        assertEquals(
            listOf(ModelCatalog.E2B_GPU.id),
            startup.installedCandidates().map { it.id },
        )
        assertNull(startup.ownerAcquisitionTarget())
    }

    private fun policy(
        durableStore: SuccessfulModelBuildStore,
        installed: Set<String>,
    ) = ModelStartupPolicy(
        preferredBuild = ModelCatalog.E2B_GPU,
        successfulBuildStore = durableStore,
        isInstalled = { it.id in installed },
    )

    private class InMemorySuccessfulBuildStore(
        private var buildId: String? = null,
    ) : SuccessfulModelBuildStore {
        override fun readBuildId(): String? = buildId

        override fun write(build: ModelBuild): Boolean {
            buildId = build.id
            return true
        }
    }
}
