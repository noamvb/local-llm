package com.noamv.localllm.v2;

import com.noamv.localllm.v2.IAssistantFactsCallbackV2;

/**
 * Provider interface implemented by client apps (Cannsheet, Poop Schedule) to serve
 * bounded aggregate fact queries to LocalLLM.
 *
 * LocalLLM authenticates with the client provider via reciprocal signature / knownSigner
 * permission and runtime package-lineage verification.
 */
interface IAssistantFactsProviderV2 {

    /**
     * Highest provider contract version implemented by this provider (e.g. 2).
     */
    int getProviderVersion();

    /**
     * Synchronous capabilities document describing supported source metric IDs and
     * filter features. Shape: ProviderCapabilities JSON document.
     */
    String getProviderCapabilitiesJson();

    /**
     * Asynchronously query aggregate facts for the specified query.
     *
     * @param queryJson serialized AggregateQuery document
     * @param callback  receives the fact result or error
     * @return assigned stable queryId
     */
    String queryFacts(String queryJson, IAssistantFactsCallbackV2 callback);

    /**
     * Best-effort cancellation of an in-flight query.
     */
    void cancelQuery(String queryId);
}
