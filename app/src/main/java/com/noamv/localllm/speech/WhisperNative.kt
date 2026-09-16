package com.noamv.localllm.speech

class WhisperNative {
    external fun initContext(modelPath: String): Long
    external fun freeContext(ctx: Long)
    external fun transcribe(ctx: Long, samples: FloatArray, threads: Int): String
    external fun timingsMs(ctx: Long): LongArray

    companion object {
        init {
            System.loadLibrary("localllm_whisper")
        }
    }
}
