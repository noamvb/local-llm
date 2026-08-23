package com.noamv.localllm.engine

import com.noamv.localllm.model.ModelBuild
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Internal native-engine lifecycle, separate from the version-one wire status. */
internal enum class EngineLifecyclePhase {
    UNLOADED,
    PREPARING,
    READY,
    GENERATING,
    CLOSING,
}

internal data class EngineLifecycleState(
    val phase: EngineLifecyclePhase,
    val buildId: String? = null,
)

internal data class LoadedEngine<T : AutoCloseable>(
    val build: ModelBuild,
    val handle: T,
)

/**
 * Owns the one native engine instance and serialises every operation that can touch it.
 *
 * LiteRT-LM does not define close-versus-initialize or close-versus-generate as safe.
 * Keeping preparation, generation, and unloading behind this one mutex makes those
 * transitions explicit and prevents a memory trim from invalidating a handle that is
 * still in use. Native generation is deliberately not pre-empted once it has started.
 */
internal class EngineLifecycleCoordinator<T : AutoCloseable>(
    private val onCloseFailure: (Throwable) -> Unit = {},
) {
    private val operationLock = Mutex()

    private val _state = MutableStateFlow(EngineLifecycleState(EngineLifecyclePhase.UNLOADED))
    val state: StateFlow<EngineLifecycleState> = _state.asStateFlow()

    @Volatile
    private var loaded: LoadedEngine<T>? = null

    val activeBuild: ModelBuild?
        get() = loaded?.build

    val isReady: Boolean
        get() = loaded != null

    suspend fun prepare(loader: suspend () -> LoadedEngine<T>): LoadedEngine<T> =
        operationLock.withLock { ensureLoaded(loader) }

    suspend fun <R> use(
        loader: suspend () -> LoadedEngine<T>,
        block: suspend (LoadedEngine<T>) -> R,
    ): R = operationLock.withLock {
        val current = ensureLoaded(loader)
        _state.value = EngineLifecycleState(EngineLifecyclePhase.GENERATING, current.build.id)
        try {
            block(current)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (outOfMemory: OutOfMemoryError) {
            closeLoaded()
            throw outOfMemory
        } finally {
            if (loaded === current) {
                _state.value = EngineLifecycleState(EngineLifecyclePhase.READY, current.build.id)
            }
        }
    }

    suspend fun unload() {
        operationLock.withLock {
            closeLoaded()
        }
    }

    private suspend fun ensureLoaded(loader: suspend () -> LoadedEngine<T>): LoadedEngine<T> {
        loaded?.let { return it }
        _state.value = EngineLifecycleState(EngineLifecyclePhase.PREPARING)
        return try {
            loader().also { created ->
                loaded = created
                _state.value = EngineLifecycleState(EngineLifecyclePhase.READY, created.build.id)
            }
        } catch (cancelled: CancellationException) {
            _state.value = EngineLifecycleState(EngineLifecyclePhase.UNLOADED)
            throw cancelled
        } catch (error: Throwable) {
            _state.value = EngineLifecycleState(EngineLifecyclePhase.UNLOADED)
            throw error
        }
    }

    private fun closeLoaded() {
        val current = loaded ?: run {
            _state.value = EngineLifecycleState(EngineLifecyclePhase.UNLOADED)
            return
        }
        _state.value = EngineLifecycleState(EngineLifecyclePhase.CLOSING, current.build.id)
        // Clear ownership before invoking native close so even a broken close cannot leave
        // a handle that later code mistakes for usable.
        loaded = null
        try {
            current.handle.close()
        } catch (error: Throwable) {
            onCloseFailure(error)
        } finally {
            _state.value = EngineLifecycleState(EngineLifecyclePhase.UNLOADED)
        }
    }
}
