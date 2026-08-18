package com.noamv.localllm.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The wire contract between LocalLLM and the apps that call it.
 *
 * This file is duplicated verbatim into each client app. It is deliberately dependency
 * free apart from kotlinx.serialization so the copy is a straight file copy.
 *
 * The central design rule is that a client sends FACTS, never rows. Every number a
 * client wants mentioned must already be computed, rounded and formatted by the client.
 * A 2B parameter model is a competent writer and an unreliable calculator; asking it to
 * derive "up 12% on last month" from a list of timestamps is how insight features start
 * producing confident nonsense. The model's only job is to turn facts into sentences.
 */
object InsightContract {
    /** Bump when a change is not backward compatible. Clients compare against getApiVersion(). */
    const val VERSION = 1

    val json: Json = Json {
        ignoreUnknownKeys = true   // an older client must survive a newer service
        encodeDefaults = true
        isLenient = false
    }
}

/** What the caller wants written. */
@Serializable
enum class InsightTask {
    /** A short narrative covering one period. The default and the safest. */
    @SerialName("PERIOD_SUMMARY") PERIOD_SUMMARY,

    /** A one-or-two sentence line suitable for a notification. */
    @SerialName("NUDGE") NUDGE,

    /** Compares two periods whose facts are both supplied. */
    @SerialName("PERIOD_COMPARISON") PERIOD_COMPARISON,
}

/**
 * A single pre-computed fact.
 *
 * @param label  what the number means, e.g. "Entries recorded"
 * @param value  the already-formatted value, e.g. "18" or "2 h 40 min" or "up from 12"
 * @param note   optional qualifier the model may mention, e.g. "3 entries lacked a duration"
 */
@Serializable
data class Fact(
    val label: String,
    val value: String,
    val note: String? = null,
)

/** The window the facts describe. Dates are ISO-8601 local dates. */
@Serializable
data class Period(
    val label: String,
    val start: String? = null,
    val end: String? = null,
)

/**
 * Guardrails. Defaults are the strict ones; a client must opt out deliberately.
 */
@Serializable
data class SafetyPolicy(
    /** Forbids diagnosis, causal claims and treatment advice in the system instruction. */
    val forbidHealthClaims: Boolean = true,
    /** Forbids inventing any number that was not supplied as a Fact. */
    val forbidNewNumbers: Boolean = true,
    /**
     * Set true when the output may be shown on a lock screen. Instructs the model to
     * avoid naming specific products, quantities or dates. Cannsheet's own convention
     * keeps that detail out of notifications, so NUDGE requests from it must set this.
     */
    val lockScreenSafe: Boolean = false,
)

@Serializable
data class InsightRequest(
    val contractVersion: Int = InsightContract.VERSION,
    /** Stable identifier of the calling app, used for logging and per-client defaults. */
    val clientId: String,
    val task: InsightTask = InsightTask.PERIOD_SUMMARY,
    val period: Period? = null,
    /** Facts describing [period]. */
    val facts: List<Fact> = emptyList(),
    /** Facts describing the comparison period, for PERIOD_COMPARISON only. */
    val comparisonPeriod: Period? = null,
    val comparisonFacts: List<Fact> = emptyList(),
    /**
     * Plain-language description of what the data is, e.g. "personal bowel-movement
     * records". Used to give the model just enough grounding without sending rows.
     */
    val subject: String,
    /** Soft cap. The prompt asks for this; generation also stops at a hard token limit. */
    val maxWords: Int = 90,
    /** Emit onToken callbacks as text is produced. */
    val stream: Boolean = true,
    /**
     * Optional JSON schema. When present the runtime constrains decoding so the result
     * is guaranteed-parseable JSON, delivered in onComplete's resultJson.
     */
    val resultSchema: String? = null,
    val safety: SafetyPolicy = SafetyPolicy(),
)

/** Error codes shared with IInsightCallback.onError. */
object LocalLlmError {
    const val BUSY = 1
    const val MODEL_NOT_READY = 2
    const val CANCELLED = 3
    const val INVALID_REQUEST = 4
    const val OUT_OF_MEMORY = 5
    const val UNSUPPORTED_DEVICE = 6
    const val INTERNAL = 7
}

@Serializable
enum class EngineState {
    @SerialName("READY") READY,
    @SerialName("MODEL_MISSING") MODEL_MISSING,
    @SerialName("DOWNLOADING") DOWNLOADING,
    @SerialName("INITIALISING") INITIALISING,
    @SerialName("UNSUPPORTED") UNSUPPORTED,
}

/** Returned by IInsightService.getEngineState(), serialised as JSON. */
@Serializable
data class EngineStatus(
    /** Whether an engine is loaded right now. MODEL_MISSING means "not loaded". */
    val state: EngineState,
    val modelId: String? = null,
    val backend: String? = null,
    val downloadPercent: Int = -1,
    val detail: String = "",
    /**
     * True when the model file is already on disk, whether or not it is loaded.
     *
     * This is the field a client should check before deciding to ask for an insight.
     * The service holds no engine until something asks for one, so a freshly started
     * process reports MODEL_MISSING even with gigabytes of model sitting on disk. A
     * client that waits for READY would therefore never ask, and the engine would never
     * load. When this is true a request costs a few seconds of loading and no network.
     */
    val modelDownloaded: Boolean = false,
)
