package com.noamv.localllm.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Priority assigned before native inference starts.
 *
 * Native work is deliberately not pre-empted: a higher-priority request can move ahead of
 * queued work, but it never interrupts the request already inside LiteRT-LM.
 */
enum class InferencePriority(internal val rank: Int) {
    BACKGROUND(0),
    OPEN_SCREEN(1),
    LIVE_ASSISTANT(2),
}

/** Owner-visible lifecycle of one admitted scheduler entry. */
enum class InferenceQueueState {
    QUEUED,
    ACTIVE,
    COMPLETED,
    CANCELLED,
    FAILED,
    EXPIRED,
}

sealed interface InferenceAdmission {
    data class Accepted(val requestId: String, val initiallyQueued: Boolean) : InferenceAdmission
    data object Busy : InferenceAdmission
    data object Closed : InferenceAdmission
}

/**
 * One-process scheduler for every native model role.
 *
 * Admission is synchronous so a Binder method can return BUSY without launching an
 * unbounded coroutine. One request may run and at most [maxWaiting] requests may wait.
 * Equal-priority work is FIFO; higher-priority waiting work moves ahead without pre-empting
 * the active native call. Entries are registered before their lazy jobs can start, which
 * makes even an immediately completing block observable and removable exactly once.
 */
class InferenceScheduler(
    private val scope: CoroutineScope,
    private val maxWaiting: Int = DEFAULT_MAX_WAITING,
    private val maxQueueWaitMillis: Long = DEFAULT_MAX_QUEUE_WAIT_MILLIS,
) : AutoCloseable {
    init {
        require(maxWaiting >= 0) { "maxWaiting must not be negative" }
        require(maxQueueWaitMillis > 0) { "maxQueueWaitMillis must be positive" }
    }

    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()
    private val waiting = mutableListOf<Entry>()
    private var active: Entry? = null
    private var nextSequence = 0L
    private var closed = false

    fun submit(
        requestId: String,
        priority: InferencePriority,
        onState: (InferenceQueueState) -> Unit = {},
        block: suspend () -> Unit,
    ): InferenceAdmission {
        require(requestId.isNotBlank()) { "requestId must not be blank" }

        var startNow: Entry? = null
        var queuedEntry: Entry? = null
        val admission: InferenceAdmission
        synchronized(lock) {
            check(requestId !in entries) { "Duplicate requestId: $requestId" }
            if (closed) return InferenceAdmission.Closed
            val canStartImmediately = active == null && waiting.isEmpty()
            if (!canStartImmediately && waiting.size >= maxWaiting) {
                return InferenceAdmission.Busy
            }

            lateinit var entry: Entry
            val work = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    // A request failure belongs to that request. Do not let a caller that
                    // supplied a plain Job scope cancel unrelated queued work.
                    entry.blockFailure.set(failure)
                }
            }
            entry = Entry(
                requestId = requestId,
                priority = priority,
                sequence = nextSequence++,
                work = work,
                onState = onState,
            )
            work.invokeOnCompletion { cause -> finish(entry, entry.blockFailure.get() ?: cause) }
            entries[requestId] = entry

            if (canStartImmediately) {
                entry.lifecycle = EntryLifecycle.ACTIVE
                active = entry
                startNow = entry
                admission = InferenceAdmission.Accepted(requestId, initiallyQueued = false)
            } else {
                entry.lifecycle = EntryLifecycle.WAITING
                entry.queueTimer = scope.launch(start = CoroutineStart.LAZY) {
                    delay(maxQueueWaitMillis)
                    expire(entry)
                }
                waiting += entry
                queuedEntry = entry
                admission = InferenceAdmission.Accepted(requestId, initiallyQueued = true)
            }
        }

        startNow?.let {
            it.notifyState(InferenceQueueState.ACTIVE)
            it.work.start()
        }
        queuedEntry?.let { entry ->
            entry.notifyState(InferenceQueueState.QUEUED)
            var promoted: Entry? = null
            var startTimer = false
            synchronized(lock) {
                if (entry.lifecycle == EntryLifecycle.WAITING) {
                    entry.published = true
                    if (active == null) {
                        promoted = promoteNextLocked()
                    }
                    startTimer = entry.lifecycle == EntryLifecycle.WAITING
                }
            }
            if (startTimer) entry.queueTimer?.start()
            promoted?.let { next ->
                next.notifyState(InferenceQueueState.ACTIVE)
                next.work.start()
            }
        }
        return admission
    }

    /** Cancels only the matching admitted request. Unknown or terminal IDs are harmless. */
    fun cancel(requestId: String): Boolean {
        var queued: Entry? = null
        var next: Entry? = null
        val entry = synchronized(lock) {
            val found = entries[requestId] ?: return false
            found.cancelRequested = true
            if (found.lifecycle == EntryLifecycle.WAITING) {
                waiting.remove(found)
                entries.remove(requestId, found)
                found.lifecycle = EntryLifecycle.TERMINAL
                found.queueTimer?.cancel()
                queued = found
                if (active == null && !closed) next = promoteNextLocked()
            }
            found
        }

        if (queued != null) {
            entry.notifyTerminal(InferenceQueueState.CANCELLED)
        }
        entry.work.cancel(CancellationException("Inference request cancelled"))
        next?.let { promoted ->
            promoted.notifyState(InferenceQueueState.ACTIVE)
            promoted.work.start()
        }
        return true
    }

    /** Current counts for diagnostics; no request IDs or personal payloads are exposed. */
    fun snapshot(): InferenceSchedulerSnapshot = synchronized(lock) {
        InferenceSchedulerSnapshot(
            active = if (active == null) 0 else 1,
            waiting = waiting.size,
            capacity = maxWaiting,
            closed = closed,
        )
    }

    override fun close() {
        val toCancel: List<Entry>
        synchronized(lock) {
            if (closed) return
            closed = true
            toCancel = buildList {
                active?.let(::add)
                addAll(waiting)
            }
            waiting.clear()
            toCancel.forEach { entry ->
                entry.cancelRequested = true
                if (entry.lifecycle == EntryLifecycle.WAITING) {
                    entries.remove(entry.requestId, entry)
                    entry.lifecycle = EntryLifecycle.TERMINAL
                    entry.queueTimer?.cancel()
                }
            }
        }
        toCancel.forEach { entry ->
            if (entry.lifecycle == EntryLifecycle.TERMINAL) {
                entry.notifyTerminal(InferenceQueueState.CANCELLED)
            }
            entry.work.cancel(CancellationException("Inference scheduler closed"))
        }
    }

    private fun expire(entry: Entry) {
        val expired = synchronized(lock) {
            if (entry.lifecycle != EntryLifecycle.WAITING || !entries.remove(entry.requestId, entry)) {
                false
            } else {
                waiting.remove(entry)
                entry.lifecycle = EntryLifecycle.TERMINAL
                true
            }
        }
        if (!expired) return
        entry.notifyTerminal(InferenceQueueState.EXPIRED)
        entry.work.cancel(CancellationException("Inference queue wait expired"))
    }

    private fun finish(entry: Entry, cause: Throwable?) {
        var next: Entry? = null
        var terminalState: InferenceQueueState? = null
        synchronized(lock) {
            when (entry.lifecycle) {
                EntryLifecycle.ACTIVE -> {
                    if (active !== entry) return
                    active = null
                    entries.remove(entry.requestId, entry)
                    entry.lifecycle = EntryLifecycle.TERMINAL
                    terminalState = when {
                        entry.cancelRequested || cause is CancellationException ->
                            InferenceQueueState.CANCELLED
                        cause == null -> InferenceQueueState.COMPLETED
                        else -> InferenceQueueState.FAILED
                    }

                    if (!closed) {
                        next = promoteNextLocked()
                    }
                }
                // A queued cancellation/expiry already emitted its terminal state.
                EntryLifecycle.WAITING, EntryLifecycle.TERMINAL -> return
            }
        }

        terminalState?.let(entry::notifyTerminal)
        next?.let { promoted ->
            promoted.notifyState(InferenceQueueState.ACTIVE)
            promoted.work.start()
        }
    }

    /**
     * Promotes only when the best waiting entry has already published QUEUED. If an
     * earlier/higher-priority submit is still delivering that event, later work cannot
     * jump past it merely because another Binder thread returned from its callback first.
     */
    private fun promoteNextLocked(): Entry? {
        check(active == null)
        val candidate = waiting.minWithOrNull(WAITING_ORDER) ?: return null
        if (!candidate.published) return null
        waiting.remove(candidate)
        candidate.queueTimer?.cancel()
        candidate.lifecycle = EntryLifecycle.ACTIVE
        active = candidate
        return candidate
    }

    private class Entry(
        val requestId: String,
        val priority: InferencePriority,
        val sequence: Long,
        val work: Job,
        val onState: (InferenceQueueState) -> Unit,
    ) {
        var lifecycle: EntryLifecycle = EntryLifecycle.WAITING
        var queueTimer: Job? = null
        var cancelRequested: Boolean = false
        var published: Boolean = false
        val blockFailure = AtomicReference<Throwable?>(null)
        private val terminalNotified = AtomicBoolean(false)

        fun notifyState(state: InferenceQueueState) {
            if (state !in TERMINAL_STATES && terminalNotified.get()) return
            runCatching { onState(state) }
        }

        fun notifyTerminal(state: InferenceQueueState) {
            if (terminalNotified.compareAndSet(false, true)) notifyState(state)
        }

        companion object {
            private val TERMINAL_STATES = setOf(
                InferenceQueueState.COMPLETED,
                InferenceQueueState.CANCELLED,
                InferenceQueueState.FAILED,
                InferenceQueueState.EXPIRED,
            )
        }
    }

    private enum class EntryLifecycle { WAITING, ACTIVE, TERMINAL }

    companion object {
        const val DEFAULT_MAX_WAITING = 2
        const val DEFAULT_MAX_QUEUE_WAIT_MILLIS = 120_000L

        private val WAITING_ORDER = compareByDescending<Entry> { it.priority.rank }
            .thenBy { it.sequence }
    }
}

data class InferenceSchedulerSnapshot(
    val active: Int,
    val waiting: Int,
    val capacity: Int,
    val closed: Boolean,
)
