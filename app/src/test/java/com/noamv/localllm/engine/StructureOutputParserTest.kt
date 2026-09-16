package com.noamv.localllm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StructureOutputParserTest {
    @Test
    fun legacyEnvelopeWithSynonymsParses() {
        val raw = "{\"{\n  \"decision\": \"todo\",\n  \"confidence\": 1.0,\n  \"rewritten_text\": \"Buy milk tomorrow\"\n}\n}"

        val result = StructureOutputParser.parse(raw, setOf("todo", "note"))

        assertEquals("todo", result.kind)
        assertEquals("Buy milk tomorrow", result.text)
        assertEquals(1.0, result.confidence, 0.0)
    }

    @Test
    fun cleanStructureObjectParses() {
        val result = StructureOutputParser.parse(
            "{\"kind\":\"note\",\"text\":\"x\",\"confidence\":0.4}",
            setOf("todo", "note"),
        )

        assertEquals("note", result.kind)
        assertEquals("x", result.text)
        assertEquals(0.4, result.confidence, 0.0)
    }

    @Test
    fun garbageFails() {
        assertThrows(IllegalArgumentException::class.java) {
            StructureOutputParser.parse("garbage", setOf("todo", "note"))
        }
    }

    @Test
    fun kindOutsideRequestFails() {
        assertThrows(IllegalArgumentException::class.java) {
            StructureOutputParser.parse(
                "{\"kind\":\"todo\",\"text\":\"x\",\"confidence\":0.4}",
                setOf("note"),
            )
        }
    }
}
