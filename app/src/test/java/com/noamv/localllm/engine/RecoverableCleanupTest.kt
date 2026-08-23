package com.noamv.localllm.engine

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoverableCleanupTest {

    @Test
    fun `ordinary cleanup failure remains recoverable`() = runBlocking {
        val expected = IllegalStateException("filesystem unavailable")
        var observed: Exception? = null

        runRecoverableCleanup<Unit>(
            cleanup = { throw expected },
            onSuccess = { error("cleanup must not succeed") },
            onFailure = { observed = it },
        )

        assertSame(expected, observed)
    }

    @Test
    fun `cleanup cancellation is rethrown and cannot publish success`() = runBlocking {
        val events = mutableListOf<String>()
        var cancelled = false

        try {
            runRecoverableCleanup<Unit>(
                cleanup = {
                    events += "cleanup"
                    throw CancellationException("critical trim")
                },
                onSuccess = { events += "success" },
                onFailure = { events += "failure" },
            )
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(listOf("cleanup"), events)
    }
}
