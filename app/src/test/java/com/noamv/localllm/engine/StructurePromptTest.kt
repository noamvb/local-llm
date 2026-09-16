package com.noamv.localllm.engine

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class StructurePromptTest {
    @Test
    fun forDictationUsesTheContractInstructionAndSchema() {
        val text = "remind me to buy milk"
        val prompt = StructurePrompts.forDictation(text, listOf("todo", "note"))

        assertEquals(
            "You sort one short dictated sentence for a personal inbox. Decide whether it is a todo (something the speaker intends to do, buy, send, remember, or be reminded of) or a note (a thought, fact, idea, or observation with no action). Rewrite the text: for a todo, a short imperative starting with a verb; for a note, the sentence as spoken with filler removed. Capitalise the first letter. Give a confidence between 0 and 1 for the kind decision. Reply with JSON only. Use exactly these keys: \"kind\" (todo or note), \"text\", \"confidence\".",
            prompt.systemInstruction,
        )
        assertEquals(text, prompt.userMessage)
        assertEquals(StructurePrompts.STRUCTURE_SCHEMA, prompt.schema)
    }

    @Test
    fun schemaIsJsonWithExactlyTheTodoAndNoteEnum() {
        val schema = kotlinx.serialization.json.Json.parseToJsonElement(StructurePrompts.STRUCTURE_SCHEMA)

        assertEquals(
            listOf("todo", "note"),
            schema.jsonObject["properties"]!!.jsonObject["kind"]!!.jsonObject["enum"]!!.jsonArray
                .map { it.jsonPrimitive.content },
        )
    }
}
