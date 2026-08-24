package com.noamv.localllm.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.noamv.localllm.LocalLlmApplication
import com.noamv.localllm.R
import com.noamv.localllm.engine.ModelAcquisitionTransport
import com.noamv.localllm.transfer.ActiveTransferSession
import com.noamv.localllm.transfer.ModelRole
import com.noamv.localllm.transfer.ModelTransferDescriptor
import com.noamv.localllm.transfer.ModelTransferSessionOwner
import com.noamv.localllm.transfer.ModelTransferStatus
import com.noamv.localllm.transfer.PromotionCommitArbiter
import com.noamv.localllm.transfer.TransferAcquisitionPath
import com.noamv.localllm.transfer.TransferByteSnapshot
import com.noamv.localllm.transfer.TransferNetworkBlockReason
import com.noamv.localllm.transfer.TransferNetworkMonitorException
import com.noamv.localllm.transfer.TransferNetworkPolicy
import com.noamv.localllm.transfer.TransferStartDecision
import com.noamv.localllm.transfer.TransferStopReason
import com.noamv.localllm.transfer.acquisitionPath
import com.noamv.localllm.transfer.notificationPresentation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.Call

internal sealed interface ModelTransferCommand {
    data class Start(val policy: TransferNetworkPolicy) : ModelTransferCommand
    data object Cancel : ModelTransferCommand
    data object Invalid : ModelTransferCommand
}

internal fun routeModelTransferCommand(
    action: String?,
    allowMeteredOnce: Boolean,
    startFlags: Int,
): ModelTransferCommand {
    if (startFlags and Service.START_FLAG_RETRY != 0) return ModelTransferCommand.Invalid
    return when (action) {
        ModelTransferService.ACTION_START -> ModelTransferCommand.Start(
            if (allowMeteredOnce) {
                TransferNetworkPolicy.ALLOW_METERED_ONCE
            } else {
                TransferNetworkPolicy.UNMETERED_WIFI
            },
        )
        ModelTransferService.ACTION_CANCEL -> ModelTransferCommand.Cancel
        else -> ModelTransferCommand.Invalid
    }
}

internal enum class ModelTransferLaunchResult {
    STARTED,
    FOREGROUND_START_NOT_ALLOWED,
    FAILED,
}

internal class DeliveredStartIdTracker {
    private var latest = 0

    fun record(startId: Int) {
        latest = startId
    }

    fun latest(): Int = latest
}

internal enum class TransferCancellationDisposition {
    CLEAN_UP_NOW,
    CANCEL_JOB_AND_AWAIT_SETTLEMENT,
    AWAIT_PROMOTION_OR_FAILURE,
}

internal fun transferCancellationDisposition(
    cancellationWon: Boolean,
    jobStarted: Boolean,
): TransferCancellationDisposition = when {
    !jobStarted -> TransferCancellationDisposition.CLEAN_UP_NOW
    cancellationWon -> TransferCancellationDisposition.CANCEL_JOB_AND_AWAIT_SETTLEMENT
    else -> TransferCancellationDisposition.AWAIT_PROMOTION_OR_FAILURE
}

internal fun shouldForcePlatformTimeoutCleanup(
    timedOutSessionId: Long,
    activeSession: ActiveTransferSession?,
): Boolean = activeSession?.id == timedOutSessionId

internal inline fun runPostForegroundTransferSetup(
    setup: () -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    try {
        setup()
    } catch (error: Throwable) {
        onFailure(error)
    }
}

