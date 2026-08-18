package com.noamv.localllm

import com.noamv.localllm.model.ModelBackend
import com.noamv.localllm.model.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    @Test
    fun `a Snapdragon 8 Elite gets the NPU build only when dispatch libraries are present`() {
        val withLibraries = ModelCatalog.defaultFor("SM8750", npuDispatchAvailable = true)
        assertEquals(ModelBackend.NPU, withLibraries.backend)
        assertEquals(ModelCatalog.E2B_NPU_SM8750.id, withLibraries.id)
    }

    @Test
    fun `a matching chipset without dispatch libraries still gets the GPU build`() {
        // Verified on a Galaxy Z Fold 7: selecting NPU without the vendor libraries fails
        // during inference warm-up with "No usable Dispatch runtime found", several
        // seconds after the model appears to load.
        val chosen = ModelCatalog.defaultFor("SM8750", npuDispatchAvailable = false)
        assertEquals(ModelCatalog.E2B_GPU.id, chosen.id)
    }

    @Test
    fun `an unknown chipset falls back to the portable GPU build`() {
        assertEquals(ModelCatalog.E2B_GPU.id, ModelCatalog.defaultFor("some-other-soc", true).id)
        assertEquals(ModelCatalog.E2B_GPU.id, ModelCatalog.defaultFor(null, true).id)
    }

    @Test
    fun `the fallback chain starts with the primary and ends on CPU`() {
        val chain = ModelCatalog.fallbacksFor(ModelCatalog.E2B_NPU_SM8750)
        assertEquals(ModelCatalog.E2B_NPU_SM8750.id, chain.first().id)
        assertEquals(listOf(ModelCatalog.E2B_GPU.id, ModelCatalog.E2B_CPU.id), chain.drop(1).map { it.id })
        assertEquals("no duplicates", chain.size, chain.map { it.id }.toSet().size)
    }

    @Test
    fun `a GPU primary is not repeated in its own fallback chain`() {
        val chain = ModelCatalog.fallbacksFor(ModelCatalog.E2B_GPU)
        assertEquals(listOf(ModelCatalog.E2B_GPU.id, ModelCatalog.E2B_CPU.id), chain.map { it.id })
    }

    @Test
    fun `every build has a plausible digest and size`() {
        ModelCatalog.all.forEach { build ->
            assertEquals("${build.id} digest length", 64, build.sha256.length)
            assertTrue("${build.id} size", build.sizeBytes > 1_000_000_000L)
            assertTrue("${build.id} url", build.url.startsWith("https://huggingface.co/"))
        }
    }

    @Test
    fun `model ids are unique`() {
        assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.id }.toSet().size)
    }
}
