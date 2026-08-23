package com.noamv.localllm.engine

import com.noamv.localllm.model.ModelBuild

/** Download, storage, or verification failed before a backend was initialized. */
class ModelAcquisitionException(
    val build: ModelBuild,
    cause: Throwable,
) : Exception("Could not acquire ${build.displayName}: ${cause.message.orEmpty()}", cause)

/** A verified model file was present, but its selected native backend did not initialize. */
class BackendInitializationException(
    val build: ModelBuild,
    cause: Throwable,
) : Exception("Could not initialize ${build.displayName}: ${cause.message.orEmpty()}", cause)

/** Every compatible backend candidate failed initialization. */
class NoUsableBackendException(
    val failures: List<BackendInitializationException>,
) : Exception(
    "No usable model backend on this device. " +
        failures.joinToString("; ") { failure ->
            "${failure.build.displayName}: ${failure.cause?.message.orEmpty().lineSequence().firstOrNull().orEmpty()}"
        },
)
