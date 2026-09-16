package com.noamv.localllm.engine

import com.noamv.localllm.contract.v3.DictationContractV3
import com.noamv.localllm.contract.v3.StructureResultFields
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object StructureOutputParser {
    private val tolerantJson = Json(DictationContractV3.json) {
        ignoreUnknownKeys = true
    }

    fun parse(raw: String, requestedKinds: Set<String>): StructureResultFields {
        val strict = runCatching {
            DictationContractV3.json.decodeFromString(StructureResultFields.serializer(), raw)
        }.getOrNull()
        if (strict != null) return validate(strict, requestedKinds)

        var searchFrom = 0
        while (true) {
            val start = raw.indexOf('{', searchFrom)
            if (start < 0) break
            val end = balancedObjectEnd(raw, start)
            if (end != null) {
                val candidate = raw.substring(start, end + 1)
                val normalized = runCatching { normalize(candidate) }.getOrNull()
                if (normalized != null) {
                    val parsed = runCatching {
                        tolerantJson.decodeFromString(
                            StructureResultFields.serializer(),
                            normalized,
                        )
                    }.getOrNull()
                    if (parsed != null) return validate(parsed, requestedKinds)
                }
            }
            searchFrom = start + 1
        }
        throw IllegalArgumentException("Structure result is not valid JSON")
    }

    private fun normalize(candidate: String): String {
        val source = tolerantJson.parseToJsonElement(candidate).jsonObject
        return buildJsonObject {
            source.forEach { (key, value) -> put(key, value) }
            if ("kind" !in source) source["decision"]?.jsonPrimitive?.content?.let { put("kind", it) }
            if ("text" !in source) {
                listOf("rewritten_text", "rewrite", "item").firstNotNullOfOrNull { source[it] }
                    ?.let { put("text", it) }
            }
            if ("confidence" !in source) source["score"]?.let { put("confidence", it) }
        }.toString()
    }

    private fun validate(parsed: StructureResultFields, requestedKinds: Set<String>): StructureResultFields {
        if (parsed.kind !in requestedKinds ||
            parsed.confidence.isNaN() ||
            parsed.confidence < 0.0 ||
            parsed.confidence > 1.0
        ) {
            throw IllegalArgumentException("Structure result does not match the request")
        }
        return parsed
    }

    private fun balancedObjectEnd(raw: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until raw.length) {
            val character = raw[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (character == '\\') {
                    escaped = true
                } else if (character == '"') {
                    inString = false
                }
                continue
            }
            when (character) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }
}
