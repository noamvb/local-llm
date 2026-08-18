# Keep the AIDL-generated binder stubs reachable for cross-app clients.
-keep class com.noamv.localllm.I*$Stub { *; }
-keep class com.noamv.localllm.I*$Stub$Proxy { *; }
