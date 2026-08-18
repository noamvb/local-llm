package com.noamv.localllm

import com.noamv.localllm.model.ModelBackend
import com.noamv.localllm.model.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    @Test
    fun `a Snapdragon 8 Elite gets the matching NPU build`() {
        val chosen = ModelCatalog.defaultFor("SM8750")
        assertEquals(ModelBackend.NPU, chosen.backend)
        assertEquals(ModelCatalog.E2B_NPU_SM8750.id, chosen.id)
    }

    @Test
    fun `an unknown chipset falls back to the portable GPU build`() {
        assertEquals(ModelCatalog.E2B_GPU.id, ModelCatalog.defaultFor("some-other-soc").id)
        assertEquals(ModelCatalog.E2B_GPU.id, ModelCatalog.defaultFor(null).id)
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
