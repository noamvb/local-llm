package com.noamv.localllm.engine

import com.noamv.localllm.contract.EngineState
import com.noamv.localllm.contract.EngineStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Arbitrates the two independent owners that contribute to public engine status.
 *
 * Native runtime transitions own the durable status. An owner-started acquisition is a
 * temporary overlay while that runtime is unloaded. This prevents a concurrent client
 * missing-model failure from erasing download progress, and prevents late acquisition
 * completion from overwriting INITIALISING or READY after atomic promotion made the
 * artifact visible to another caller.
 */
internal class EngineStatusCoordinator(initial: EngineStatus) {
    private val lock = Any()
    private val _status = MutableStateFlow(initial)
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private var runtimeStatus = initial
    private var acquisition: ActiveAcquisition? = null
    private var nextAcquisitionId = 0L

    fun publishRuntime(status: EngineStatus) {
        synchronized(lock) {
            runtimeStatus = status
            publishVisibleLocked()
        }
    }

    fun beginAcquisition(status: EngineStatus): Long = synchronized(lock) {
        check(acquisition == null) { "Only one owner acquisition may publish status." }
        check(status.state == EngineState.DOWNLOADING) {
            "Acquisition status must use DOWNLOADING."
        }
        val id = ++nextAcquisitionId
        acquisition = ActiveAcquisition(id, status)
        publishVisibleLocked()
        id
    }

    fun publishAcquisitionProgress(id: Long, percent: Int) {
        synchronized(lock) {
            val current = acquisition?.takeIf { it.id == id } ?: return
            acquisition = current.copy(status = current.status.copy(downloadPercent = percent))
            publishVisibleLocked()
        }
    }

    fun finishAcquisition(id: Long, restingStatus: EngineStatus) {
        synchronized(lock) {
            if (acquisition?.id != id) return
            acquisition = null
            // Runtime work may have started as soon as atomic promotion exposed the file.
            // Only replace an unloaded status; INITIALISING/READY/UNSUPPORTED owns itself.
            if (runtimeStatus.state == EngineState.MODEL_MISSING) {
                runtimeStatus = restingStatus
            }
            publishVisibleLocked()
        }
    }

    private fun publishVisibleLocked() {
        val active = acquisition
        _status.value = when {
            active == null -> runtimeStatus
            runtimeStatus.state == EngineState.INITIALISING -> runtimeStatus
            runtimeStatus.state == EngineState.READY -> runtimeStatus
            else -> active.status
        }
    }

    private data class ActiveAcquisition(
        val id: Long,
        val status: EngineStatus,
    )
}
