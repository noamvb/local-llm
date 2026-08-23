package com.noamv.localllm.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
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
import com.noamv.localllm.security.CallerAuthorizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The bound service that client apps talk to.
 *
 * The manifest permission is the first gate. Every Binder method also authenticates the
 * calling UID against an exact approved package-and-signing-lineage pair; request.clientId
 * is descriptive data and is never an authority signal.
 */
class InferenceService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlight = ConcurrentHashMap<String, InFlightRequest>()
    private lateinit var callerAuthorizer: CallerAuthorizer

    private val engine: LlmEngine
        get() = (application as LocalLlmApplication).engine

    override fun onCreate() {
        super.onCreate()
        callerAuthorizer = CallerAuthorizer(this)
        createNotificationChannel()
    }

    // Binding proves only that the manifest permission gate passed. Exact package-plus-signer
    // authorization happens on each Binder transaction, so a simple bind must stay inert.
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private val binder = object : IInsightService.Stub() {

        override fun getApiVersion(): Int {
            return authorizedServiceCall(
                authorize = { enforceCaller() },
                afterAuthorization = { (application as LocalLlmApplication).prewarmModel() },
                call = { InsightContract.VERSION },
            )
        }

        override fun getEngineState(): String {
            enforceCaller()
            return InsightContract.json.encodeToString(
                EngineStatus.serializer(),
                engine.status.value,
            )
        }

        override fun requestInsight(requestJson: String, callback: IInsightCallback): String {
            val callerUid = enforceCaller()
            val requestId = UUID.randomUUID().toString()

            val request = try {
                InsightContract.json.decodeFromString(InsightRequest.serializer(), requestJson)
            } catch (error: Exception) {
                callback.safeError(requestId, LocalLlmError.INVALID_REQUEST, error.message.orEmpty())
                return requestId
            }

            V1RequestValidator.errorMessage(request)?.let { validationError ->
                callback.safeError(
                    requestId,
                    LocalLlmError.INVALID_REQUEST,
                    validationError,
                )
                return requestId
            }

            val callbackBinder = callback.asBinder()
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val builder = StringBuilder()
                try {
                    engine.prepare { percent, stage ->
                        if (!callback.safeProgress(requestId, percent, stage)) {
                            throw CancellationException("Client callback is unavailable")
                        }
                    }
                    engine.generate(request)
                        .catch { error -> throw error }
                        .collect { fragment ->
                            builder.append(fragment)
                            if (request.stream && !callback.safeToken(requestId, fragment)) {
                                throw CancellationException("Client callback is unavailable")
                            }
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
                }
            }
            lateinit var record: InFlightRequest
            val deathRecipient = IBinder.DeathRecipient {
                inFlight[requestId]
                    ?.takeIf { it === record }
                    ?.job
                    ?.cancel(CancellationException("Client callback Binder died"))
            }
            record = InFlightRequest(callerUid, job, callbackBinder, deathRecipient)
            job.invokeOnCompletion {
                inFlight.remove(requestId, record)
                runCatching { record.callbackBinder.unlinkToDeath(record.deathRecipient, 0) }
            }
            inFlight[requestId] = record
            try {
                record.callbackBinder.linkToDeath(record.deathRecipient, 0)
                if (!record.callbackBinder.isBinderAlive) {
                    job.cancel(CancellationException("Client callback Binder is already dead"))
                } else {
                    job.start()
                }
            } catch (_: RemoteException) {
                job.cancel(CancellationException("Client callback Binder is already dead"))
            }
            return requestId
        }

        override fun cancel(requestId: String) {
            val callerUid = enforceCaller()
            val request = inFlight[requestId] ?: return
            if (request.callerUid != callerUid) {
                throw SecurityException("A client cannot cancel another client's request")
            }
            request.job.cancel()
        }
    }

    private fun enforceCaller(): Int = Binder.getCallingUid().also(callerAuthorizer::enforceAuthorized)

    // A dead client is an ordinary event, not an error worth crashing the service over.
    private fun IInsightCallback.safeToken(id: String, token: String): Boolean =
        runCatching { onToken(id, token) }.callbackDelivered()

    private fun IInsightCallback.safeComplete(id: String, text: String, json: String?): Boolean =
        runCatching { onComplete(id, text, json) }.callbackDelivered()

    private fun IInsightCallback.safeError(id: String, code: Int, message: String): Boolean =
        runCatching { onError(id, code, message) }.callbackDelivered()

    private fun IInsightCallback.safeProgress(id: String, percent: Int, stage: String): Boolean =
        runCatching { onProgress(id, percent, stage) }.callbackDelivered()

    private fun <T> Result<T>.callbackDelivered(): Boolean = fold(
        onSuccess = { true },
        onFailure = { error ->
            if (error is RemoteException) {
                Log.d(TAG, "Client went away", error)
                false
            } else {
                throw error
            }
        },
    )

    private data class InFlightRequest(
        val callerUid: Int,
        val job: Job,
        val callbackBinder: IBinder,
        val deathRecipient: IBinder.DeathRecipient,
    )

    /**
     * Deliberately NOT promoted to the foreground while serving a bound request.
     *
     * The obvious design — promote for the duration of generation so the work survives —
     * is wrong here and actively dangerous. An app targeting API 31+ may not start a
     * foreground service from the background, and the system evaluates that against the
     * *binding client's* live eligibility at the moment startForeground() is called
     * (ActiveServices.canBindingClientStartFgsLocked). A client running an ordinary
     * WorkManager job holds no such eligibility, so the call is denied and
     * ForegroundServiceStartNotAllowedException is thrown into THIS process, gated on
     * this app's targetSdk. The client cannot catch it. This service would die mid
     * generation and the client would see only a DeadObjectException.
     *
     * Promotion is also unnecessary. While a client holds a BIND_AUTO_CREATE binding,
     * this process is pulled up by that binding for as long as it lasts, so it is neither
     * frozen nor a near-term low-memory kill candidate for the ten to sixty seconds a
     * generation takes. A client that needs a stronger guarantee should run its own
     * foreground service around the request; the binding then propagates that state here.
     *
     * See docs/DECISIONS.md.
     */
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

/** Orders optional expensive work strictly after exact Binder-caller authorization. */
internal fun <T> authorizedServiceCall(
    authorize: () -> Unit,
    afterAuthorization: () -> Unit = {},
    call: () -> T,
): T {
    authorize()
    afterAuthorization()
    return call()
}
