#include <jni.h>
#include <android/log.h>
#include <whisper.h>

#include <chrono>
#include <string>

namespace {
constexpr char kTag[] = "DictationV3";

long long monotonic_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_noamv_localllm_speech_WhisperNative_initContext(
        JNIEnv * env, jobject, jstring model_path) {
    if (model_path == nullptr) return 0;
    const char * path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) return 0;
    const long long start_ms = monotonic_ms();
    __android_log_print(ANDROID_LOG_DEBUG, kTag, "native model load start t=%lld", start_ms);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context * ctx = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(model_path, path);
    __android_log_print(
            ANDROID_LOG_DEBUG,
            kTag,
            "native model load end t=%lld elapsedMs=%lld",
            monotonic_ms(),
            monotonic_ms() - start_ms);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_noamv_localllm_speech_WhisperNative_freeContext(
        JNIEnv *, jobject, jlong handle) {
    if (handle != 0) whisper_free(reinterpret_cast<whisper_context *>(handle));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_noamv_localllm_speech_WhisperNative_transcribe(
        JNIEnv * env, jobject, jlong handle, jfloatArray samples, jint threads, jfloat temperature_increment) {
    if (handle == 0 || samples == nullptr) return env->NewStringUTF("");
    whisper_context * ctx = reinterpret_cast<whisper_context *>(handle);
    const jsize count = env->GetArrayLength(samples);
    jfloat * data = env->GetFloatArrayElements(samples, nullptr);
    if (data == nullptr) return env->NewStringUTF("");

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = "en";
    params.translate = false;
    params.no_timestamps = true;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.temperature_inc = temperature_increment;
    const long long start_ms = monotonic_ms();
    __android_log_print(
            ANDROID_LOG_DEBUG,
            kTag,
            "whisper_full start t=%lld samples=%d threads=%d temperatureInc=%.2f",
            start_ms,
            count,
            threads,
            temperature_increment);
    const int result = whisper_full(ctx, params, data, count);
    __android_log_print(
            ANDROID_LOG_DEBUG,
            kTag,
            "whisper_full end t=%lld elapsedMs=%lld result=%d",
            monotonic_ms(),
            monotonic_ms() - start_ms,
            result);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    if (result != 0) return env->NewStringUTF("");

    std::string text;
    const int segments = whisper_full_n_segments(ctx);
    for (int index = 0; index < segments; ++index) {
        const char * segment = whisper_full_get_segment_text(ctx, index);
        if (segment != nullptr) text += segment;
    }
    while (!text.empty() && (text.back() == ' ' || text.back() == '\n' || text.back() == '\r' || text.back() == '\t')) {
        text.pop_back();
    }
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_noamv_localllm_speech_WhisperNative_timingsMs(
        JNIEnv * env, jobject, jlong handle) {
    jlong values[4] = {0, 0, 0, 0};
    if (handle != 0) {
        whisper_timings * timings = whisper_get_timings(reinterpret_cast<whisper_context *>(handle));
        if (timings != nullptr) {
            values[0] = static_cast<jlong>(timings->sample_ms);
            values[1] = static_cast<jlong>(timings->encode_ms);
            values[2] = static_cast<jlong>(timings->decode_ms);
            values[3] = static_cast<jlong>(
                    timings->sample_ms + timings->encode_ms + timings->decode_ms +
                    timings->batchd_ms + timings->prompt_ms);
        }
    }
    jlongArray result = env->NewLongArray(4);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 4, values);
    return result;
}
