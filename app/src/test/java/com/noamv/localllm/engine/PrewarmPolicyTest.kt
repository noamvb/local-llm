package com.noamv.localllm.engine

import com.noamv.localllm.contract.EngineState
import com.noamv.localllm.contract.EngineStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrewarmPolicyTest {

    private fun status(
        state: EngineState,
        modelDownloaded: Boolean = false,
    ) = EngineStatus(state = state, modelDownloaded = modelDownloaded)

    @Test
    fun `prewarm fires for MODEL_MISSING when the file is on disk`() {
        assertTrue(shouldPrewarmOnBind(status(EngineState.MODEL_MISSING, modelDownloaded = true)))
    }

    @Test
    fun `prewarm never downloads when the model file is not on disk`() {
        assertFalse(shouldPrewarmOnBind(status(EngineState.MODEL_MISSING, modelDownloaded = false)))
    }

    @Test
    fun `prewarm does not fire for any other state even with model downloaded`() {
        val otherStates = EngineState.entries.filter { it != EngineState.MODEL_MISSING }
        otherStates.forEach { state ->
            assertFalse("state=$state", shouldPrewarmOnBind(status(state, modelDownloaded = true)))
            assertFalse("state=$state (no download)", shouldPrewarmOnBind(status(state, modelDownloaded = false)))
        }
    }

    @Test
    fun `prewarm does not fire for UNSUPPORTED even with model downloaded`() {
        assertFalse(shouldPrewarmOnBind(status(EngineState.UNSUPPORTED, modelDownloaded = true)))
    }
}
