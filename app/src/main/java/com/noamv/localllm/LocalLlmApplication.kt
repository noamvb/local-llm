package com.noamv.localllm

import android.app.Application
import android.util.Log
import com.noamv.localllm.engine.LiteRtEngine
import com.noamv.localllm.engine.LlmEngine
import com.noamv.localllm.engine.shouldPrewarmOnBind
import com.noamv.localllm.model.ModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    private val engineDelegate = lazy<LlmEngine> { LiteRtEngine(this, modelStore) }
    val engine: LlmEngine
        get() = engineDelegate.value

    /**
     * Scope for work that has to outlive whatever screen started it.
     *
     * It is never cancelled: it is the process, not a component. Nothing here is tied to
     * a UI lifecycle, so there is nothing to release.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var prepareJob: Job? = null
    private val prepareJobLock = Any()

    /**
     * Downloads and loads the model, and keeps going when the user leaves.
     *
     * This deliberately does not run in a ViewModel's scope. It used to, and closing the
     * manager screen therefore cancelled a two-gigabyte download part-way through — the
     * single most expensive thing this app does. Repeat calls join the run already in
     * flight rather than starting a second one; [LlmEngine.prepare] is idempotent, but
     * queueing redundant calls behind its lock is still wasteful.
     *
     * Progress and failures are published through [engine] status, which the UI observes,
     * so nothing is lost by not returning them here.
     */
    fun prepareModel(): Job {
        synchronized(prepareJobLock) {
            prepareJob?.takeIf { it.isActive }?.let { return it }

            // Register the lazy job before it can complete. The earlier launch-then-store
            // sequence allowed a fast failure to finish before prepareJob was assigned,
            // after which callers could retain a stale completed job.
            lateinit var newJob: Job
            newJob = applicationScope.launch(start = CoroutineStart.LAZY) {
                try {
                    engine.prepare()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Log.w(TAG, "Model preparation failed", error)
                }
            }
            prepareJob = newJob
            newJob.invokeOnCompletion {
                synchronized(prepareJobLock) {
                    if (prepareJob === newJob) prepareJob = null
                }
            }
            newJob.start()
            return newJob
        }
    }

    /**
     * Loads an already-downloaded model ahead of the first request, so Engine.initialize()
     * overlaps with the user reading the screen instead of blocking the insight card.
     *
     * Deliberately never downloads: this fires on a bind, and a bind must not be able to
     * start a multi-gigabyte transfer. When no file is present this does nothing and the
     * ordinary download-on-demand path in requestInsight still applies.
     */
    fun prewarmModel() {
        applicationScope.launch {
            // Reading engine.status constructs the engine lazily, which touches the disk.
            // Doing it inside the coroutine keeps that off the binder/main thread.
            if (shouldPrewarmOnBind(engine.status.value)) prepareModel()
        }
    }

    // The granular TRIM_MEMORY_* levels are deprecated from API 34, but no replacement
    // signal exists for "the system is about to kill you". Releasing the model is drastic;
    // being killed outright mid-generation is worse, so the next request pays a reload.
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < TRIM_MEMORY_RUNNING_CRITICAL || !engineDelegate.isInitialized()) return

        // Cancelling process-owned preparation preserves ModelStore's partial download.
        // Native generation itself is not pre-empted: unload waits behind the engine's
        // lifecycle coordinator and closes only after generation reaches a safe boundary.
        synchronized(prepareJobLock) { prepareJob?.cancel() }
        applicationScope.launch { engine.unload() }
    }

    companion object {
        private const val TAG = "LocalLlmApplication"
    }
}
