package com.noamv.localllm.engine

data class StructurePrompt(
    val systemInstruction: String,
    val userMessage: String,
    val schema: String,
)

const val STRUCTURE_SCHEMA =
    "{\"type\":\"object\",\"properties\":{\"kind\":{\"type\":\"string\",\"enum\":[\"todo\",\"note\"]},\"text\":{\"type\":\"string\"},\"confidence\":{\"type\":\"number\",\"minimum\":0,\"maximum\":1}},\"required\":[\"kind\",\"text\",\"confidence\"]}"

object StructurePrompts {
    const val STRUCTURE_SCHEMA = com.noamv.localllm.engine.STRUCTURE_SCHEMA

    private const val SYSTEM_INSTRUCTION =
        "You sort one short dictated sentence for a personal inbox. Decide whether it is a todo (something the speaker intends to do, buy, send, remember, or be reminded of) or a note (a thought, fact, idea, or observation with no action). Rewrite the text: for a todo, a short imperative starting with a verb; for a note, the sentence as spoken with filler removed. Capitalise the first letter. Give a confidence between 0 and 1 for the kind decision. Reply with JSON only. Use exactly these keys: \"kind\" (todo or note), \"text\", \"confidence\"."

    fun forDictation(text: String, kinds: List<String>): StructurePrompt = StructurePrompt(
        systemInstruction = SYSTEM_INSTRUCTION,
        userMessage = text,
        schema = STRUCTURE_SCHEMA,
    )
}
