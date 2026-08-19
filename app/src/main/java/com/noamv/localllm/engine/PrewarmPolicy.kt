package com.noamv.localllm.engine

import com.noamv.localllm.contract.EngineState
import com.noamv.localllm.contract.EngineStatus

/**
 * Whether binding the service should start loading the model.
 *
 * Only MODEL_MISSING with the file already on disk qualifies. READY needs nothing;
 * DOWNLOADING and INITIALISING already have work in flight; UNSUPPORTED means every
 * candidate build failed to start, and retrying that on every bind would burn battery
 * in a loop for a device that cannot run the model at all.
 */
internal fun shouldPrewarmOnBind(status: EngineStatus): Boolean =
    status.modelDownloaded && status.state == EngineState.MODEL_MISSING
