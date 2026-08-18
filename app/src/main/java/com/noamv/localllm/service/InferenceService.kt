package com.noamv.localllm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.noamv.localllm.IInsightCallback
import com.noamv.localllm.IInsightService
import com.noamv.localllm.LocalLlmApplication
import com.noamv.localllm.R
import com.noamv.localllm.contract.EngineStatus
import com.noamv.localllm.contract.InsightContract
import com.noamv.localllm.contract.InsightRequest
import com.noamv.localllm.contract.LocalLlmError
import com.noamv.localllm.engine.LlmEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.Dispatchers
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The bound service that client apps talk to.
 *
 * Access is gated by the signature-level permission declared in the manifest, so the
 * binder itself performs no additional caller checks: an app that is not signed with the
 * same certificate cannot bind at all.
 *
 * The service promotes itself to the foreground while work is in flight. Without that, a
 * generation started by a backgrounded client is liable to be killed part-way through,
 * and the client sees a stream that simply stops.
 */
class InferenceService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlight = ConcurrentHashMap<String, Job>()

    private val engine: LlmEngine
        get() = (application as LocalLlmApplication).engine

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private val binder = object : IInsightService.Stub() {

        override fun getApiVersion(): Int = InsightContract.VERSION

        override fun getEngineState(): String =
            InsightContract.json.encodeToString(EngineStatus.serializer(), engine.status.value)

        override fun requestInsight(requestJson: String, callback: IInsightCallback): String {
            val requestId = UUID.randomUUID().toString()

            val request = try {
                InsightContract.json.decodeFromString(InsightRequest.serializer(), requestJson)
            } catch (error: Exception) {
                callback.safeError(requestId, LocalLlmError.INVALID_REQUEST, error.message.orEmpty())
                return requestId
            }

            if (request.contractVersion > InsightContract.VERSION) {
                callback.safeError(
                    requestId,
                    LocalLlmError.INVALID_REQUEST,
                    "Client asked for contract v${request.contractVersion}; " +
                        "this service implements v${InsightContract.VERSION}.",
                )
                return requestId
            }

            val job = scope.launch {
                enterForeground()
                val builder = StringBuilder()
                try {
                    engine.prepare { percent, stage -> callback.safeProgress(requestId, percent, stage) }
                    engine.generate(request)
                        .catch { error -> throw error }
                        .collect { fragment ->
                            builder.append(fragment)
                            if (request.stream) callback.safeToken(requestId, fragment)
                        }
                    callback.safeComplete(requestId, builder.toString().trim(), null)
                } catch (cancelled: CancellationException) {
                    callback.safeError(requestId, LocalLlmError.CANCELLED, "Cancelled")
                    throw cancelled
                } catch (oom: OutOfMemoryError) {
                    Log.e(TAG, "Out of memory during generation", oom)
                    callback.safeError(requestId, LocalLlmError.OUT_OF_MEMORY, "Out of memory")
                } catch (error: Throwable) {
                    Log.e(TAG, "Generation failed", error)
                    callback.safeError(requestId, LocalLlmError.INTERNAL, error.message.orEmpty())
                } finally {
                    inFlight.remove(requestId)
                    if (inFlight.isEmpty()) exitForeground()
                }
            }
            inFlight[requestId] = job
            return requestId
        }

        override fun cancel(requestId: String) {
            inFlight.remove(requestId)?.cancel()
        }
    }

    // A dead client is an ordinary event, not an error worth crashing the service over.
    private fun IInsightCallback.safeToken(id: String, token: String) =
        runCatching { onToken(id, token) }.logFailure()

    private fun IInsightCallback.safeComplete(id: String, text: String, json: String?) =
        runCatching { onComplete(id, text, json) }.logFailure()

    private fun IInsightCallback.safeError(id: String, code: Int, message: String) =
        runCatching { onError(id, code, message) }.logFailure()

    private fun IInsightCallback.safeProgress(id: String, percent: Int, stage: String) =
        runCatching { onProgress(id, percent, stage) }.logFailure()

    private fun <T> Result<T>.logFailure() = onFailure { error ->
        if (error is RemoteException) Log.d(TAG, "Client went away", error) else throw error
    }

    private fun enterForeground() {
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Generating a summary on this device")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { Log.w(TAG, "Could not enter the foreground", it) }
    }

    private fun exitForeground() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_engine),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notification_channel_engine_description) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "InferenceService"
        private const val CHANNEL_ID = "engine"
        private const val NOTIFICATION_ID = 1001
    }
}
