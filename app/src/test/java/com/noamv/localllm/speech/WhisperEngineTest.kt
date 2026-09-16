package com.noamv.localllm.speech

import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import com.noamv.localllm.model.ModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
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

    @Test
    fun `deleting a speech model while dictation holds the engine lock fails`() = runTest {
        val modelFile = File(tempFilesDir, "models/${SpeechModelCatalog.BASE_EN.fileName}")
        val store = ModelStore(File(tempFilesDir, "models"))
        val engine = WhisperEngine(
            context = object : ContextWrapper(null) {
                override fun getFilesDir(): File = tempFilesDir
            },
            native = RecordingBackend(),
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch {
            engine.tryWithOperationLock {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val deleted = engine.tryWithOperationLock { store.delete(SpeechModelCatalog.BASE_EN) }

        assertEquals(false, deleted)
        assertEquals(true, modelFile.exists())
        release.complete(Unit)
        holder.join()
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
