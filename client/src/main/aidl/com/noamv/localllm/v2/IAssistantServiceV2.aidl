package com.noamv.localllm.v2;

import com.noamv.localllm.v2.IAssistantCallbackV2;

/**
 * Version-two assistant inference service.
 *
 * All request payloads and responses use typed JSON documents defined in
 * com.noamv.localllm.contract.v2.AssistantContractV2.
 *
 * Binders run with reciprocal package-and-signing-lineage authentication.
 */
interface IAssistantServiceV2 {

    /**
     * Highest contract version implemented by this service (e.g. 2).
     */
    int getApiVersion();

    /**
     * Synchronous capabilities document describing supported model roles, grammar
     * version, and provider status. Shape: AssistantCapabilities JSON.
     */
    String getCapabilitiesJson(String clientId);

    /**
     * Start an assistant turn asynchronously.
     *
     * @param requestJson serialized AssistantTurnRequest document
     * @param callback    receives streaming events, drafts, and the terminal result
     * @return assigned stable requestId
     */
    String startTurn(String requestJson, IAssistantCallbackV2 callback);

    /**
     * Best-effort cancellation of an in-flight turn request.
     */
    void cancelTurn(String requestId);

    /**
     * Synchronously read a bounded page of shared conversation history.
     *
     * @param queryJson serialized HistoryQuery document
     * @return serialized HistoryPage document
     */
    String getHistoryPage(String queryJson);
}
