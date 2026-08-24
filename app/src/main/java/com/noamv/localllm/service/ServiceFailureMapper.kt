package com.noamv.localllm.service

import com.noamv.localllm.contract.LocalLlmError
import com.noamv.localllm.engine.BackendInitializationException
import com.noamv.localllm.engine.GenerationOutputPolicyException
import com.noamv.localllm.engine.ModelAcquisitionException
import com.noamv.localllm.engine.ModelNotInstalledException
import com.noamv.localllm.engine.NoUsableBackendException
import com.noamv.localllm.model.IncompleteModelDownloadException
import com.noamv.localllm.model.InsufficientModelStorageException
import com.noamv.localllm.model.InvalidModelRangeException
import com.noamv.localllm.model.ModelChecksumException
import com.noamv.localllm.model.ModelDownloadHttpException
import com.noamv.localllm.model.ModelDownloadResponseLimitException
import com.noamv.localllm.model.ModelDownloadTooLargeException
import com.noamv.localllm.model.ModelNetworkException
import com.noamv.localllm.model.ModelPromotionException
import com.noamv.localllm.model.ModelStorageException
import kotlinx.coroutines.CancellationException

/** Service-owned safe terminal error; [retryable] is diagnostic because v1 has no wire bit. */
internal data class ServiceFailure(
    val code: Int,
    val message: String,
    val retryable: Boolean,
)

/** Maps internal exception detail to the seven frozen v1 result codes without leaking paths. */
internal object ServiceFailureMapper {
    fun map(error: Throwable): ServiceFailure {
        val categories = FailureCategories.from(error)
        return when {
            categories.cancelled -> ServiceFailure(
                code = LocalLlmError.CANCELLED,
                message = "Request cancelled.",
                retryable = true,
            )
            categories.outOfMemory -> ServiceFailure(
                code = LocalLlmError.OUT_OF_MEMORY,
                message = "The model ran out of memory. Retry after closing other apps.",
                retryable = true,
            )
            categories.unsupportedBackend -> ServiceFailure(
                code = LocalLlmError.UNSUPPORTED_DEVICE,
                message = "No supported model backend is available on this device.",
                retryable = false,
            )
            categories.modelNotInstalled -> ServiceFailure(
                code = LocalLlmError.MODEL_NOT_READY,
                message = "No compatible model is installed. Open LocalLLM to download it.",
                retryable = false,
            )
            categories.insufficientStorage -> ServiceFailure(
                code = LocalLlmError.MODEL_NOT_READY,
                message = "There is not enough free storage to prepare the model.",
                retryable = false,
            )
            categories.storage -> ServiceFailure(
                code = LocalLlmError.MODEL_NOT_READY,
                message = "Model storage is unavailable. Repair the model and retry.",
                retryable = true,
            )
            categories.checksum -> ServiceFailure(
                code = LocalLlmError.MODEL_NOT_READY,
                message = "Model verification failed. Repair or retry the model download.",
                retryable = true,
            )
            categories.network -> ServiceFailure(
                code = LocalLlmError.MODEL_NOT_READY,
                message = "The model download could not reach the network. Retry when connected.",
                retryable = true,
            )
            categories.downloadProtocol -> ServiceFailure(
                code = LocalLlmError.MODEL_NOT_READY,
                message = "The model download was incomplete or invalid. Retry the download.",
                retryable = true,
            )
            categories.backendInitialization -> ServiceFailure(
                code = LocalLlmError.MODEL_NOT_READY,
                message = "The model backend could not initialize. Retry later.",
                retryable = true,
            )
            categories.acquisition -> ServiceFailure(
                code = LocalLlmError.MODEL_NOT_READY,
                message = "The model could not be acquired. Retry model preparation.",
                retryable = true,
            )
            categories.outputPolicy -> ServiceFailure(
                code = LocalLlmError.INTERNAL,
                message = "Generated output did not satisfy the request limits.",
                retryable = true,
            )
            else -> ServiceFailure(
                code = LocalLlmError.INTERNAL,
                message = "Local inference failed unexpectedly.",
                retryable = true,
            )
        }
    }

    private data class FailureCategories(
        var cancelled: Boolean = false,
        var outOfMemory: Boolean = false,
        var unsupportedBackend: Boolean = false,
        var modelNotInstalled: Boolean = false,
        var insufficientStorage: Boolean = false,
        var storage: Boolean = false,
        var checksum: Boolean = false,
        var network: Boolean = false,
        var downloadProtocol: Boolean = false,
        var backendInitialization: Boolean = false,
        var acquisition: Boolean = false,
        var outputPolicy: Boolean = false,
    ) {
        companion object {
            fun from(error: Throwable): FailureCategories {
                val result = FailureCategories()
                var current: Throwable? = error
                var depth = 0
                while (current != null && depth++ < MAX_CAUSE_DEPTH) {
                    when (current) {
                        is CancellationException -> result.cancelled = true
                        is OutOfMemoryError -> result.outOfMemory = true
                        is NoUsableBackendException -> result.unsupportedBackend = true
                        is ModelNotInstalledException -> result.modelNotInstalled = true
                        is InsufficientModelStorageException -> result.insufficientStorage = true
                        is ModelStorageException,
                        is ModelPromotionException,
                        -> result.storage = true
                        is ModelChecksumException -> result.checksum = true
                        is ModelNetworkException -> result.network = true
                        is ModelDownloadHttpException,
                        is InvalidModelRangeException,
                        is ModelDownloadTooLargeException,
                        is IncompleteModelDownloadException,
                        is ModelDownloadResponseLimitException,
                        -> result.downloadProtocol = true
                        is BackendInitializationException -> result.backendInitialization = true
                        is ModelAcquisitionException -> result.acquisition = true
                        is GenerationOutputPolicyException -> result.outputPolicy = true
                    }
                    current = current.cause
                }
                return result
            }

            private const val MAX_CAUSE_DEPTH = 64
        }
    }
}
