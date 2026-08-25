package com.noamv.localllm

import android.app.Application
import android.net.Network
import android.util.Log
import com.noamv.localllm.engine.ArtifactAcquisitionStage
import com.noamv.localllm.engine.EpochProcessJobCoordinator
import com.noamv.localllm.engine.InferenceScheduler
import com.noamv.localllm.engine.LiteRtEngine
import com.noamv.localllm.engine.LlmEngine
import com.noamv.localllm.engine.ModelAcquisitionTransport
import com.noamv.localllm.engine.ModelAcquirer
import com.noamv.localllm.engine.ProcessWorkEpoch
import com.noamv.localllm.engine.ModelResidencyCoordinator
import com.noamv.localllm.engine.shouldPrewarmOnBind
import com.noamv.localllm.history.AssistantDatabase
import com.noamv.localllm.history.AssistantHistoryRepository
import com.noamv.localllm.model.ModelStore
import com.noamv.localllm.orchestrator.AssistantOrchestratorV2
import com.noamv.localllm.privacy.AssistantAccessPolicy
import com.noamv.localllm.transfer.ForegroundTransferCancellationRegistry
import com.noamv.localllm.transfer.ModelRole
import com.noamv.localllm.transfer.ModelTransferDescriptor
import com.noamv.localllm.transfer.ModelTransferPhase
import com.noamv.localllm.transfer.ModelTransferStatus
import com.noamv.localllm.transfer.ModelTransferStatusCoordinator
import com.noamv.localllm.transfer.TransferNetworkPolicy
import com.noamv.localllm.transfer.TransferStopReason
import com.noamv.localllm.transfer.resolveTransferNetworkBlockReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Dns
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

    /**
     * Scope for work that has to outlive whatever screen or service binding started it.
     *
     * It is never cancelled: it is the process, not a component. Component-owned work
     * still registers cancellation with the process scheduler before it starts.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // Model downloads are large; the default ten-second read timeout is far too short.
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    internal fun pinnedTransferCallFactory(network: Network): Call.Factory =
        httpClient.newBuilder()
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
            })
            // A transfer is pinned to one approved Network. Transparent retry could
            // otherwise recreate a request after that network lost eligibility.
            .retryOnConnectionFailure(false)
            .build()

    val modelStore: ModelStore by lazy { ModelStore(this, httpClient) }

    val assistantDatabase: AssistantDatabase by lazy {
        AssistantDatabase.getInstance(this)
    }

    val historyRepository: AssistantHistoryRepository by lazy {
        AssistantHistoryRepository(assistantDatabase.historyDao())
    }

    val accessPolicy: AssistantAccessPolicy by lazy {
        AssistantAccessPolicy(this)
    }

    internal val residencyCoordinator: ModelResidencyCoordinator by lazy {
        ModelResidencyCoordinator(this)
    }

    internal val factProviderClient: com.noamv.localllm.orchestrator.FactProviderClient by lazy {
        com.noamv.localllm.orchestrator.FactProviderClient(this)
    }

    internal val assistantOrchestrator: AssistantOrchestratorV2 by lazy {
        AssistantOrchestratorV2(
            engine = engine,
            historyRepository = historyRepository,
            accessPolicy = accessPolicy,
            residencyCoordinator = residencyCoordinator,
            factProviderQuery = factProviderClient::queryFacts,
        )
    }

    private val engineDelegate = lazy { LiteRtEngine(this, modelStore) }
    val engine: LlmEngine
        get() = engineDelegate.value
    private val modelAcquirer: ModelAcquirer
        get() = engineDelegate.value

    private val preferredBuild
        get() = engineDelegate.value.preferredBuild

    private val transferStatusCoordinator by lazy {
        ModelTransferStatusCoordinator(
            ModelTransferDescriptor(
                role = ModelRole.WRITER,
                modelId = preferredBuild.id,
                modelName = preferredBuild.displayName,
                expectedBytes = preferredBuild.sizeBytes,
            ),
        )
    }

    internal val modelTransferStatus: StateFlow<ModelTransferStatus>
        get() = transferStatusCoordinator.status

    internal val foregroundTransferCancellation = ForegroundTransferCancellationRegistry()

    private var idleCheckJob: kotlinx.coroutines.Job? = null
    @Volatile
    private var lastActivityTime: Long = System.currentTimeMillis()

    fun recordInferenceActivity() {
        lastActivityTime = System.currentTimeMillis()
        scheduleIdleUnloadCheck()
    }

    internal fun scheduleIdleUnloadCheck(delayMillis: Long = ModelResidencyCoordinator.IDLE_TIMEOUT_MILLIS) {
        idleCheckJob?.cancel()
        idleCheckJob = applicationScope.launch {
            kotlinx.coroutines.delay(delayMillis)
            if (residencyCoordinator.isIdleExpired(lastActivityTime)) {
                if (engineDelegate.isInitialized()) {
                    engine.unload()
                }
            }
        }
    }

    /** One admission owner for every native generation role in this process. */
    val inferenceScheduler: InferenceScheduler by lazy {
        InferenceScheduler(
            scope = applicationScope,
            onActivityFinished = ::recordInferenceActivity,
        )
    }

    private val processWorkEpoch = ProcessWorkEpoch()

    private val installedPrewarm by lazy {
        EpochProcessJobCoordinator(
            scope = applicationScope,
            workEpoch = processWorkEpoch,
            work = {
                // Even reading engine status can initialize the lazy engine and inspect
                // model storage. Keep that work off the Binder/main caller thread.
                if (shouldPrewarmOnBind(engine.status.value)) engine.prepare()
            },
            onFailure = { error -> Log.w(TAG, "Installed model preparation failed", error) },
        )
    }

    /**
     * Begins typed status after the foreground service is visible. Returns false when a
     * compatible installed build already satisfies the action; no network interface is
     * reached in that case.
     */
    internal fun beginOwnerModelTransfer(
        sessionId: Long,
        policy: TransferNetworkPolicy,
    ): Boolean {
        val engineStatus = engine.status.value
        val selectedBuild = if (engineStatus.modelDownloaded) {
            engineStatus.modelId?.let(com.noamv.localllm.model.ModelCatalog::byId)
                ?: preferredBuild
        } else {
            preferredBuild
        }
        transferStatusCoordinator.begin(
            sessionId = sessionId,
            policy = policy,
            activeDescriptor = ModelTransferDescriptor(
                role = ModelRole.WRITER,
                modelId = selectedBuild.id,
                modelName = selectedBuild.displayName,
                expectedBytes = selectedBuild.sizeBytes,
            ),
            partialBytes = modelStore.partialBytes(selectedBuild),
        )
        if (engineStatus.modelDownloaded) {
            transferStatusCoordinator.publish(
                sessionId = sessionId,
                phase = ModelTransferPhase.COMPLETED,
                availableBytes = selectedBuild.sizeBytes,
            )
            return false
        }
        return true
    }

    internal suspend fun performOwnerModelTransfer(
        sessionId: Long,
        transport: ModelAcquisitionTransport,
    ) {
        try {
            modelAcquirer.acquirePreferredArtifact(
                transport = transport,
                onProgress = { progress ->
                    transferStatusCoordinator.publish(
                        sessionId = sessionId,
                        phase = when (progress.stage) {
                            ArtifactAcquisitionStage.DOWNLOADING -> ModelTransferPhase.DOWNLOADING
                            ArtifactAcquisitionStage.VERIFYING -> ModelTransferPhase.VERIFYING
                            ArtifactAcquisitionStage.INSTALLING -> ModelTransferPhase.INSTALLING
                        },
                        availableBytes = progress.availableBytes,
                        transferredThisRunBytes = progress.transferredThisRunBytes,
                    )
                },
                onTerminalSnapshot = { snapshot ->
                    if (snapshot.promotionCommitted) {
                        transferStatusCoordinator.completeCommittedPromotion(
                            sessionId = sessionId,
                            availableBytes = snapshot.availableBytes,
                            transferredThisRunBytes = snapshot.transferredThisRunBytes,
                        )
                    } else {
                        transferStatusCoordinator.refreshStorageBytes(
                            sessionId = sessionId,
                            availableBytes = snapshot.availableBytes,
                            transferredThisRunBytes = snapshot.transferredThisRunBytes,
                        )
                    }
                },
            )
            transferStatusCoordinator.publish(
                sessionId = sessionId,
                phase = ModelTransferPhase.COMPLETED,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val policyReason = resolveTransferNetworkBlockReason(
                error = error,
                leaseTerminalReason = transport.terminalNetworkBlockReason(),
            )
            if (policyReason != null) {
                transferStatusCoordinator.block(sessionId, policyReason)
            } else {
                transferStatusCoordinator.fail(sessionId, error)
            }
            throw error
        }
    }

    internal fun publishOwnerTransferCancelled(sessionId: Long, reason: TransferStopReason) =
        transferStatusCoordinator.cancel(sessionId, reason)

    internal fun publishOwnerTransferPolicyBlocked(
        sessionId: Long,
        reason: com.noamv.localllm.transfer.TransferNetworkBlockReason,
    ) = transferStatusCoordinator.block(sessionId, reason)

    internal fun publishOwnerTransferSetupFailed(
        sessionId: Long,
        policy: TransferNetworkPolicy,
        error: Throwable,
    ) = transferStatusCoordinator.failSetup(sessionId, policy, error)

    /**
     * Loads an already-downloaded model ahead of the first request, so Engine.initialize()
     * overlaps with the user reading the screen instead of blocking the insight card.
     *
     * Deliberately never downloads: this fires on a bind, and a bind must not be able to
     * start a multi-gigabyte transfer. When no file is present this does nothing; only
     * the explicit foreground transfer service may cross the owner acquisition boundary.
     */
    fun prewarmModel() {
        val ticket = processWorkEpoch.ticket()
        // Registration is synchronous, but engine/status disk work remains inside the
        // process coroutine. This lets critical trim cancel the complete request instead
        // of racing an untracked wrapper that could register preparation afterward.
        installedPrewarm.start(ticket)
    }

    /** Explicit manager action that loads installed artifacts and has no acquisition type. */
    internal fun prepareInstalledModel() {
        val ticket = processWorkEpoch.ticket()
        installedPrewarm.start(ticket)
    }

    // The granular TRIM_MEMORY_* levels are deprecated from API 34, but no replacement
    // signal exists for "the system is about to kill you". Releasing the model is drastic;
    // being killed outright mid-generation is worse, so the next request pays a reload.
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < TRIM_MEMORY_RUNNING_CRITICAL) return

        // The service-owned transfer Job is cancelled synchronously through its registry;
        // ModelStore then retains safely written partial bytes. Native generation itself
        // is not pre-empted: unload waits for a safe lifecycle boundary.
        processWorkEpoch.invalidate()
        foregroundTransferCancellation.cancel(TransferStopReason.CRITICAL_MEMORY)
        installedPrewarm.cancel()
        if (!engineDelegate.isInitialized()) return
        applicationScope.launch { engine.unload() }
    }

    companion object {
        private const val TAG = "LocalLlmApplication"
    }
}
