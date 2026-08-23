package com.noamv.localllm.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import com.noamv.localllm.IInsightCallback
import com.noamv.localllm.IInsightService
import com.noamv.localllm.contract.EngineStatus
import com.noamv.localllm.contract.InsightContract
import com.noamv.localllm.contract.InsightRequest
import com.noamv.localllm.contract.LocalLlmError
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Client-side helper for talking to the LocalLLM inference service.
 *
 * Copy the whole `client/src/main` tree into the client app's module. The .aidl files
 * must keep the `com/noamv/localllm/` package path exactly, and the module needs
 * `buildFeatures { aidl = true }` in its build.gradle.kts.
 *
 * The client app's manifest also needs, at the top level:
 *
 *     <uses-permission android:name="com.noamv.localllm.permission.INFERENCE" />
 *     <queries>
 *         <package android:name="com.noamv.localllm" />
 *     </queries>
 *
 * Every binding resolves an exact component and authenticates the installed package's
 * current signer (or an Android-verified descendant of the pinned signing lineage). A
 * package name is routing information, not an identity check.
 */
class LocalLlmClient(
    context: Context,
    private val timeouts: Timeouts = Timeouts(),
) {
    private val appContext = context.applicationContext ?: context
    private val resolver = TrustedServiceResolver(appContext)

    /** The LocalLLM app is absent, untrusted, incompatible, or refused the bind. */
    class Unavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Terminal failure reported by the service. Codes are [LocalLlmError] values. */
    class InferenceFailed(val code: Int, message: String) : Exception(message)

    /** A local deadline elapsed. [phase] is stable for diagnostics and tests. */
    class TimedOut(val phase: TimeoutPhase) : Exception("LocalLLM timed out during ${phase.wireName}")

    enum class TimeoutPhase(internal val wireName: String) {
        BIND("bind"),
        STATUS("status"),
        FIRST_RESPONSE("first response"),
        TOTAL("generation"),
    }

    data class Timeouts(
        val bindMillis: Long = 5_000,
        val statusMillis: Long = 5_000,
        val firstResponseMillis: Long = 30_000,
        val totalMillis: Long = 120_000,
    ) {
        init {
            require(bindMillis > 0)
            require(statusMillis > 0)
            require(firstResponseMillis > 0)
            require(totalMillis >= firstResponseMillis)
        }
    }

    /** True only when the visible service resolves to the approved LocalLLM lineage. */
    fun isInstalled(): Boolean = runCatching { resolver.resolve() }.isSuccess

    /**
     * Holds an authenticated, version-negotiated binding open while a client screen is
     * visible. Failure is deliberately a no-op; engineStatus()/generateEvents() surface
     * details.
     */
    fun warmup(): AutoCloseable {
        val closed = AtomicBoolean(false)
        val sessionRef = AtomicReference<BoundSession?>(null)
        val job = IPC_SCOPE.launch {
            val session = runCatching { bindSession() }.getOrNull() ?: return@launch
            if (closed.get()) {
                session.close()
                return@launch
            }
            val negotiated = runCatching {
                remoteCall(timeouts.statusMillis, TimeoutPhase.STATUS) {
                    session.throwIfDead()
                    negotiate(session.service)
                    session.throwIfDead()
                }
            }.isSuccess
            if (!negotiated || closed.get()) {
                session.close()
                return@launch
            }
            sessionRef.set(session)
            if (closed.get()) sessionRef.getAndSet(null)?.close()
        }
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                job.cancel()
                sessionRef.getAndSet(null)?.close()
            }
        }
    }

    /** Reads engine state after negotiating v1 on the same authenticated binding. */
    suspend fun engineStatus(): EngineStatus {
        val session = bindSession()
        return try {
            remoteCall(timeouts.statusMillis, TimeoutPhase.STATUS) {
                session.throwIfDead()
                negotiate(session.service)
                val encoded = session.service.engineState
                session.throwIfDead()
                InsightContract.json.decodeFromString(EngineStatus.serializer(), encoded)
            }
        } finally {
            session.close()
        }
    }

    /**
     * Compatibility API for existing v1 callers that append every emitted string.
     *
     * Only the authoritative terminal text is emitted, exactly once. Use
     * [generateEvents] for progress and replaceable streamed drafts.
     */
    @Deprecated("Use generateEvents() for typed progress, draft, completion, and failure events")
    fun generate(request: InsightRequest): Flow<String> =
        generateEvents(request).completionOnlyText()

    /**
     * Runs one request and emits typed progress, replaceable draft, and terminal events.
     *
     * [GenerationEvent.Draft] contains the entire current draft, never an append-only
     * fragment. [GenerationEvent.Complete] contains authoritative `onComplete.text` and
     * may differ from every draft or be the first text returned. A conflated channel keeps
     * the latest event without an unbounded Binder-thread back-pressure queue.
     */
    fun generateEvents(request: InsightRequest): Flow<GenerationEvent> {
        if (request.contractVersion != InsightContract.VERSION) {
            return flow {
                emit(
                    GenerationEvent.Failure(
                        InferenceFailed(
                            LocalLlmError.INVALID_REQUEST,
                            "This client only sends contract v${InsightContract.VERSION}",
                        ),
                    ),
                )
            }
        }
        if (request.resultSchema != null) {
            return flow {
                emit(
                    GenerationEvent.Failure(
                        InferenceFailed(
                            LocalLlmError.INVALID_REQUEST,
                            "Structured result schemas are not implemented by contract v1",
                        ),
                    ),
                )
            }
        }

        return callbackFlow {
            val sessionRef = AtomicReference<BoundSession?>(null)
            val firstResponseTimer = AtomicReference<Job?>(null)
            val submissionJobRef = AtomicReference<Job?>(null)
            val submissionGate = GenerationSubmissionGate()
            val remoteCancellation = DeferredOneShotAction<RemoteRequest> { remote ->
                IPC_SCOPE.launch { runCatching { remote.service.cancel(remote.requestId) } }
            }

            val gate = GenerationEventGate(
                onFirstResponse = { firstResponseTimer.getAndSet(null)?.cancel() },
                deliverEvent = { event -> trySend(event).isSuccess },
                closeFlow = { cause ->
                    if (cause == null) close() else close(cause)
                },
                onTerminalSelected = {
                    // This runs under the delivery gate before its terminal trySend/close.
                    // A submission whose begin-CAS has not won cannot transmit afterward.
                    submissionGate.stop()
                    submissionJobRef.getAndSet(null)?.cancel()
                    firstResponseTimer.getAndSet(null)?.cancel()
                },
            )

            val callback = object : IInsightCallback.Stub() {
                override fun onToken(requestId: String, token: String) {
                    gate.accept(requestId, CallbackEvent.Token(token))
                }

                override fun onComplete(requestId: String, text: String, resultJson: String?) {
                    gate.accept(requestId, CallbackEvent.Complete(text, resultJson))
                }

                override fun onError(requestId: String, code: Int, message: String) {
                    gate.accept(requestId, CallbackEvent.Error(code, message))
                }

                override fun onProgress(requestId: String, percent: Int, stage: String) {
                    gate.accept(requestId, CallbackEvent.Progress(percent, stage))
                }
            }

            val totalTimer = launch {
                delay(timeouts.totalMillis)
                gate.fail(TimedOut(TimeoutPhase.TOTAL))
            }

            val bindingJob = launch {
                val session = try {
                    bindSession { error -> gate.fail(error) }
                } catch (error: Throwable) {
                    gate.fail(error)
                    return@launch
                }
                if (gate.isTerminal) {
                    session.close()
                    return@launch
                }
                sessionRef.set(session)
                if (gate.isTerminal) {
                    sessionRef.getAndSet(null)?.close()
                    return@launch
                }
                firstResponseTimer.set(
                    launch {
                        delay(timeouts.firstResponseMillis)
                        gate.failIfNoFirstResponse(TimedOut(TimeoutPhase.FIRST_RESPONSE))
                    },
                )

                // requestInsight() is synchronous and cannot be forcibly interrupted once it
                // crosses Binder. Keep that worker outside the collector scope so a late ID
                // can still be cancelled, but track and fence it so cancellation or a phase
                // deadline before transmission can never send the request's facts afterward.
                val submissionJob = IPC_SCOPE.launch(start = CoroutineStart.LAZY) {
                    try {
                        remoteCall(timeouts.statusMillis, TimeoutPhase.STATUS) {
                            session.throwIfDead()
                            negotiate(session.service)
                            session.throwIfDead()
                        }
                    } catch (error: Throwable) {
                        gate.fail(error)
                        return@launch
                    }

                    if (submissionGate.isStopped) return@launch
                    val requestId = try {
                        submissionGate.runIfOpen {
                            session.throwIfDead()
                            session.service.requestInsight(
                                InsightContract.json.encodeToString(
                                    InsightRequest.serializer(),
                                    request,
                                ),
                                callback,
                            )
                        } ?: return@launch
                    } catch (error: Throwable) {
                        gate.fail(error)
                        return@launch
                    }

                    remoteCancellation.assign(RemoteRequest(session.service, requestId))
                    if (submissionGate.isStopped) {
                        if (gate.requiresRemoteCancellation) remoteCancellation.request()
                        return@launch
                    }
                    gate.assignRequestId(requestId)
                    if (gate.requiresRemoteCancellation) {
                        remoteCancellation.request()
                    }
                }
                submissionJob.invokeOnCompletion {
                    submissionJobRef.compareAndSet(submissionJob, null)
                }
                submissionJobRef.set(submissionJob)
                if (!submissionGate.isStopped) submissionJob.start() else submissionJob.cancel()
            }

            awaitClose {
                gate.cancelCollector()
                totalTimer.cancel()
                firstResponseTimer.getAndSet(null)?.cancel()
                bindingJob.cancel()
                submissionJobRef.getAndSet(null)?.cancel()
                if (gate.requiresRemoteCancellation) remoteCancellation.request()
                sessionRef.getAndSet(null)?.close()
            }
        }.buffer(Channel.CONFLATED)
    }

    private suspend fun bindSession(
        onDeath: (Throwable) -> Unit = {},
    ): BoundSession {
        val startedAt = SystemClock.elapsedRealtime()
        val component = remoteCall(timeouts.bindMillis, TimeoutPhase.BIND, resolver::resolve)
        val remainingBindMillis = remainingDeadlineMillis(
            totalMillis = timeouts.bindMillis,
            elapsedMillis = SystemClock.elapsedRealtime() - startedAt,
        )
        if (remainingBindMillis <= 0) throw TimedOut(TimeoutPhase.BIND)
        return suspendCancellableCoroutine { continuation ->
            val delivery = BindDeliveryGate<BoundSession>()
            val deathLinkRef = AtomicReference<Pair<IBinder, IBinder.DeathRecipient>?>(null)
            val timeoutRef = AtomicReference<Job?>(null)
            lateinit var connection: ServiceConnection
            val registrationCleanup = BindRegistrationCleanup {
                // Android can register a connection even when bindService reports false.
                runCatching { appContext.unbindService(connection) }
            }

            fun cleanup() {
                timeoutRef.getAndSet(null)?.cancel()
                deathLinkRef.getAndSet(null)?.let { (binder, recipient) ->
                    runCatching { binder.unlinkToDeath(recipient, 0) }
                }
                registrationCleanup.requestCleanup()
            }

            fun failPending(error: Throwable) {
                if (delivery.failPending()) {
                    cleanup()
                    continuation.failIfActive(error)
                }
            }

            fun connectionLost(error: Throwable) {
                when (val target = delivery.failConnection()) {
                    BindDeliveryGate.FailureTarget.PendingContinuation -> {
                        cleanup()
                        continuation.failIfActive(error)
                    }
                    is BindDeliveryGate.FailureTarget.DeliveredValue ->
                        target.value.markDead(error)
                    BindDeliveryGate.FailureTarget.Ignore -> Unit
                }
            }

            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val result = runCatching {
                        resolver.verifyConnected(name)
                        val nonNullBinder = binder
                            ?: throw Unavailable("LocalLLM connected with a null Binder")
                        val service = IInsightService.Stub.asInterface(nonNullBinder)
                            ?: throw Unavailable("LocalLLM returned an invalid Binder")
                        val recipient = IBinder.DeathRecipient {
                            connectionLost(Unavailable("The LocalLLM service process died"))
                        }
                        nonNullBinder.linkToDeath(recipient, 0)
                        // Publish the pair only after linkToDeath succeeds. If terminal
                        // cleanup won while linking, failed delivery closes the session and
                        // retries cleanup with the complete pair available.
                        deathLinkRef.set(nonNullBinder to recipient)
                        BoundSession(service, ::cleanup, onDeath)
                    }
                    result.onSuccess { session ->
                        if (delivery.tryDeliver(session)) {
                            timeoutRef.getAndSet(null)?.cancel()
                            continuation.resumeWith(Result.success(session))
                        } else {
                            session.close()
                        }
                    }.onFailure(::connectionLost)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    connectionLost(Unavailable("The LocalLLM service disconnected"))
                }

                override fun onNullBinding(name: ComponentName?) {
                    connectionLost(Unavailable("LocalLLM returned a null binding"))
                }

                override fun onBindingDied(name: ComponentName?) {
                    connectionLost(
                        Unavailable("The LocalLLM binding died after a package or process change"),
                    )
                }
            }

            timeoutRef.set(
                IPC_SCOPE.launch {
                    delay(remainingBindMillis)
                    failPending(TimedOut(TimeoutPhase.BIND))
                },
            )

            val bindResult = runCatching {
                registrationCleanup.runBindingCall {
                    appContext.bindService(
                        Intent(ACTION_BIND_INFERENCE).setComponent(component),
                        connection,
                        Context.BIND_AUTO_CREATE,
                    )
                }
            }
            val bound = bindResult.getOrElse { error ->
                failPending(Unavailable("Could not bind the LocalLLM inference service", error))
                false
            }
            if (!bound) failPending(Unavailable("Could not bind the LocalLLM inference service"))

            continuation.invokeOnCancellation {
                delivery.cancel()?.close() ?: cleanup()
            }
        }
    }

    private fun negotiate(service: IInsightService) {
        val serviceVersion = service.apiVersion
        if (!ServiceApiCompatibility.supportsV1(serviceVersion)) {
            throw Unavailable(
                "LocalLLM API v$serviceVersion does not declare contract-v1 compatibility",
            )
        }
    }

    private suspend fun <T> remoteCall(
        timeoutMillis: Long,
        phase: TimeoutPhase,
        block: () -> T,
    ): T = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        val timeoutJob = IPC_SCOPE.launch {
            delay(timeoutMillis)
            if (completed.compareAndSet(false, true)) {
                continuation.failIfActive(TimedOut(phase))
            }
        }
        IPC_SCOPE.launch {
            val result = runCatching(block)
            if (completed.compareAndSet(false, true)) {
                timeoutJob.cancel()
                if (continuation.isActive) continuation.resumeWith(result)
            }
        }
        continuation.invokeOnCancellation {
            completed.set(true)
            timeoutJob.cancel()
        }
    }

    private fun <T> CancellableContinuation<T>.failIfActive(error: Throwable) {
        if (isActive) resumeWith(Result.failure(error))
    }

    private class BoundSession(
        val service: IInsightService,
        private val cleanup: () -> Unit,
        private val onDeath: (Throwable) -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val death = AtomicReference<Throwable?>(null)

        fun markDead(error: Throwable) {
            if (!death.compareAndSet(null, error)) return
            close()
            onDeath(error)
        }

        fun throwIfDead() {
            death.get()?.let { throw it }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) cleanup()
        }
    }

    private data class RemoteRequest(
        val service: IInsightService,
        val requestId: String,
    )

    companion object {
        const val LOCALLLM_PACKAGE = "com.noamv.localllm"
        const val INFERENCE_SERVICE_CLASS = "com.noamv.localllm.service.InferenceService"
        const val ACTION_BIND_INFERENCE = "com.noamv.localllm.action.BIND_INFERENCE"
        const val INFERENCE_PERMISSION = "com.noamv.localllm.permission.INFERENCE"

        /**
         * SHA-256 read from the independently downloaded v0.1.5 release APK on
         * 2026-08-23. Android's signing lineage makes this pin survive a legitimate key
         * rotation without accepting an unrelated same-package APK.
         */
        internal val APPROVED_LOCALLLM_SIGNING_LINEAGE = setOf(
            "f1f2632b76d0edbd40c839a86c7d6eec63ec74f3d5095726a6f676ba1ad3b95d",
        )

        private val IPC_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

internal fun remainingDeadlineMillis(totalMillis: Long, elapsedMillis: Long): Long =
    (totalMillis - elapsedMillis.coerceAtLeast(0)).coerceAtLeast(0)

/** Future API numbers are fail-closed until their v1 compatibility is explicitly declared. */
internal object ServiceApiCompatibility {
    private val V1_CAPABLE_API_VERSIONS = setOf(1)

    fun supportsV1(serviceApiVersion: Int): Boolean = serviceApiVersion in V1_CAPABLE_API_VERSIONS
}
