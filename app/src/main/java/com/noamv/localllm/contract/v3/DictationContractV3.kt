package com.noamv.localllm.contract.v3

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object DictationContractV3 {
    const val VERSION = 3

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = false
    }
}

@Serializable
data class DictationRequest(
    val model: String = "whisper-base-en",
    val language: String = "en",
    val timeoutMs: Int = 30_000,
)

@Serializable
data class SpeechModelCapability(
    val id: String,
    val installed: Boolean,
)

@Serializable
data class DictationAudioCapabilities(
    val format: String = "wav",
    val sampleRateHz: Int = 16_000,
    val channels: Int = 1,
    val bitsPerSample: Int = 16,
    val maxSeconds: Int = 60,
)

@Serializable
data class DictationCapabilities(
    val apiVersion: Int = DictationContractV3.VERSION,
    val models: List<SpeechModelCapability>,
    val audio: DictationAudioCapabilities = DictationAudioCapabilities(),
)

@Serializable
data class DictationTimings(
    val load: Long = 0,
    val encode: Long = 0,
    val decode: Long = 0,
    val total: Long = 0,
)

@Serializable
data class DictationResultFields(
    val requestId: String,
    val text: String,
    val model: String,
    val audioSeconds: Double,
    val timingsMs: DictationTimings = DictationTimings(),
)

@Serializable
data class StructureRequest(
    val text: String,
    val kinds: List<String> = listOf("todo", "note"),
)

@Serializable
data class StructureTimings(
    val total: Long = 0,
)

@Serializable
data class StructureResultFields(
    val requestId: String = "",
    val kind: String,
    val text: String,
    val confidence: Double,
    val model: String = "",
    val timingsMs: StructureTimings = StructureTimings(),
)

typealias StructureResult = StructureResultFields

object DictationError {
    const val UNAUTHORIZED = 1
    const val BAD_REQUEST = 2
    const val MODEL_NOT_INSTALLED = 3
    const val AUDIO_FORMAT = 4
    const val BUSY = 5
    const val CANCELLED = 6
    const val ENGINE_FAILURE = 7
}
