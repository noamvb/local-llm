package com.noamv.localllm.service

import android.os.ParcelFileDescriptor
import com.noamv.localllm.contract.v3.DictationContractV3
import com.noamv.localllm.contract.v3.DictationError
import com.noamv.localllm.contract.v3.DictationRequest
import com.noamv.localllm.contract.v3.DictationResultFields
import com.noamv.localllm.speech.AudioFormatException
import com.noamv.localllm.speech.DictationEngine
import com.noamv.localllm.speech.SpeechModelCatalog
import com.noamv.localllm.v3.IDictationCallbackV3
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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

    private fun CoroutineScope.makeBinder(
        fake: FakeEngine,
        authorizer: (Int) -> String = { "com.noamv.inbox" },
        readAudio: (ParcelFileDescriptor?) -> FloatArray = { FloatArray(16_000) },
    ): DictationServiceV3Binder = DictationServiceV3Binder(
        scope = this,
        callerAuthorizer = authorizer,
        engine = fake,
        getCallingUid = { 1234 },
        readAudio = readAudio,
    )

    private fun requestJson(model: String = SpeechModelCatalog.BASE_EN.id): String =
        DictationContractV3.json.encodeToString(
            DictationRequest.serializer(),
            DictationRequest(model = model),
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
