package com.noamv.localllm.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ServiceCallbackGateTest {
    @Test
    fun `completion and failure race produces one terminal callback`() {
        val delivered = Collections.synchronizedList(mutableListOf<String>())
        val barrier = CyclicBarrier(3)
        val gate = gate(delivered)
        val complete = thread {
            barrier.await(5, TimeUnit.SECONDS)
            gate.complete("complete")
        }
        val failure = thread {
            barrier.await(5, TimeUnit.SECONDS)
            gate.error(7, "failure")
        }

        barrier.await(5, TimeUnit.SECONDS)
        complete.join(5_000)
        failure.join(5_000)

        assertEquals(1, delivered.size)
        assertTrue(delivered.single() in setOf("complete:complete", "error:7:failure"))
        assertTrue(gate.isTerminal)
    }

    @Test
    fun `admitted token completes before terminal and no token follows terminal`() {
        val tokenEntered = CountDownLatch(1)
        val releaseToken = CountDownLatch(1)
        val terminalReturned = CountDownLatch(1)
        val delivered = Collections.synchronizedList(mutableListOf<String>())
        val gate = ServiceCallbackGate(
            deliverToken = { token ->
                tokenEntered.countDown()
                check(releaseToken.await(5, TimeUnit.SECONDS))
                delivered += "token:$token"
                true
            },
            deliverProgress = { _, _ -> true },
            deliverComplete = { text -> delivered.add("complete:$text") },
            deliverError = { code, message -> delivered.add("error:$code:$message") },
        )
        val token = thread { assertTrue(gate.token("first")) }
        assertTrue(tokenEntered.await(5, TimeUnit.SECONDS))
        val terminal = thread {
            gate.error(3, "cancelled")
            terminalReturned.countDown()
        }

        assertFalse(terminalReturned.await(100, TimeUnit.MILLISECONDS))
        releaseToken.countDown()
        token.join(5_000)
        terminal.join(5_000)

        assertEquals(listOf("token:first", "error:3:cancelled"), delivered)
        assertFalse(gate.token("late"))
        assertFalse(gate.progress(100, "late"))
        assertFalse(gate.complete("late"))
    }

    @Test
    fun `terminal first prevents every nonterminal delivery`() {
        val delivered = mutableListOf<String>()
        val gate = gate(delivered)

        assertTrue(gate.complete("authoritative"))
        assertFalse(gate.error(7, "late"))
        assertFalse(gate.token("late"))
        assertFalse(gate.progress(1, "late"))
        assertEquals(listOf("complete:authoritative"), delivered)
    }

    private fun gate(delivered: MutableList<String>) = ServiceCallbackGate(
        deliverToken = { token -> delivered.add("token:$token") },
        deliverProgress = { percent, stage -> delivered.add("progress:$percent:$stage") },
        deliverComplete = { text -> delivered.add("complete:$text") },
        deliverError = { code, message -> delivered.add("error:$code:$message") },
    )
}
