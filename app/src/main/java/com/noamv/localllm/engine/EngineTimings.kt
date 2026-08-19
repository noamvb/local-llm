package com.noamv.localllm.engine

/** App-internal diagnostics. Deliberately not part of InsightContract. */
data class EngineTimings(
    val lastInitMillis: Long? = null,
    val lastInitBackend: String? = null,
    /** Request start to first emitted fragment, including any load it had to wait for. */
    val lastTimeToFirstTokenMillis: Long? = null,
    /** Time from the engine being ready to the first fragment: prefill plus any queue wait. */
    val lastPrefillMillis: Long? = null,
    /** True when an engine was already loaded when the request arrived. */
    val lastRequestWasWarm: Boolean? = null,
    /** True when this request had to download the model first, which inflates TTFT enormously. */
    val lastRequestDownloaded: Boolean = false,
)
