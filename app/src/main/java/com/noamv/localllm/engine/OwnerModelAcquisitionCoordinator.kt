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
 * Process-owned owner-acquisition job boundary reached only from the manager action.
 *
 * Repeated taps share the active job. Cancellation propagates into ModelStore so its
 * resumable partial-preservation rules remain authoritative. The epoch closes the race
 * where critical trim invalidates a request before this coordinator can register it.
 */
internal class OwnerModelAcquisitionCoordinator(
    private val scope: CoroutineScope,
    private val workEpoch: ProcessWorkEpoch,
    private val acquireAndPrepare: suspend () -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private val lock = Any()
    private var activeJob: Job? = null

    fun start(): Job = start(workEpoch.ticket())

    internal fun start(ticket: Long): Job = synchronized(lock) {
        activeJob?.takeIf { it.isActive }?.let { return it }

        lateinit var newJob: Job
        newJob = scope.launch(start = CoroutineStart.LAZY) {
            if (!workEpoch.isCurrent(ticket)) return@launch
            try {
                acquireAndPrepare()
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
}
