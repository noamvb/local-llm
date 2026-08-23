package com.noamv.localllm;

import com.noamv.localllm.IInsightCallback;

/**
 * The inference service other apps bind to.
 *
 * Payloads are JSON strings rather than Parcelables on purpose. The client apps live in
 * separate repositories and are released on their own schedules, so a versioned JSON
 * contract can add fields without every app needing to be rebuilt in lockstep. Only this
 * file and IInsightCallback.aidl need to be copied into a client app.
 *
 * Binder transactions are capped at roughly 1 MB for the whole process, so callers must
 * send pre-computed statistics rather than raw database rows. See docs/API_CONTRACT.md.
 */
interface IInsightService {

    /**
     * Highest contract version implemented by this service. Clients must call this on
     * the same binding they will use. A numerically higher unknown value does not itself
     * prove v1 compatibility; clients accept only explicitly declared compatible values.
     */
    int getApiVersion();

    /**
     * Engine state as JSON, without starting any work. Shape:
     * {"state":"READY|MODEL_MISSING|DOWNLOADING|INITIALISING|UNSUPPORTED",
     *  "modelId":"gemma-4-E2B-it","backend":"NPU|GPU|CPU|NANO",
     *  "downloadPercent":42,"detail":"human readable"}
     */
    String getEngineState();

    /**
     * Queue an insight request. Returns immediately; results arrive on the callback.
     *
     * @param requestJson the request document, see docs/API_CONTRACT.md
     * @param callback    receives tokens, progress and the terminal result
     * @return the requestId the service assigned, echoed on every callback
     */
    String requestInsight(String requestJson, IInsightCallback callback);

    /** Best-effort cancellation. Safe to call for an unknown or finished requestId. */
    void cancel(String requestId);
}
