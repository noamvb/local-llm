package com.noamv.localllm.engine

import com.noamv.localllm.contract.EngineState
import com.noamv.localllm.contract.EngineStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineStatusCoordinatorTest {
    @Test
    fun `missing client preparation cannot erase concurrent owner download progress`() {
        val coordinator = coordinator()
        val acquisition = coordinator.beginAcquisition(downloading())
        coordinator.publishAcquisitionProgress(acquisition, 37)

        coordinator.publishRuntime(
            EngineStatus(
                state = EngineState.MODEL_MISSING,
                modelId = "preferred",
                detail = "not installed",
                modelDownloaded = false,
            ),
        )

        assertEquals(EngineState.DOWNLOADING, coordinator.status.value.state)
        assertEquals(37, coordinator.status.value.downloadPercent)
        assertFalse(coordinator.status.value.modelDownloaded)
    }

    @Test
    fun `runtime initialization wins after promotion and late acquisition finish cannot stomp it`() {
        val coordinator = coordinator()
        val acquisition = coordinator.beginAcquisition(downloading())
        coordinator.publishRuntime(
            EngineStatus(
                state = EngineState.INITIALISING,
                modelId = "preferred",
                detail = "Loading preferred",
                modelDownloaded = true,
            ),
        )

        coordinator.finishAcquisition(
            acquisition,
            EngineStatus(
                state = EngineState.MODEL_MISSING,
                modelId = "preferred",
                detail = "Downloaded; not loaded",
                modelDownloaded = true,
            ),
        )

        assertEquals(EngineState.INITIALISING, coordinator.status.value.state)
        assertEquals("Loading preferred", coordinator.status.value.detail)
        assertTrue(coordinator.status.value.modelDownloaded)
    }

    @Test
    fun `cancelled acquisition returns truthful missing status and keeps no partial as installed`() {
        val coordinator = coordinator()
        val acquisition = coordinator.beginAcquisition(downloading())

        coordinator.finishAcquisition(
            acquisition,
            EngineStatus(
                state = EngineState.MODEL_MISSING,
                modelId = "preferred",
                detail = "Download cancelled",
                modelDownloaded = false,
            ),
        )

        assertEquals(EngineState.MODEL_MISSING, coordinator.status.value.state)
        assertEquals("Download cancelled", coordinator.status.value.detail)
        assertFalse(coordinator.status.value.modelDownloaded)
    }

    private fun coordinator() = EngineStatusCoordinator(
        EngineStatus(
            state = EngineState.MODEL_MISSING,
            modelId = "preferred",
            modelDownloaded = false,
        ),
    )

    private fun downloading() = EngineStatus(
        state = EngineState.DOWNLOADING,
        modelId = "preferred",
        detail = "Downloading preferred",
        modelDownloaded = false,
    )
}
