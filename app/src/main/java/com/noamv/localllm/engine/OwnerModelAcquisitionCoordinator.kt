package com.noamv.localllm.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Process-owned job boundary reached only from the manager's existing owner action.
 *
 * Repeated taps share the active job. Cancellation propagates into ModelStore so its
 * resumable partial-preservation rules remain authoritative.
 */
internal class OwnerModelAcquisitionCoordinator(
    private val scope: CoroutineScope,
    private val acquireAndPrepare: suspend () -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private val lock = Any()
    private var activeJob: Job? = null

    fun start(): Job = synchronized(lock) {
        activeJob?.takeIf { it.isActive }?.let { return it }

        lateinit var newJob: Job
        newJob = scope.launch(start = CoroutineStart.LAZY) {
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
