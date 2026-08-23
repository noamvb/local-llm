package com.noamv.localllm.client

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * One event from [LocalLlmClient.generateEvents].
 *
 * Draft text is a complete replaceable snapshot, not an append-only fragment. Exactly one
 * [Complete] or [Failure] is emitted unless the collector cancels first. Progress is
 * advisory and may be absent.
 */
sealed interface GenerationEvent {
    /** Preparation or download progress as reported by the service. */
    data class Progress(val percent: Int, val stage: String) : GenerationEvent

    /** The latest complete streamed draft. Replace any earlier draft with [text]. */
    data class Draft(val text: String) : GenerationEvent

    /** The authoritative terminal text. This may differ from every streamed draft. */
    data class Complete(val text: String) : GenerationEvent

    /** A terminal service, protocol, connection, or deadline failure. */
    data class Failure(val error: Throwable) : GenerationEvent
}

/** Compatibility adapter for append-based v1 collectors: emit terminal text exactly once. */
internal fun Flow<GenerationEvent>.completionOnlyText(): Flow<String> = flow {
    collect { event ->
        when (event) {
            is GenerationEvent.Complete -> emit(event.text)
            is GenerationEvent.Failure -> throw event.error
            is GenerationEvent.Draft,
            is GenerationEvent.Progress,
            -> Unit
        }
    }
}
