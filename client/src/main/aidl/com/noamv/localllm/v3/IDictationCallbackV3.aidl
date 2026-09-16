package com.noamv.localllm.v3;
oneway interface IDictationCallbackV3 {
    void onProgress(String requestId, int percent, String stage);
    void onComplete(String requestId, String resultJson);
    void onError(String requestId, int errorCode, String message, boolean retryable);
}
