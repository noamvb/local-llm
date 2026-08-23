package com.noamv.localllm.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class GenerationSubmissionGateTest {

    @Test
    fun `stopped turn cannot begin a later request transmission`() {
        val gate = GenerationSubmissionGate()
        var transmitted = false

        gate.stop()
        val result = gate.runIfOpen {
            transmitted = true
            "request-id"
        }

        assertNull(result)
        assertFalse(transmitted)
        assertTrue(gate.isStopped)
    }

    @Test
    fun `open turn transmits through the immediate gate`() {
        val gate = GenerationSubmissionGate()

        assertEquals("request-id", gate.runIfOpen { "request-id" })
        assertFalse(gate.isStopped)
        assertNull(gate.runIfOpen { "second-request" })
    }

    @Test
    fun `begin and stop have one linearized winner`() {
        val gate = GenerationSubmissionGate()
        val submissionBegan = CountDownLatch(1)
        val allowReturn = CountDownLatch(1)

        val worker = thread(name = "submission-gate-test") {
            gate.runIfOpen {
                submissionBegan.countDown()
                check(allowReturn.await(5, TimeUnit.SECONDS))
                "request-id"
            }
        }
        assertTrue(submissionBegan.await(5, TimeUnit.SECONDS))

        gate.stop()
        assertTrue(gate.isStopped)
        assertNull(gate.runIfOpen { "late-request" })
        allowReturn.countDown()
        worker.join(5_000)
        assertFalse(worker.isAlive)
    }
}
