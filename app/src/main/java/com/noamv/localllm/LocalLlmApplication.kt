package com.noamv.localllm

import android.app.Application
import com.noamv.localllm.engine.LiteRtEngine
import com.noamv.localllm.engine.LlmEngine
import com.noamv.localllm.model.ModelStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency wiring, matching the convention used by the other two apps: no
 * dependency-injection framework, one container built at startup.
 *
 * The engine is held here rather than in the service so that the loaded model survives
 * the service being unbound and rebound. Reloading a two-gigabyte model on every bind
 * would make the first insight after each app switch take ten seconds.
 */
class LocalLlmApplication : Application() {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // Model downloads are large; the default ten-second read timeout is far too short.
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val modelStore: ModelStore by lazy { ModelStore(this, httpClient) }

    val engine: LlmEngine by lazy { LiteRtEngine(this, modelStore) }

    // The granular TRIM_MEMORY_* levels are deprecated from API 34, but no replacement
    // signal exists for "the system is about to kill you". Releasing the model is drastic;
    // being killed outright mid-generation is worse, so the next request pays a reload.
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL) {
            engine.close()
        }
    }
}
