package com.noamv.localllm.service

import com.noamv.localllm.contract.Fact
import com.noamv.localllm.contract.InsightRequest
import com.noamv.localllm.contract.InsightTask
import com.noamv.localllm.contract.LocalLlmError
import com.noamv.localllm.contract.Period
import com.noamv.localllm.engine.BackendInitializationException
import com.noamv.localllm.engine.GenerationOutputPolicyException
import com.noamv.localllm.engine.InferencePriority
import com.noamv.localllm.engine.ModelAcquisitionException
import com.noamv.localllm.engine.NoUsableBackendException
import com.noamv.localllm.model.InsufficientModelStorageException
import com.noamv.localllm.model.IncompleteModelDownloadException
import com.noamv.localllm.model.InvalidModelRangeException
import com.noamv.localllm.model.ModelCatalog
import com.noamv.localllm.model.ModelChecksumException
import com.noamv.localllm.model.ModelDownloadHttpException
import com.noamv.localllm.model.ModelDownloadResponseLimitException
import com.noamv.localllm.model.ModelDownloadTooLargeException
import com.noamv.localllm.model.ModelNetworkException
import com.noamv.localllm.model.ModelPromotionException
import com.noamv.localllm.model.ModelStorageException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ServiceFailureMapperTest {
    private val build = ModelCatalog.E2B_GPU

    @Test
    fun `maps cancellation OOM and unsupported backend to frozen v1 codes`() {
        assertFailure(CancellationException("cancel"), LocalLlmError.CANCELLED, retryable = true)
        assertFailure(
            RuntimeException("wrapper", OutOfMemoryError("secret native detail")),
            LocalLlmError.OUT_OF_MEMORY,
            retryable = true,
        )
        assertFailure(
            NoUsableBackendException(
                listOf(BackendInitializationException(build, IllegalStateException("driver"))),
            ),
            LocalLlmError.UNSUPPORTED_DEVICE,
            retryable = false,
        )
    }

    @Test
    fun `distinguishes network storage checksum and protocol acquisition categories`() {
        val network = mappedAcquisition(ModelNetworkException(build, IOException("host")))
        assertEquals(LocalLlmError.MODEL_NOT_READY, network.code)
        assertTrue(network.message.contains("network"))
        assertTrue(network.retryable)

        val storage = mappedAcquisition(ModelStorageException("/private/path"))
        assertEquals(LocalLlmError.MODEL_NOT_READY, storage.code)
        assertTrue(storage.message.contains("storage"))
        assertFalse(storage.message.contains("/private/path"))

        val noSpace = mappedAcquisition(
            InsufficientModelStorageException(100, 10, build),
        )
        assertEquals(LocalLlmError.MODEL_NOT_READY, noSpace.code)
        assertFalse(noSpace.retryable)
        assertTrue(noSpace.message.contains("free storage"))

        val checksum = mappedAcquisition(ModelChecksumException("expected", "actual", build))
        assertEquals(LocalLlmError.MODEL_NOT_READY, checksum.code)
        assertTrue(checksum.message.contains("verification"))
        assertFalse(checksum.message.contains("expected"))

        val protocolFailures = listOf(
            ModelDownloadHttpException(503, build),
            InvalidModelRangeException(null, 10, 100, build),
            ModelDownloadTooLargeException(100, 101, build),
            IncompleteModelDownloadException(100, 99, build),
            ModelDownloadResponseLimitException(8, build),
        )
        protocolFailures.forEach { error ->
            val protocol = mappedAcquisition(error)
            assertEquals(LocalLlmError.MODEL_NOT_READY, protocol.code)
            assertTrue(protocol.message.contains("incomplete or invalid"))
            assertTrue(protocol.retryable)
        }

        val promotion = mappedAcquisition(ModelPromotionException(build, IOException("rename")))
        assertEquals(LocalLlmError.MODEL_NOT_READY, promotion.code)
        assertTrue(promotion.message.contains("storage"))

        val generic = ServiceFailureMapper.map(ModelAcquisitionException(build, IOException("other")))
        assertEquals(LocalLlmError.MODEL_NOT_READY, generic.code)
        assertTrue(generic.message.contains("acquired"))
    }

    @Test
    fun `backend initialization remains retryable and distinct from exhausted backends`() {
        val failure = ServiceFailureMapper.map(
            BackendInitializationException(build, IllegalStateException("driver busy")),
        )
        assertEquals(LocalLlmError.MODEL_NOT_READY, failure.code)
        assertTrue(failure.retryable)
        assertTrue(failure.message.contains("initialize"))
        assertFalse(failure.message.contains("driver busy"))
    }

    @Test
    fun `output policy and unexpected failures are sanitized internal results`() {
        val output = ServiceFailureMapper.map(GenerationOutputPolicyException("42 secret words"))
        assertEquals(LocalLlmError.INTERNAL, output.code)
        assertTrue(output.message.contains("request limits"))
        assertFalse(output.message.contains("42"))

        val unexpected = ServiceFailureMapper.map(IllegalStateException("/data/user/private"))
        assertEquals(LocalLlmError.INTERNAL, unexpected.code)
        assertTrue(unexpected.retryable)
        assertFalse(unexpected.message.contains("/data/user/private"))
    }

    @Test
    fun `every v1 task has deterministic open-screen priority`() {
        InsightTask.entries.forEach { task ->
            val request = InsightRequest(
                clientId = "test",
                task = task,
                subject = "records",
                period = Period("period"),
                facts = listOf(Fact("Entries", "1")),
            )
            assertEquals(InferencePriority.OPEN_SCREEN, v1Priority(request))
        }
    }

    private fun mappedAcquisition(cause: Throwable): ServiceFailure =
        ServiceFailureMapper.map(ModelAcquisitionException(build, cause))

    private fun assertFailure(
        error: Throwable,
        code: Int,
        retryable: Boolean,
    ) {
        val failure = ServiceFailureMapper.map(error)
        assertEquals(code, failure.code)
        assertEquals(retryable, failure.retryable)
    }
}
