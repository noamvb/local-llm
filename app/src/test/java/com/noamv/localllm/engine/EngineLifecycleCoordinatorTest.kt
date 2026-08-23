package com.noamv.localllm.engine

import com.noamv.localllm.model.ModelBackend
import com.noamv.localllm.model.ModelBuild
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EngineLifecycleCoordinatorTest {

    @Test
    fun `concurrent prepare callers share one initialization`() = runTest {
        val coordinator = EngineLifecycleCoordinator<FakeEngine>()
        val initializationStarted = CompletableDeferred<Unit>()
        val allowInitialization = CompletableDeferred<Unit>()
        var initializationCount = 0

        val loader: suspend () -> LoadedEngine<FakeEngine> = {
            initializationCount++
            initializationStarted.complete(Unit)
            allowInitialization.await()
            LoadedEngine(build, FakeEngine())
        }

        val first = launch { coordinator.prepare(loader) }
        initializationStarted.await()
        val second = launch { coordinator.prepare(loader) }
        runCurrent()

        assertEquals(EngineLifecyclePhase.PREPARING, coordinator.state.value.phase)
        assertEquals(1, initializationCount)

        allowInitialization.complete(Unit)
        first.join()
        second.join()

        assertEquals(1, initializationCount)
        assertEquals(EngineLifecyclePhase.READY, coordinator.state.value.phase)
        assertEquals(build.id, coordinator.activeBuild?.id)
    }

    @Test
    fun `unload waits until native generation reaches a safe boundary`() = runTest {
        val coordinator = EngineLifecycleCoordinator<FakeEngine>()
        val fake = FakeEngine()
        coordinator.prepare { LoadedEngine(build, fake) }
        val generationStarted = CompletableDeferred<Unit>()
        val allowGenerationToFinish = CompletableDeferred<Unit>()

        val generation = launch {
            coordinator.use(loader = { error("already loaded") }) {
                generationStarted.complete(Unit)
                allowGenerationToFinish.await()
            }
        }
        generationStarted.await()
        val unload = launch { coordinator.unload() }
        runCurrent()

        assertEquals(EngineLifecyclePhase.GENERATING, coordinator.state.value.phase)
        assertFalse("trim must not close a handle while generation owns it", fake.closed)
        assertFalse("unload should still be waiting", unload.isCompleted)

        allowGenerationToFinish.complete(Unit)
        generation.join()
        unload.join()

        assertTrue(fake.closed)
        assertEquals(1, fake.closeCount)
        assertEquals(EngineLifecyclePhase.UNLOADED, coordinator.state.value.phase)
    }

    @Test
    fun `unload requested during preparation cannot close a half initialized resource`() = runTest {
        val coordinator = EngineLifecycleCoordinator<FakeEngine>()
        val initializationStarted = CompletableDeferred<Unit>()
        val allowInitialization = CompletableDeferred<Unit>()
        val fake = FakeEngine()

        val preparation = launch {
            coordinator.prepare {
                initializationStarted.complete(Unit)
                allowInitialization.await()
                LoadedEngine(build, fake)
            }
        }
        initializationStarted.await()
        val unload = launch { coordinator.unload() }
        runCurrent()

        assertEquals(EngineLifecyclePhase.PREPARING, coordinator.state.value.phase)
        assertFalse(fake.closed)
        assertFalse(unload.isCompleted)

        allowInitialization.complete(Unit)
        preparation.join()
        unload.join()

        assertTrue(fake.closed)
        assertEquals(1, fake.closeCount)
        assertEquals(EngineLifecyclePhase.UNLOADED, coordinator.state.value.phase)
    }

    @Test
    fun `out of memory closes the poisoned handle and permits clean reinitialization`() = runTest {
        val coordinator = EngineLifecycleCoordinator<FakeEngine>()
        val first = FakeEngine()

        val failure = runCatching {
            coordinator.use(loader = { LoadedEngine(build, first) }) {
                throw OutOfMemoryError("synthetic")
            }
        }.exceptionOrNull()

        assertTrue(failure is OutOfMemoryError)
        assertTrue(first.closed)
        assertEquals(EngineLifecyclePhase.UNLOADED, coordinator.state.value.phase)
        assertFalse(coordinator.isReady)

        val replacement = FakeEngine()
        val loaded = coordinator.prepare { LoadedEngine(build, replacement) }

        assertNotSame(first, loaded.handle)
        assertFalse(replacement.closed)
        assertTrue(coordinator.isReady)
        assertEquals(EngineLifecyclePhase.READY, coordinator.state.value.phase)
    }

    @Test
    fun `cancelling a generation releases the operation but keeps the initialized engine`() = runTest {
        val coordinator = EngineLifecycleCoordinator<FakeEngine>()
        val fake = FakeEngine()
        coordinator.prepare { LoadedEngine(build, fake) }
        val generationStarted = CompletableDeferred<Unit>()

        val generation = launch {
            coordinator.use(loader = { error("already loaded") }) {
                generationStarted.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        generationStarted.await()
        generation.cancelAndJoin()

        assertFalse(fake.closed)
        assertTrue(coordinator.isReady)
        assertEquals(EngineLifecyclePhase.READY, coordinator.state.value.phase)
    }

    @Test
    fun `failed preparation returns to unloaded and a later prepare can retry`() = runTest {
        val coordinator = EngineLifecycleCoordinator<FakeEngine>()

        val failure = runCatching {
            coordinator.prepare { error("initialization failed") }
        }.exceptionOrNull()

        assertEquals("initialization failed", failure?.message)
        assertEquals(EngineLifecyclePhase.UNLOADED, coordinator.state.value.phase)

        val recovered = FakeEngine()
        coordinator.prepare { LoadedEngine(build, recovered) }
        assertEquals(EngineLifecyclePhase.READY, coordinator.state.value.phase)
        assertFalse(recovered.closed)
    }

    private class FakeEngine : AutoCloseable {
        var closeCount: Int = 0
            private set
        val closed: Boolean get() = closeCount > 0

        override fun close() {
            closeCount++
        }
    }

    private companion object {
        val build = ModelBuild(
            id = "test-gpu",
            displayName = "Test GPU",
            repo = "example/test",
            fileName = "test.litertlm",
            sizeBytes = 1,
            sha256 = "0".repeat(64),
            backend = ModelBackend.GPU,
        )
    }
}
