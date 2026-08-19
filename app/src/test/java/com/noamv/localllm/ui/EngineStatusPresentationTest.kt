package com.noamv.localllm.ui

import com.noamv.localllm.contract.EngineState
import com.noamv.localllm.contract.EngineStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MODEL_MISSING means "no engine is loaded", not "no model file". These tests exist
 * because that distinction was lost on a real device: the owner saw MODEL_MISSING over
 * 2 GB of verified, downloaded model and concluded the app had deleted it.
 */
class EngineStatusPresentationTest {

    private fun status(
        state: EngineState,
        modelDownloaded: Boolean = false,
        detail: String = "",
    ) = EngineStatus(state = state, modelDownloaded = modelDownloaded, detail = detail)

    @Test
    fun `MODEL_MISSING with a downloaded file reads as not loaded, not as missing`() {
        val label = engineStateLabel(status(EngineState.MODEL_MISSING, modelDownloaded = true))
        assertEquals("Downloaded, not loaded", label)
    }

    @Test
    fun `MODEL_MISSING with no file on disk reads as no model downloaded`() {
        val label = engineStateLabel(status(EngineState.MODEL_MISSING, modelDownloaded = false))
        assertEquals("No model downloaded", label)
    }

    @Test
    fun `the other states never mention download regardless of modelDownloaded`() {
        assertEquals("Ready", engineStateLabel(status(EngineState.READY, modelDownloaded = false)))
        assertEquals("Downloading", engineStateLabel(status(EngineState.DOWNLOADING, modelDownloaded = false)))
        assertEquals("Loading", engineStateLabel(status(EngineState.INITIALISING, modelDownloaded = true)))
        assertEquals("Could not start", engineStateLabel(status(EngineState.UNSUPPORTED, modelDownloaded = false)))
    }

    @Test
    fun `a failure to start still says whether the file survived`() {
        assertEquals(
            "Could not start (model is on this device)",
            engineStateLabel(status(EngineState.UNSUPPORTED, modelDownloaded = true)),
        )
    }

    /**
     * The detail strings here are the ones LiteRtEngine actually publishes. Asserting
     * against invented values would have hidden the duplication these cases exist for.
     */
    @Test
    fun `a detail that restates the state does not produce a stutter`() {
        assertEquals("Ready", engineStatusText(status(EngineState.READY, detail = "Ready")))
        assertEquals(
            "Downloading Gemma 4 E2B (GPU)",
            engineStatusText(status(EngineState.DOWNLOADING, detail = "Downloading Gemma 4 E2B (GPU)")),
        )
        assertEquals(
            "Loading Gemma 4 E2B (GPU)",
            engineStatusText(status(EngineState.INITIALISING, detail = "Loading Gemma 4 E2B (GPU)")),
        )
    }

    @Test
    fun `a detail that adds something is kept alongside the state`() {
        assertEquals(
            "Downloaded, not loaded — Closed",
            engineStatusText(status(EngineState.MODEL_MISSING, modelDownloaded = true, detail = "Closed")),
        )
        assertEquals("Ready", engineStatusText(status(EngineState.READY, detail = "")))
    }

    @Test
    fun `the model file line never depends on whether an engine is loaded`() {
        EngineState.entries.forEach { state ->
            assertEquals(
                "state=$state",
                "on this device",
                modelFileText(status(state, modelDownloaded = true)),
            )
            assertEquals(
                "state=$state",
                "not downloaded",
                modelFileText(status(state, modelDownloaded = false)),
            )
        }
    }

    @Test
    fun `the button only offers to download when there is really nothing on disk`() {
        assertEquals(
            "Reload model",
            prepareButtonLabel(status(EngineState.READY, modelDownloaded = true)),
        )
        assertEquals(
            "Load model",
            prepareButtonLabel(status(EngineState.MODEL_MISSING, modelDownloaded = true)),
        )
        assertEquals(
            "Download and load model",
            prepareButtonLabel(status(EngineState.MODEL_MISSING, modelDownloaded = false)),
        )
    }

    @Test
    fun `a downloaded file never offers to download it again, in any non-READY state`() {
        val nonReadyStates = EngineState.entries.filter { it != EngineState.READY }
        nonReadyStates.forEach { state ->
            assertEquals(
                "state=$state",
                "Load model",
                prepareButtonLabel(status(state, modelDownloaded = true)),
            )
        }
    }
}
