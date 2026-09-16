package com.noamv.localllm.speech

internal interface WhisperBackend {
    fun initContext(modelPath: String): Long
    fun freeContext(ctx: Long)
    fun transcribe(ctx: Long, samples: FloatArray, threads: Int, temperatureIncrement: Float): String
    fun timingsMs(ctx: Long): LongArray
}

class WhisperNative : WhisperBackend {
    external override fun initContext(modelPath: String): Long
    external override fun freeContext(ctx: Long)
    external override fun transcribe(
        ctx: Long,
        samples: FloatArray,
        threads: Int,
        temperatureIncrement: Float,
    ): String
    external override fun timingsMs(ctx: Long): LongArray

    companion object {
        init {
            System.loadLibrary("localllm_whisper")
        }
    }
}
