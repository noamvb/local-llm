package com.noamv.localllm.service

import android.os.ParcelFileDescriptor
import com.noamv.localllm.contract.EngineState
import com.noamv.localllm.contract.EngineStatus
import com.noamv.localllm.contract.InsightRequest
import com.noamv.localllm.contract.v3.DictationContractV3
import com.noamv.localllm.contract.v3.DictationError
import com.noamv.localllm.contract.v3.DictationRequest
import com.noamv.localllm.contract.v3.DictationResultFields
import com.noamv.localllm.contract.v3.StructureRequest
import com.noamv.localllm.engine.EngineTimings
import com.noamv.localllm.engine.LlmEngine
import com.noamv.localllm.engine.StructurePrompt
import com.noamv.localllm.speech.AudioFormatException
import com.noamv.localllm.speech.DictationEngine
import com.noamv.localllm.speech.SpeechModelCatalog
import com.noamv.localllm.v3.IDictationCallbackV3
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DictationServiceV3BinderTest {
    @Test
    fun unauthorizedUidThrowsAndNeverInvokesEngine() = runTest {
        val fake = FakeEngine()
        val binder = makeBinder(fake, authorizer = { throw SecurityException("denied") })
        assertThrows(SecurityException::class.java) {
            binder.transcribe(null, requestJson(), RecordingCallback())
        }
        assertEquals(0, fake.calls)
    }

    @Test
    fun malformedJsonReportsBadRequest() = runTest {
        val callback = RecordingCallback()
        makeBinder(FakeEngine()).transcribe(null, "{", callback)
        advanceUntilIdle()
        assertEquals(DictationError.BAD_REQUEST, callback.errorCode)
    }

    @Test
    fun unknownModelReportsModelNotInstalled() = runTest {
        val callback = RecordingCallback()
        makeBinder(FakeEngine()).transcribe(null, requestJson(model = "unknown"), callback)
        advanceUntilIdle()
        assertEquals(DictationError.MODEL_NOT_INSTALLED, callback.errorCode)
    }

    @Test
    fun stereoWavReportsAudioFormat() = runTest {
        val callback = RecordingCallback()
        makeBinder(FakeEngine(), readAudio = { throw AudioFormatException("channels 2; expected 1") })
            .transcribe(null, requestJson(), callback)
        advanceUntilIdle()
        assertEquals(DictationError.AUDIO_FORMAT, callback.errorCode)
    }

    @Test
    fun secondCallWhileFirstIsBlockedReportsRetryableBusy() = runTest {
        val fake = FakeEngine(block = true)
        val service = makeBinder(fake)
        service.transcribe(null, requestJson(), RecordingCallback())
        val callback = RecordingCallback()
        service.transcribe(null, requestJson(), callback)
        assertEquals(DictationError.BUSY, callback.errorCode)
        assertTrue(callback.retryable)
        fake.release()
        advanceUntilIdle()
    }

    @Test
    fun cancelReportsCancelled() = runTest {
        val fake = FakeEngine(block = true)
        val service = makeBinder(fake)
        val callback = RecordingCallback()
        val requestId = service.transcribe(null, requestJson(), callback)
        service.cancel(requestId)
        advanceUntilIdle()
        assertEquals(DictationError.CANCELLED, callback.errorCode)
    }

    @Test
    fun engineExceptionReportsEngineFailure() = runTest {
        val callback = RecordingCallback()
        makeBinder(FakeEngine(failure = IllegalStateException("broken"))).transcribe(null, requestJson(), callback)
        advanceUntilIdle()
        assertEquals(DictationError.ENGINE_FAILURE, callback.errorCode)
    }

    @Test
    fun successReportsExactEncodedResultJson() = runTest {
        val callback = RecordingCallback()
        val result = DictationResultFields("ignored", "Hello", SpeechModelCatalog.BASE_EN.id, 1.0)
        makeBinder(FakeEngine(result = result)).transcribe(null, requestJson(), callback)
        advanceUntilIdle()
        assertEquals(
            "{\"requestId\":\"${callback.requestId}\",\"text\":\"Hello\",\"model\":\"whisper-base-en\",\"audioSeconds\":1.0,\"timingsMs\":{\"load\":0,\"encode\":0,\"decode\":0,\"total\":0}}",
            callback.resultJson,
        )
    }

    @Test
    fun unauthorizedStructureThrowsAndNeverInvokesLlmEngine() = runTest {
        val llmEngine = FakeLlmEngine()
        val binder = makeBinder(FakeEngine(), authorizer = { throw SecurityException("denied") }, llmEngine = llmEngine)

        assertThrows(SecurityException::class.java) {
            binder.structure(structureRequestJson(), RecordingCallback())
        }
        assertEquals(0, llmEngine.calls)
    }

    @Test
    fun emptyStructureTextReportsBadRequest() = runTest {
        val callback = RecordingCallback()
        makeBinder(FakeEngine(), llmEngine = FakeLlmEngine()).structure(structureRequestJson(text = ""), callback)
        advanceUntilIdle()
        assertEquals(DictationError.BAD_REQUEST, callback.errorCode)
    }

    @Test
    fun unknownStructureKindReportsBadRequest() = runTest {
        val callback = RecordingCallback()
        makeBinder(FakeEngine(), llmEngine = FakeLlmEngine()).structure(
            structureRequestJson(kinds = listOf("todo", "event")),
            callback,
        )
        advanceUntilIdle()
        assertEquals(DictationError.BAD_REQUEST, callback.errorCode)
    }

    @Test
    fun structureSuccessRoundsConfidenceAndReportsExactJson() = runTest {
        val callback = RecordingCallback()
        makeBinder(
            FakeEngine(),
            llmEngine = FakeLlmEngine("{\"kind\":\"todo\",\"text\":\"Buy milk\",\"confidence\":0.9123}"),
        ).structure(structureRequestJson(), callback)
        advanceUntilIdle()

        assertEquals(
            "{\"requestId\":\"${callback.requestId}\",\"kind\":\"todo\",\"text\":\"Buy milk\",\"confidence\":0.91,\"model\":\"fake\",\"timingsMs\":{\"total\":0}}",
            callback.resultJson,
        )
    }

    @Test
    fun nonJsonStructureResultReportsEngineFailure() = runTest {
        val callback = RecordingCallback()
        makeBinder(FakeEngine(), llmEngine = FakeLlmEngine("not-json")).structure(structureRequestJson(), callback)
        advanceUntilIdle()
        assertEquals(DictationError.ENGINE_FAILURE, callback.errorCode)
    }

    @Test
    fun structureWhileTranscribeIsInFlightReportsRetryableBusy() = runTest {
        val fake = FakeEngine(block = true)
        val service = makeBinder(fake, llmEngine = FakeLlmEngine())
        service.transcribe(null, requestJson(), RecordingCallback())
        val callback = RecordingCallback()

        service.structure(structureRequestJson(), callback)

        assertEquals(DictationError.BUSY, callback.errorCode)
        assertTrue(callback.retryable)
        fake.release()
        advanceUntilIdle()
    }

    private fun CoroutineScope.makeBinder(
        fake: FakeEngine,
        authorizer: (Int) -> String = { "com.noamv.inbox" },
        readAudio: (ParcelFileDescriptor?) -> FloatArray = { FloatArray(16_000) },
        llmEngine: FakeLlmEngine = FakeLlmEngine(),
    ): DictationServiceV3Binder = DictationServiceV3Binder(
        scope = this,
        callerAuthorizer = authorizer,
        engine = fake,
        llmEngine = llmEngine,
        getCallingUid = { 1234 },
        readAudio = readAudio,
    )

    private fun requestJson(model: String = SpeechModelCatalog.BASE_EN.id): String =
        DictationContractV3.json.encodeToString(
            DictationRequest.serializer(),
            DictationRequest(model = model),
        )

    private fun structureRequestJson(
        text: String = "remind me to buy milk",
        kinds: List<String> = listOf("todo", "note"),
    ): String = DictationContractV3.json.encodeToString(
        StructureRequest.serializer(),
        StructureRequest(text = text, kinds = kinds),
    )

    private class FakeEngine(
        private val result: DictationResultFields = DictationResultFields("fake", "Hello", SpeechModelCatalog.BASE_EN.id, 1.0),
        private val block: Boolean = false,
        private val failure: Throwable? = null,
    ) : DictationEngine {
        var calls = 0
        private val release = CompletableDeferred<Unit>()

        fun release() {
            release.complete(Unit)
        }

        override suspend fun transcribe(
            build: com.noamv.localllm.speech.SpeechModelBuild,
            samples: FloatArray,
            onProgress: (Int) -> Unit,
        ): DictationResultFields {
            calls++
            failure?.let { throw it }
            onProgress(50)
            if (block) release.await()
            return result.copy(model = build.id)
        }
    }

    private class FakeLlmEngine(
        private val result: String = "{\"kind\":\"todo\",\"text\":\"Buy milk\",\"confidence\":0.92}",
    ) : LlmEngine {
        override val status = MutableStateFlow(
            EngineStatus(
                state = EngineState.READY,
                modelId = "fake",
                modelDownloaded = true,
            ),
        )
        override val timings = MutableStateFlow(EngineTimings())
        var calls = 0

        override suspend fun prepare(onProgress: (Int, String) -> Unit) = Unit
        override fun generate(request: InsightRequest): Flow<String> = flowOf()
        override suspend fun structure(prompt: StructurePrompt): String {
            calls++
            return result
        }
        override suspend fun unload() = Unit
        override fun close() = Unit
    }

    private class RecordingCallback : IDictationCallbackV3.Stub() {
        var requestId: String? = null
        var resultJson: String? = null
        var errorCode: Int? = null
        var retryable: Boolean = false

        override fun onProgress(requestId: String?, percent: Int, stage: String?) = Unit
        override fun onComplete(requestId: String?, resultJson: String?) {
            this.requestId = requestId
            this.resultJson = resultJson
        }
        override fun onError(requestId: String?, errorCode: Int, message: String?, retryable: Boolean) {
            this.requestId = requestId
            this.errorCode = errorCode
            this.retryable = retryable
        }
    }
}
