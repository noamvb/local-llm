package com.noamv.localllm.speech

import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test

class WhisperEngineTest {
    private lateinit var tempFilesDir: File

    @Before
    fun setUp() {
        tempFilesDir = Files.createTempDirectory("whisper-engine-test").toFile()
        File(tempFilesDir, "models/ggml-base.en.bin").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
    }

    @After
    fun tearDown() {
        tempFilesDir.deleteRecursively()
    }

    @Test
    fun transcriptionCapsNativeThreadsForRestrictedDevice() = runTest {
        val backend = RecordingBackend()
        WhisperEngine(
            context = object : ContextWrapper(null) {
                override fun getFilesDir(): File = tempFilesDir
            },
            native = backend,
        ).transcribe(SpeechModelCatalog.BASE_EN, FloatArray(16_000), {})

        assertEquals(4, backend.threads)
        assertEquals(0.0f, backend.temperatureIncrement)
    }

    private class RecordingBackend : WhisperBackend {
        var temperatureIncrement: Float? = null
        var threads: Int? = null

        override fun initContext(modelPath: String): Long = 1L
        override fun freeContext(ctx: Long) = Unit
        override fun transcribe(
            ctx: Long,
            samples: FloatArray,
            threads: Int,
            temperatureIncrement: Float,
        ): String {
            this.threads = threads
            this.temperatureIncrement = temperatureIncrement
            return "hello"
        }

        override fun timingsMs(ctx: Long): LongArray = longArrayOf(1L, 2L, 3L, 4L)
    }
}
