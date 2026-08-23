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
import com.noamv.localllm.contract.InsightTask
import com.noamv.localllm.contract.LocalLlmError
import com.noamv.localllm.engine.GenerationOutputPolicy
import com.noamv.localllm.engine.InferencePriority
import com.noamv.localllm.engine.InferenceQueueState
import com.noamv.localllm.engine.InferenceScheduler
import com.noamv.localllm.engine.LlmEngine
import com.noamv.localllm.security.CallerAuthorizer
import kotlinx.coroutines.CancellationException
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

    private val inFlight = ConcurrentHashMap<String, InFlightRequest>()
    private val serviceStateLock = Any()
    private var acceptingRequests = true
    private lateinit var callerAuthorizer: CallerAuthorizer

    private val engine: LlmEngine
        get() = (application as LocalLlmApplication).engine
    private val scheduler: InferenceScheduler
        get() = (application as LocalLlmApplication).inferenceScheduler

    override fun onCreate() {
        super.onCreate()
        callerAuthorizer = CallerAuthorizer(this)
        createNotificationChannel()
    }

    // Binding proves only that the manifest permission gate passed. Exact package-plus-signer
    // authorization happens on each Binder transaction, so a simple bind must stay inert.
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        val toCancel = synchronized(serviceStateLock) {
            acceptingRequests = false
            inFlight.values.toList()
        }
        toCancel.forEach { it.cancel() }
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

            V1RequestValidator.rawErrorMessage(requestJson)?.let { validationError ->
                callback.safeError(requestId, LocalLlmError.INVALID_REQUEST, validationError)
                return requestId
            }
            val request = try {
                InsightContract.json.decodeFromString(InsightRequest.serializer(), requestJson)
            } catch (_: Exception) {
                callback.safeError(
                    requestId,
                    LocalLlmError.INVALID_REQUEST,
                    "Request JSON is malformed or uses an unknown contract v1 value.",
                )
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

            val callbackGate = ServiceCallbackGate(
                deliverToken = { fragment -> callback.safeToken(requestId, fragment) },
                deliverProgress = { percent, stage ->
                    callback.safeProgress(requestId, percent, stage)
                },
                deliverComplete = { text -> callback.safeComplete(requestId, text, null) },
                deliverError = { code, message -> callback.safeError(requestId, code, message) },
            )
            val record = InFlightRequest(
                requestId = requestId,
                callerUid = callerUid,
                callbackGate = callbackGate,
                callbackBinder = callback.asBinder(),
            )
            val registered = synchronized(serviceStateLock) {
                if (!acceptingRequests) {
                    false
                } else {
                    check(inFlight.putIfAbsent(requestId, record) == null) {
                        "Generated a duplicate inference request ID"
                    }
                    true
                }
            }
            if (!registered) {
                callbackGate.error(
                    LocalLlmError.BUSY,
                    "Inference is shutting down. Retry later.",
                )
                return requestId
            }

            if (!record.linkCallbackDeath()) {
                callbackGate.error(LocalLlmError.CANCELLED, "Client callback is unavailable.")
                record.finish()
                return requestId
            }

            val registration = try {
                record.register(
                    priority = v1Priority(request),
                    work = { runGeneration(record, request) },
                )
            } catch (error: Throwable) {
                val failure = ServiceFailureMapper.map(error)
                if (failure.code == LocalLlmError.OUT_OF_MEMORY) {
                    Log.e(TAG, "Inference admission failed after memory exhaustion")
                } else {
                    Log.e(
                        TAG,
                        "Inference admission failed: code=${failure.code} " +
                            "retryable=${failure.retryable}",
                        error,
                    )
                }
                callbackGate.error(failure.code, failure.message)
                record.finish()
                return requestId
            }
            when (registration) {
                is RegistrationResult.Admitted -> Unit
                RegistrationResult.Busy -> {
                    callbackGate.error(
                        LocalLlmError.BUSY,
                        "Inference is busy: one request is active and two are waiting.",
                    )
                    record.finish()
                }
                RegistrationResult.Closed -> {
                    callbackGate.error(LocalLlmError.BUSY, "Inference is shutting down. Retry later.")
                    record.finish()
                }
                RegistrationResult.CancelledBeforeAdmission -> {
                    callbackGate.error(LocalLlmError.CANCELLED, "Request cancelled.")
                    record.finish()
                }
            }
            return requestId
        }

        override fun cancel(requestId: String) {
            val callerUid = enforceCaller()
            val request = inFlight[requestId] ?: return
            if (request.callerUid != callerUid) {
                throw SecurityException("A client cannot cancel another client's request")
            }
            request.cancel()
        }
    }

    private suspend fun runGeneration(record: InFlightRequest, request: InsightRequest) {
        val builder = StringBuilder()
        try {
            engine.prepare { percent, stage ->
                if (!record.callbackGate.progress(percent, stage)) record.cancel()
            }
            engine.generate(request).collect { fragment ->
                GenerationOutputPolicy.append(builder, fragment)
                if (request.stream && !record.callbackGate.token(fragment)) {
                    throw CancellationException("Client callback is unavailable")
                }
            }
            val completed = GenerationOutputPolicy.validatedTerminalText(request, builder.toString())
            record.callbackGate.complete(completed)
        } catch (cancelled: CancellationException) {
            val failure = ServiceFailureMapper.map(cancelled)
            record.callbackGate.error(failure.code, failure.message)
            throw cancelled
        } catch (error: Throwable) {
            val failure = ServiceFailureMapper.map(error)
            if (failure.code == LocalLlmError.OUT_OF_MEMORY) {
                Log.e(TAG, "Inference request failed after native memory exhaustion")
            } else {
                Log.e(
                    TAG,
                    "Inference request failed: code=${failure.code} retryable=${failure.retryable}",
                    error,
                )
            }
            record.callbackGate.error(failure.code, failure.message)
            throw error
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
            Log.d(TAG, "Client callback is unavailable", error)
            false
        },
    )

    private inner class InFlightRequest(
        val requestId: String,
        val callerUid: Int,
        val callbackGate: ServiceCallbackGate,
        val callbackBinder: IBinder,
    ) {
        private val lifecycle = RequestLifecycleGate(requestId, scheduler::cancel)
        private val deathLinkLock = Any()
        private var deathLinked = false
        private val deathRecipient = IBinder.DeathRecipient {
            inFlight[requestId]
                ?.takeIf { it === this@InFlightRequest }
                ?.cancel()
        }

        fun linkCallbackDeath(): Boolean {
            return try {
                callbackBinder.linkToDeath(deathRecipient, 0)
                synchronized(deathLinkLock) { deathLinked = true }
                if (callbackBinder.isBinderAlive) {
                    true
                } else {
                    cancel()
                    false
                }
            } catch (_: RemoteException) {
                cancel()
                false
            }
        }

        fun register(
            priority: InferencePriority,
            work: suspend () -> Unit,
        ): RegistrationResult = lifecycle.register {
            scheduler.submit(
                requestId = requestId,
                priority = priority,
                onState = ::handleQueueState,
                block = work,
            )
        }

        fun cancel(): Boolean {
            // Terminal callback ownership is the cancellation linearization point. If
            // completion already won, this becomes best-effort native cancellation only;
            // otherwise cancellation becomes the sole terminal result before the work is
            // removed from the queue or interrupted.
            return lifecycle.cancel {
                callbackGate.error(LocalLlmError.CANCELLED, "Request cancelled.")
            }
        }

        fun finish() {
            if (!lifecycle.terminal()) return
            inFlight.remove(requestId, this)
            val unlink = synchronized(deathLinkLock) {
                if (deathLinked) {
                    deathLinked = false
                    true
                } else {
                    false
                }
            }
            if (unlink) runCatching { callbackBinder.unlinkToDeath(deathRecipient, 0) }
        }

        private fun handleQueueState(state: InferenceQueueState) {
            when (state) {
                InferenceQueueState.QUEUED -> {
                    if (!callbackGate.progress(-1, STAGE_QUEUED)) cancel()
                }
                // ACTIVE may be published synchronously inside scheduler registration.
                // Do not invoke a client callback there: the lazy work must be the first
                // externally cancellable action after registration has linearized.
                InferenceQueueState.ACTIVE -> Unit
                InferenceQueueState.COMPLETED -> {
                    if (!callbackGate.isTerminal) {
                        callbackGate.error(
                            LocalLlmError.INTERNAL,
                            "Inference ended without a terminal result.",
                        )
                    }
                    finish()
                }
                InferenceQueueState.FAILED -> {
                    if (!callbackGate.isTerminal) {
                        callbackGate.error(
                            LocalLlmError.INTERNAL,
                            "Local inference failed unexpectedly.",
                        )
                    }
                    finish()
                }
                InferenceQueueState.CANCELLED -> {
                    callbackGate.error(LocalLlmError.CANCELLED, "Request cancelled.")
                    finish()
                }
                InferenceQueueState.EXPIRED -> {
                    callbackGate.error(
                        LocalLlmError.BUSY,
                        "Inference queue wait expired. Retry later.",
                    )
                    finish()
                }
            }
        }
    }

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
        private const val STAGE_QUEUED = "queued"
    }
}

/**
 * Contract v1 has no execution-context or priority field. Treat every valid v1 request as
 * an open-screen turn; deriving priority from clientId, task, or personal content would be
 * both spoofable and semantically wrong. Future contracts may opt into the other lanes.
 */
internal fun v1Priority(request: InsightRequest): InferencePriority = when (request.task) {
    InsightTask.PERIOD_SUMMARY,
    InsightTask.NUDGE,
    InsightTask.PERIOD_COMPARISON,
    -> InferencePriority.OPEN_SCREEN
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
