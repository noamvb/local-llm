package com.noamv.localllm.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Generation ticket for process-owned work that must not begin after a critical trim.
 *
 * A caller takes a ticket before registering its work. Critical trim advances the epoch
 * before cancelling registered jobs. A request that was captured before the trim but had
 * not registered yet therefore becomes inert instead of starting after memory pressure.
 */
internal class ProcessWorkEpoch {
    private val generation = AtomicLong()

    fun ticket(): Long = generation.get()

    fun invalidate(): Long = generation.incrementAndGet()

    fun isCurrent(ticket: Long): Boolean = generation.get() == ticket
}

/**
 * One epoch-aware slot for process-owned model work.
 *
 * Staleness is checked while holding the same lock that owns coalescing. A pre-trim
 * request that registers late therefore returns an inert completed job and can never
 * occupy the slot that a valid post-trim request needs.
 */
internal class EpochProcessJobCoordinator(
    private val scope: CoroutineScope,
    private val workEpoch: ProcessWorkEpoch,
    private val work: suspend () -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private val lock = Any()
    private var activeJob: Job? = null

    fun start(): Job = start(workEpoch.ticket())

    fun start(ticket: Long): Job = synchronized(lock) {
        if (!workEpoch.isCurrent(ticket)) return completedJob()
        activeJob?.takeIf { it.isActive }?.let { return it }

        lateinit var newJob: Job
        newJob = scope.launch(start = CoroutineStart.LAZY) {
            // Trim can invalidate the ticket after synchronous registration but before
            // the dispatcher runs this coroutine. Keep the execution-time fence too.
            if (!workEpoch.isCurrent(ticket)) return@launch
            try {
                work()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                onFailure(error)
            }
        }
        activeJob = newJob
        newJob.invokeOnCompletion {
            synchronized(lock) {
                if (activeJob === newJob) activeJob = null
            }
        }
        newJob.start()
        newJob
    }

    fun cancel() {
        synchronized(lock) { activeJob?.cancel() }
    }

    private fun completedJob(): Job = Job().apply { complete() }
}

/**
 * Process-owned owner-acquisition job boundary reached only from the manager action.
 *
 * Repeated taps share the active job. Cancellation propagates into ModelStore so its
 * resumable partial-preservation rules remain authoritative. The epoch closes the race
 * where critical trim invalidates a request before this coordinator can register it.
 */
internal class OwnerModelAcquisitionCoordinator(
    scope: CoroutineScope,
    workEpoch: ProcessWorkEpoch,
    acquireAndPrepare: suspend () -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    private val jobs = EpochProcessJobCoordinator(
        scope = scope,
        workEpoch = workEpoch,
        work = acquireAndPrepare,
        onFailure = onFailure,
    )

    fun start(): Job = jobs.start()

    internal fun start(ticket: Long): Job = jobs.start(ticket)

    fun cancel() = jobs.cancel()
}
