package com.noamv.localllm.ui

import com.noamv.localllm.contract.EngineState
import com.noamv.localllm.contract.EngineStatus
import com.noamv.localllm.contract.InsightRequest
import com.noamv.localllm.engine.EngineTimings
import com.noamv.localllm.engine.InferencePriority
import com.noamv.localllm.engine.InferenceScheduler
import com.noamv.localllm.engine.InferenceSchedulerSnapshot
import com.noamv.localllm.engine.LlmEngine
import com.noamv.localllm.engine.ModelNotInstalledException
import com.noamv.localllm.model.ModelCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ManagerViewModelSchedulerTest {
    @Test
    fun `self-test uses process scheduler and publishes validated terminal text`() = runTest {
        withMainDispatcher {
            val scheduler = InferenceScheduler(this)
            val viewModel = viewModel(FakeEngine { flowOf("  self test complete  ") }, scheduler)

            viewModel.runSelfTest()
            advanceUntilIdle()

            assertEquals("self test complete", viewModel.selfTest.value)
            assertEquals(InferenceSchedulerSnapshot(0, 0, 2, false), scheduler.snapshot())
        }
    }

    @Test
    fun `self-test reports busy instead of bypassing full process queue`() = runTest {
        withMainDispatcher {
            val scheduler = InferenceScheduler(this)
            val release = CompletableDeferred<Unit>()
            scheduler.submit("active", InferencePriority.OPEN_SCREEN) { release.await() }
            scheduler.submit("waiting-1", InferencePriority.OPEN_SCREEN) {}
            scheduler.submit("waiting-2", InferencePriority.OPEN_SCREEN) {}
            val viewModel = viewModel(FakeEngine { flowOf("must not run") }, scheduler)

            viewModel.runSelfTest()
            runCurrent()

            assertTrue(viewModel.selfTest.value.orEmpty().contains("busy"))
            release.complete(Unit)
            advanceUntilIdle()
        }
    }

    @Test
    fun `self-test applies terminal word validation`() = runTest {
        withMainDispatcher {
            val scheduler = InferenceScheduler(this)
            val tooLong = List(61) { "word" }.joinToString(" ")
            val viewModel = viewModel(FakeEngine { flowOf(tooLong) }, scheduler)

            viewModel.runSelfTest()
            advanceUntilIdle()

            assertTrue(viewModel.selfTest.value.orEmpty().startsWith("Self-test failed:"))
            assertEquals(InferenceSchedulerSnapshot(0, 0, 2, false), scheduler.snapshot())
        }
    }

    @Test
    fun `starting a new self-test cancels the old scheduler entry without mixing output`() = runTest {
        withMainDispatcher {
            val calls = AtomicInteger()
            val firstStarted = CompletableDeferred<Unit>()
            val engine = FakeEngine {
                if (calls.getAndIncrement() == 0) {
                    flow {
                        firstStarted.complete(Unit)
                        awaitCancellation()
                    }
                } else {
                    flowOf("replacement result")
                }
            }
            val scheduler = InferenceScheduler(this)
            val viewModel = viewModel(engine, scheduler)

            viewModel.runSelfTest()
            runCurrent()
            firstStarted.await()
            viewModel.runSelfTest()
            advanceUntilIdle()

            assertEquals("replacement result", viewModel.selfTest.value)
            assertEquals(2, calls.get())
            assertEquals(InferenceSchedulerSnapshot(0, 0, 2, false), scheduler.snapshot())
        }
    }

    @Test
    fun `self-test missing-model failure cannot cross the owner acquisition boundary`() = runTest {
        withMainDispatcher {
            val ownerAcquisitions = AtomicInteger()
            val scheduler = InferenceScheduler(this)
            val viewModel = viewModel(
                engine = FakeEngine { flow { throw ModelNotInstalledException(ModelCatalog.E2B_GPU) } },
                scheduler = scheduler,
                startOwnerAcquisition = { ownerAcquisitions.incrementAndGet() },
            )

            viewModel.runSelfTest()
            advanceUntilIdle()

            assertTrue(viewModel.selfTest.value.orEmpty().contains("not installed"))
            assertEquals(0, ownerAcquisitions.get())
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.withMainDispatcher(
        block: suspend kotlinx.coroutines.test.TestScope.() -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun viewModel(
        engine: LlmEngine,
        scheduler: InferenceScheduler,
        startOwnerAcquisition: () -> Unit = {},
    ) = ManagerViewModel(
        engine = engine,
        build = ModelCatalog.E2B_GPU,
        startOwnerAcquisition = startOwnerAcquisition,
        scheduler = scheduler,
    )

    private class FakeEngine(
        private val generator: (InsightRequest) -> Flow<String>,
    ) : LlmEngine {
        override val status = MutableStateFlow(
            EngineStatus(
                state = EngineState.READY,
                modelDownloaded = true,
            ),
        )
        override val timings = MutableStateFlow(EngineTimings())

        override suspend fun prepare(onProgress: (Int, String) -> Unit) = Unit

        override fun generate(request: InsightRequest): Flow<String> = generator(request)

        override suspend fun unload() = Unit

        override fun close() = Unit
    }
}
