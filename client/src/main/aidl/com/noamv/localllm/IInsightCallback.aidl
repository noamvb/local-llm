package com.noamv.localllm;

/**
 * Results are delivered back to the calling app on this interface.
 *
 * Every method is `oneway`: the inference service must never block on a client that is
 * slow, paused, or dying. A oneway call returns as soon as the transaction is queued.
 *
 * The client must tolerate a stream that stops without onComplete, because the service
 * process can be killed under memory pressure at any point.
 */
oneway interface IInsightCallback {

    /** A generated fragment. Fires many times when the request asked for streaming. */
    void onToken(String requestId, String token);

    /**
     * Terminal success.
     *
     * @param text       the full generated narrative
     * @param resultJson reserved for a later contract; always null in v1
     */
    void onComplete(String requestId, String text, String resultJson);

    /**
     * Terminal failure. Codes are the LocalLlmError values in the shared contract:
     * 1 BUSY, 2 MODEL_NOT_READY, 3 CANCELLED, 4 INVALID_REQUEST,
     * 5 OUT_OF_MEMORY, 6 UNSUPPORTED_DEVICE, 7 INTERNAL.
     */
    void onError(String requestId, int code, String message);

    /**
     * Progress for work that happens before generation, chiefly model download and
     * engine initialisation. `stage` is a stable machine-readable token such as
     * "downloading", "verifying" or "initialising"; percent is -1 when indeterminate.
     */
    void onProgress(String requestId, int percent, String stage);
}
