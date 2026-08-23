package com.noamv.localllm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedEngineInitializationTest {

    @Test
    fun `failed initialization closes the just-created native resource`() {
        val resource = FakeResource()

        val failure = runCatching {
            initializeOwnedEngine(
                create = { resource },
                initialize = { error("warmup failed") },
            )
        }.exceptionOrNull()

        assertEquals("warmup failed", failure?.message)
        assertTrue(resource.closed)
    }

    @Test
    fun `successful initialization transfers the original resource without closing it`() {
        val resource = FakeResource()

        val result = initializeOwnedEngine(
            create = { resource },
            initialize = {},
        )

        assertSame(resource, result)
        assertEquals(false, resource.closed)
    }

    private class FakeResource : AutoCloseable {
        var closed = false
            private set

        override fun close() {
            closed = true
        }
    }
}
