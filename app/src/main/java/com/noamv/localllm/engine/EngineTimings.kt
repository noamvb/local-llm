package com.noamv.localllm.engine

/** App-internal diagnostics. Deliberately not part of InsightContract. */
data class EngineTimings(
    val lastInitMillis: Long? = null,
    val lastInitBackend: String? = null,
    /** Request start to first emitted fragment, including any load it had to wait for. */
    val lastTimeToFirstTokenMillis: Long? = null,
    /** True when an engine was already loaded when the request arrived. */
    val lastRequestWasWarm: Boolean? = null,
)
