package com.noamv.localllm.v2;

/**
 * Results and streaming events are delivered back to the calling app on this interface.
 *
 * Every method is `oneway`: the inference service must never block on a client that is
 * slow, paused, or dying.
 */
oneway interface IAssistantCallbackV2 {

    /**
     * Intermediate lifecycle event (routing, model loading, drafts, provider status).
     * Shape: AssistantEvent JSON document.
     */
    void onEvent(String requestId, String eventJson);

    /**
     * Authoritative terminal success with citations, evidence, and validation status.
     * Shape: AssistantTerminalResult JSON document.
     */
    void onComplete(String requestId, String resultJson);

    /**
     * Terminal failure.
     *
     * @param errorCode LocalLlmError code
     * @param message   human-readable detail
     * @param retryable whether the caller may safely retry the turn
     */
    void onError(String requestId, int errorCode, String message, boolean retryable);
}
