package com.noamv.localllm.contract.v3

import org.junit.Assert.assertEquals
import org.junit.Test

class DictationContractV3Test {
    @Test
    fun decodesRequestLiteral() {
        val request = DictationContractV3.json.decodeFromString(
            DictationRequest.serializer(),
            "{\"model\":\"whisper-base-en\",\"language\":\"en\",\"timeoutMs\":30000}",
        )
        assertEquals(DictationRequest(), request)
    }

    @Test
    fun decodesCapabilitiesLiteral() {
        val capabilities = DictationContractV3.json.decodeFromString(
            DictationCapabilities.serializer(),
            "{\"apiVersion\":3,\"models\":[{\"id\":\"whisper-base-en\",\"installed\":false},{\"id\":\"whisper-small-en\",\"installed\":true}],\"audio\":{\"format\":\"wav\",\"sampleRateHz\":16000,\"channels\":1,\"bitsPerSample\":16,\"maxSeconds\":60}}",
        )
        assertEquals(3, capabilities.apiVersion)
        assertEquals(listOf("whisper-base-en", "whisper-small-en"), capabilities.models.map { it.id })
    }

    @Test
    fun decodesResultLiteral() {
        val result = DictationContractV3.json.decodeFromString(
            DictationResultFields.serializer(),
            "{\"requestId\":\"id-1\",\"text\":\"And so my fellow Americans, ask not what your country can do for you, ask what you can do for your country.\",\"model\":\"whisper-base-en\",\"audioSeconds\":11.0,\"timingsMs\":{\"load\":0,\"encode\":0,\"decode\":0,\"total\":0}}",
        )
        assertEquals("And so my fellow Americans, ask not what your country can do for you, ask what you can do for your country.", result.text)
    }

    @Test
    fun encodesRequestAndResultExactly() {
        assertEquals(
            "{\"model\":\"whisper-base-en\",\"language\":\"en\",\"timeoutMs\":30000}",
            DictationContractV3.json.encodeToString(DictationRequest.serializer(), DictationRequest()),
        )
        val result = DictationResultFields("id-1", "Hello", "whisper-base-en", 1.0)
        assertEquals(
            "{\"requestId\":\"id-1\",\"text\":\"Hello\",\"model\":\"whisper-base-en\",\"audioSeconds\":1.0,\"timingsMs\":{\"load\":0,\"encode\":0,\"decode\":0,\"total\":0}}",
            DictationContractV3.json.encodeToString(DictationResultFields.serializer(), result),
        )
    }

    @Test
    fun decodesStructureRequestLiteralWithDefaultKinds() {
        val request = DictationContractV3.json.decodeFromString(
            StructureRequest.serializer(),
            "{\"text\":\"remind me to buy milk tomorrow\"}",
        )

        assertEquals("remind me to buy milk tomorrow", request.text)
        assertEquals(listOf("todo", "note"), request.kinds)
    }

    @Test
    fun encodesStructureResultExactly() {
        val result = StructureResult(
            requestId = "id-1",
            kind = "todo",
            text = "Buy milk tomorrow",
            confidence = 0.92,
            model = "gemma-4-E2B-it-gpu",
        )

        assertEquals(
            "{\"requestId\":\"id-1\",\"kind\":\"todo\",\"text\":\"Buy milk tomorrow\",\"confidence\":0.92,\"model\":\"gemma-4-E2B-it-gpu\",\"timingsMs\":{\"total\":0}}",
            DictationContractV3.json.encodeToString(StructureResult.serializer(), result),
        )
    }
}
