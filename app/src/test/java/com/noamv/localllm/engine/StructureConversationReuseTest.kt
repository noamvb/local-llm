package com.noamv.localllm.engine

import com.noamv.localllm.model.ModelBackend
import com.noamv.localllm.model.ModelBuild
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StructureConversationReuseTest {
    @Test
    fun reusesConversationWhileTheEngineRemainsResident() {
        val reuse = StructureConversationReuse<FakeConversation>()
        val engine = FakeEngineHandle()
        var created = 0

        val first = reuse.getOrCreate(engine, "instruction") {
            created++
            FakeConversation()
        }
        val second = reuse.getOrCreate(engine, "instruction") {
            created++
            FakeConversation()
        }

        assertSame(first, second)
        assertEquals(1, created)
        assertTrue(!first.closed)
    }

    @Test
    fun recreatesConversationAfterEngineUnload() = runTest {
        val reuse = StructureConversationReuse<FakeConversation>()
        val firstEngine = FakeEngineHandle()
        val secondEngine = FakeEngineHandle()
        val firstLifecycle = EngineLifecycleCoordinator<FakeEngineHandle>(
            beforeClose = reuse::closeFor,
        )
        val secondLifecycle = EngineLifecycleCoordinator<FakeEngineHandle>(
            beforeClose = reuse::closeFor,
        )
        var created = 0

        firstLifecycle.prepare { LoadedEngine(build, firstEngine) }
        val first = reuse.getOrCreate(firstEngine, "instruction") {
            created++
            FakeConversation()
        }
        firstLifecycle.unload()
        secondLifecycle.prepare { LoadedEngine(build, secondEngine) }
        val second = reuse.getOrCreate(secondEngine, "instruction") {
            created++
            FakeConversation()
        }

        assertNotSame(first, second)
        assertTrue(first.closed)
        assertTrue(!second.closed)
        assertEquals(2, created)
    }

    @Test
    fun recreatesConversationWhenSystemInstructionChanges() {
        val reuse = StructureConversationReuse<FakeConversation>()
        val engine = FakeEngineHandle()
        var created = 0

        val first = reuse.getOrCreate(engine, "first") {
            created++
            FakeConversation()
        }
        val second = reuse.getOrCreate(engine, "second") {
            created++
            FakeConversation()
        }

        assertNotSame(first, second)
        assertTrue(first.closed)
        assertEquals(2, created)
    }

    private class FakeEngineHandle : AutoCloseable {
        override fun close() = Unit
    }

    private class FakeConversation : AutoCloseable {
        var closed = false
            private set

        override fun close() {
            closed = true
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
