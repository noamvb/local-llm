package com.noamv.localllm.v3;
import android.os.ParcelFileDescriptor;
import com.noamv.localllm.v3.IDictationCallbackV3;
interface IDictationServiceV3 {
    int getApiVersion();
    String getCapabilitiesJson();
    String transcribe(in ParcelFileDescriptor audio, String requestJson, IDictationCallbackV3 callback);
    String structure(String requestJson, IDictationCallbackV3 callback);
    void cancel(String requestId);
}