/** User-started owner of the only network acquisition path in LocalLLM. */
class ModelTransferService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    private val sessions = ModelTransferSessionOwner()
    private lateinit var app: LocalLlmApplication
    private lateinit var networks: TransferNetworkMonitor
    private var networkRegistration: TransferNetworkRegistration? = null
    private var processCancellationRegistration: AutoCloseable? = null
    private var activeLease: TransferNetworkLease? = null
    private var activePromotionArbiter: PromotionCommitArbiter? = null
    private var activeJob: Job? = null
    private var activeJobStarted = false
    private var transferDeadlineJob: Job? = null
    private var statusCollection: Job? = null
    private var foregroundStarted = false
    private val deliveredStartIds = DeliveredStartIdTracker()

    override fun onCreate() {
        super.onCreate()
        app = application as LocalLlmApplication
        networks = AndroidTransferNetworkMonitor(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Record every delivered command before routing. Cancel, invalid, and retry
        // commands are starts too, and cleanup must fence the true latest start ID.
        deliveredStartIds.record(startId)
        val command = routeModelTransferCommand(
            action = intent?.action,
            allowMeteredOnce = intent?.getBooleanExtra(EXTRA_ALLOW_METERED_ONCE, false) == true,
            startFlags = flags,
        )
        when (command) {
            is ModelTransferCommand.Start -> handleStart(command.policy)
            ModelTransferCommand.Cancel -> cancelAndStop(TransferStopReason.OWNER_CANCELLED)
            ModelTransferCommand.Invalid -> if (sessions.active() == null) stopLatestStart()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleStart(policy: TransferNetworkPolicy) {
        when (val decision = sessions.start(policy)) {
            is TransferStartDecision.Coalesced -> {
                // A repeated explicit start shares the current run and cannot widen its
                // one-run network policy.
                updateForeground(app.modelTransferStatus.value)
            }
            is TransferStartDecision.Started -> startSession(decision.session)
        }
    }

    private fun startSession(session: ActiveTransferSession) {
        // Platform timing comes first. This preflight notification uses only checked-in
        // constants: no model directory, network, engine, or transfer work is touched
        // before the service is visibly foreground.
        ensureForeground(preflightStatus(session))

        runPostForegroundTransferSetup(
            setup = { startSessionAfterForeground(session) },
            onFailure = { error -> failSetupAndStop(session, error) },
        )
    }

    private fun startSessionAfterForeground(session: ActiveTransferSession) {

        // Installed compatible fallbacks remain the truth; a stale/repeated owner command
        // never relabels them as a completed preferred-model download.
        val requiresAcquisition = app.beginOwnerModelTransfer(session.id, session.policy)
        updateForeground(app.modelTransferStatus.value)
        if (!requiresAcquisition) {
            finishServiceSession(session.id)
            return
        }

        val path = acquisitionPath(app.modelTransferStatus.value)
        val lease = when (path) {
            TransferAcquisitionPath.NETWORK_REQUIRED -> {
                val result = try {
                    networks.acquire(session.policy)
                } catch (error: RuntimeException) {
                    throw TransferNetworkMonitorException(error)
                }
                when (result) {
                    is TransferNetworkLeaseResult.Blocked -> {
                        app.publishOwnerTransferPolicyBlocked(session.id, result.reason)
                        updateForeground(app.modelTransferStatus.value)
                        finishServiceSession(session.id)
                        return
                    }
                    is TransferNetworkLeaseResult.Approved -> result.lease
                }
            }
            TransferAcquisitionPath.LOCAL_VERIFY_AND_PROMOTE -> null
        }
        activeLease = lease
        val promotionArbiter = PromotionCommitArbiter()
        activePromotionArbiter = promotionArbiter

        val transport = if (lease == null) {
            ModelAcquisitionTransport(
                callFactory = NO_NETWORK_CALL_FACTORY,
                validateNetwork = {},
                terminalNetworkBlockReason = { null },
                commitPromotion = promotionArbiter::commit,
            )
        } else {
            ModelAcquisitionTransport(
                callFactory = lease.bind(app.pinnedTransferCallFactory(lease.network)),
                validateNetwork = lease::validateOrThrow,
                terminalNetworkBlockReason = lease::terminalBlockReason,
                commitPromotion = promotionArbiter::commit,
            )
        }

        lateinit var ownedJob: Job
        ownedJob = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                app.performOwnerModelTransfer(
                    sessionId = session.id,
                    transport = transport,
                )
            } catch (_: kotlinx.coroutines.CancellationException) {
                // The cancelling owner publishes its terminal reason before cancelling.
            } catch (error: Throwable) {
                // The application already published a sanitized manager-only category.
                Log.w(TAG, "Owner model transfer failed", error)
            } finally {
                if (activeJob === ownedJob) finishServiceSession(session.id)
            }
        }
        activeJob = ownedJob
        activeJobStarted = false

        processCancellationRegistration = app.foregroundTransferCancellation.register(
            session.id,
        ) { reason -> cancelAndStop(reason) }

        statusCollection = serviceScope.launch {
            app.modelTransferStatus.collectLatest { status ->
                if (sessions.active()?.id == session.id && status.sessionId == session.id) {
                    updateForeground(status)
                }
            }
        }

        if (lease != null) {
            networkRegistration = try {
                networks.monitor(lease) { blocked ->
                    // The monitor closes the lease fence synchronously on its callback thread;
                    // component state remains main-confined.
                    serviceScope.launch {
                        if (sessions.active()?.id == session.id) {
                            cancelAndStop(TransferStopReason.NETWORK_POLICY_LOST, blocked.reason)
                        }
                    }
                }
            } catch (error: RuntimeException) {
                throw TransferNetworkMonitorException(error)
            }

            // Close the lease inspection-to-registration race only after registration
            // ownership is stored, so synchronous failure cannot leak a callback.
            try {
                lease.validateOrThrow()
            } catch (error: com.noamv.localllm.transfer.TransferNetworkPolicyException) {
                cancelAndStop(TransferStopReason.NETWORK_POLICY_LOST, error.reason)
                return
            }
        }

        // Monitoring can synchronously reject a lease that changed after admission.
        if (sessions.active()?.id == session.id) {
            // The deadline claims the same arbiter before cancelling the acquisition Job;
            // coroutine timeout alone would leave a check-to-promotion race.
            transferDeadlineJob = serviceScope.launch {
                delay(TRANSFER_DEADLINE_MILLIS)
                if (sessions.active()?.id == session.id) {
                    cancelAndStop(TransferStopReason.SERVICE_TIMEOUT)
                }
            }
            // Set first: Main.immediate may enter the coroutine and its finally before
            // Job.start() returns, and cleanup must not resurrect a stale true value.
            activeJobStarted = true
            if (!ownedJob.start()) {
                activeJobStarted = false
                finishServiceSession(session.id)
            }
        }
    }

    private fun failSetupAndStop(session: ActiveTransferSession, error: Throwable) {
        activePromotionArbiter?.cancel()
        activeLease?.invalidate(TransferNetworkBlockReason.NO_VALIDATED_NETWORK)
        activeJob?.cancel()
        runCatching {
            app.publishOwnerTransferSetupFailed(session.id, session.policy, error)
            updateForeground(app.modelTransferStatus.value)
        }
        finishServiceSession(session.id)
    }

    private fun cancelAndStop(
        reason: TransferStopReason,
        networkReason: TransferNetworkBlockReason? = null,
    ) {
        val session = sessions.active()
        if (session == null) {
            stopLatestStart()
            return
        }
        // Claim cancellation before touching the network. If promotion already claimed
        // COMMITTING, it owns the terminal outcome and this callback must not publish a
        // contradictory CANCELLED/POLICY_BLOCKED state.
        val cancellationWon = activePromotionArbiter?.cancel() ?: true
        activeLease?.invalidate(
            networkReason ?: TransferNetworkBlockReason.NO_VALIDATED_NETWORK,
        )
        if (cancellationWon) {
            if (networkReason != null) {
                app.publishOwnerTransferPolicyBlocked(session.id, networkReason)
            } else {
                app.publishOwnerTransferCancelled(session.id, reason)
            }
            updateForeground(app.modelTransferStatus.value)
        }
        when (transferCancellationDisposition(cancellationWon, activeJobStarted)) {
            TransferCancellationDisposition.CLEAN_UP_NOW -> {
                activeJob?.cancel()
                finishServiceSession(session.id)
            }
            TransferCancellationDisposition.CANCEL_JOB_AND_AWAIT_SETTLEMENT -> {
                // Service owns the actual acquisition Job. Cancellation reaches OkHttp
                // immediately, while the session/collector/foreground stay owned until
                // ModelStore publishes its exact terminal storage snapshot in finally.
                activeJob?.cancel()
            }
            TransferCancellationDisposition.AWAIT_PROMOTION_OR_FAILURE -> {
                // Promotion already claimed the irreversible outcome. Do not cancel its
                // parent or admit a new session; ownedJob.finally performs exact cleanup.
            }
        }
    }

    private fun finishServiceSession(sessionId: Long) {
        if (!sessions.finish(sessionId)) return
        activeLease?.invalidate(TransferNetworkBlockReason.NO_VALIDATED_NETWORK)
        activeLease = null
        activePromotionArbiter = null
        networkRegistration?.close()
        networkRegistration = null
        processCancellationRegistration?.close()
        processCancellationRegistration = null
        statusCollection?.cancel()
        statusCollection = null
        activeJob = null
        activeJobStarted = false
        transferDeadlineJob?.cancel()
        transferDeadlineJob = null
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopLatestStart()
    }

    private fun stopLatestStart() {
        val latest = deliveredStartIds.latest()
        if (latest > 0) stopSelfResult(latest) else stopSelf()
    }

    private fun preflightStatus(session: ActiveTransferSession): ModelTransferStatus {
        val descriptor = ModelTransferDescriptor(
            role = ModelRole.WRITER,
            modelId = "pending",
            modelName = "Selected writer model",
            expectedBytes = 0L,
        )
        return ModelTransferStatus(
            sessionId = session.id,
            descriptor = descriptor,
            policy = session.policy,
            phase = com.noamv.localllm.transfer.ModelTransferPhase.STARTING,
            bytes = TransferByteSnapshot.create(0L, 0L, 0L),
        )
    }

    private fun ensureForeground(status: ModelTransferStatus) {
        startForeground(
            NOTIFICATION_ID,
            buildNotification(status),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        foregroundStarted = true
    }

    private fun updateForeground(status: ModelTransferStatus) {
        if (!foregroundStarted) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: ModelTransferStatus): Notification {
        val presentation = notificationPresentation(status)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(presentation.title)
            .setContentText(presentation.content)
            .setStyle(Notification.BigTextStyle().bigText(presentation.expandedText))
            .setOnlyAlertOnce(true)
            .setOngoing(status.isActive)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(100, presentation.progress, presentation.indeterminate)

        if (presentation.showCancel) {
            val pending = PendingIntent.getService(
                this,
                CANCEL_REQUEST_CODE,
                Intent(this, ModelTransferService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.model_transfer_cancel),
                    pending,
                ).build(),
            )
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_transfer),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_transfer_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Android 15 data-sync timeout: cancel now and force bounded foreground cleanup. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        handlePlatformTimeout()
    }

    override fun onTimeout(startId: Int) {
        handlePlatformTimeout()
    }

    private fun handlePlatformTimeout() {
        val timedOutSessionId = sessions.active()?.id
        cancelAndStop(TransferStopReason.SERVICE_TIMEOUT)
        if (timedOutSessionId == null) return

        // Normal cleanup waits for ModelStore's exact terminal snapshot. Android's
        // dataSync timeout cannot wait without bound, so a short app-owned watchdog
        // forces this exact service session out of foreground/started state. The global
        // session ID and ModelStore mutex fence any later settling callback/file work.
        serviceScope.launch {
            delay(PLATFORM_TIMEOUT_STOP_GRACE_MILLIS)
            if (shouldForcePlatformTimeoutCleanup(timedOutSessionId, sessions.active())) {
                activeJob?.cancel()
                finishServiceSession(timedOutSessionId)
            }
        }
    }

    override fun onDestroy() {
        sessions.active()?.let { session ->
            val cancellationWon = activePromotionArbiter?.cancel() ?: true
            if (cancellationWon) {
                app.publishOwnerTransferCancelled(
                    session.id,
                    TransferStopReason.SERVICE_DESTROYED,
                )
                activeJob?.cancel()
            }
            activeLease?.invalidate(TransferNetworkBlockReason.NO_VALIDATED_NETWORK)
            sessions.finish(session.id)
        }
        networkRegistration?.close()
        processCancellationRegistration?.close()
        statusCollection?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        internal const val ACTION_START = "com.noamv.localllm.action.START_MODEL_TRANSFER"
        internal const val ACTION_CANCEL = "com.noamv.localllm.action.CANCEL_MODEL_TRANSFER"
        internal const val EXTRA_ALLOW_METERED_ONCE = "allow_metered_once"
        internal const val CHANNEL_ID = "model_transfer"
        internal const val NOTIFICATION_ID = 2001
        internal const val TRANSFER_DEADLINE_MILLIS = 5L * 60L * 60L * 1_000L
        internal const val PLATFORM_TIMEOUT_STOP_GRACE_MILLIS = 2_000L
        private const val CANCEL_REQUEST_CODE = 2002
        private const val TAG = "ModelTransferService"
        private val NO_NETWORK_CALL_FACTORY = Call.Factory {
            error("A complete partial must verify and promote without creating an HTTP call.")
        }

        internal fun start(
            context: Context,
            policy: TransferNetworkPolicy,
        ): ModelTransferLaunchResult {
            val intent = Intent(context, ModelTransferService::class.java)
                .setAction(ACTION_START)
                .putExtra(
                    EXTRA_ALLOW_METERED_ONCE,
                    policy == TransferNetworkPolicy.ALLOW_METERED_ONCE,
                )
            return try {
                context.startForegroundService(intent)
                ModelTransferLaunchResult.STARTED
            } catch (_: ForegroundServiceStartNotAllowedException) {
                ModelTransferLaunchResult.FOREGROUND_START_NOT_ALLOWED
            } catch (_: RuntimeException) {
                ModelTransferLaunchResult.FAILED
            }
        }

        internal fun cancel(context: Context): Boolean = runCatching {
            context.startService(
                Intent(context, ModelTransferService::class.java).setAction(ACTION_CANCEL),
            )
        }.isSuccess
    }
}
