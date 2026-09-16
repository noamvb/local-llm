package com.noamv.localllm.engine

/**
 * Retains one structure conversation for one resident engine and instruction.
 *
 * Conversation state is native and must be released before its owning engine. Calls are
 * serialized by [EngineLifecycleCoordinator], so this holder does not need its own lock.
 */
internal class StructureConversationReuse<C : AutoCloseable> {
    private var owner: Any? = null
    private var systemInstruction: String? = null
    private var conversation: C? = null

    fun getOrCreate(
        engine: Any,
        instruction: String,
        create: () -> C,
    ): C {
        if (conversation == null || owner !== engine || systemInstruction != instruction) {
            close()
            conversation = create()
            owner = engine
            systemInstruction = instruction
        }
        return checkNotNull(conversation)
    }

    fun closeFor(engine: Any) {
        if (owner === engine) {
            close()
        }
    }

    fun close() {
        val current = conversation
        conversation = null
        owner = null
        systemInstruction = null
        current?.close()
    }
}
