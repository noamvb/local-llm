package com.noamv.localllm.v2;

/**
 * Delivers computed fact results or errors back to LocalLLM from a client provider.
 */
oneway interface IAssistantFactsCallbackV2 {

    /**
     * Computed fact results with evidence provenance.
     * Shape: ProviderFactsResult JSON document.
     */
    void onFactsResult(String queryId, String resultJson);

    /**
     * Provider error when facts cannot be computed.
     *
     * @param errorCode LocalLlmError code
     * @param message   human-readable error description
     */
    void onProviderError(String queryId, int errorCode, String message);
}
