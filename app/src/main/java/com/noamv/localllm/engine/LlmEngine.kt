package com.noamv.localllm.engine

import com.noamv.localllm.contract.EngineStatus
import com.noamv.localllm.contract.InsightRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The inference backend, behind an interface so the LiteRT-LM path and a possible
 * Gemini Nano path can be swapped without the service or clients changing.
 */
interface LlmEngine : AutoCloseable {

    val status: StateFlow<EngineStatus>

    /** Diagnostics for the manager screen. Not part of the cross-app contract. */
    val timings: StateFlow<EngineTimings>

    /**
     * Initialises from compatible artifacts already installed on this device.
     *
     * This method never performs acquisition or waits for a download. When no compatible
     * artifact is installed it fails with [ModelNotInstalledException]. Safe to call
     * repeatedly; concurrent initialisation callers await the same native work.
     */
    suspend fun prepare(onProgress: (percent: Int, stage: String) -> Unit = { _, _ -> })

    /**
     * Runs one request, emitting text fragments as they are produced.
     *
     * Implementations serialise generation: a second request waits rather than running
     * concurrently, because two simultaneous generations on a phone will exhaust memory.
     */
    fun generate(request: InsightRequest): Flow<String>

    /**
     * Releases the loaded native engine after any in-progress operation reaches a safe
     * boundary. The verified model artifact remains installed.
     */
    suspend fun unload()
}
