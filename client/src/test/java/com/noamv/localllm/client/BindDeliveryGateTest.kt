package com.noamv.localllm.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class BindDeliveryGateTest {

    @Test
    fun `deadline that wins pending state rejects later delivery`() {
        val gate = BindDeliveryGate<Any>()

        assertTrue(gate.failPending())
        assertFalse(gate.tryDeliver(Any()))
        assertEquals(BindDeliveryGate.FailureTarget.Ignore, gate.failConnection())
    }

    @Test
    fun `delivered bind is not killed by its cancelled deadline timer`() {
        val gate = BindDeliveryGate<Any>()
        val session = Any()

        assertTrue(gate.tryDeliver(session))
        assertFalse(gate.failPending())
        val failure = gate.failConnection()
        assertTrue(failure is BindDeliveryGate.FailureTarget.DeliveredValue)
        assertSame(
            session,
            (failure as BindDeliveryGate.FailureTarget.DeliveredValue).value,
        )
    }

    @Test
    fun `cancellation closes a delivered value and is exact once`() {
        val gate = BindDeliveryGate<Any>()
        val session = Any()

        assertTrue(gate.tryDeliver(session))
        assertSame(session, gate.cancel())
        assertNull(gate.cancel())
    }

    @Test
    fun `concurrent delivery and failure have exactly one winner`() {
        val gate = BindDeliveryGate<Any>()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val delivered = AtomicBoolean(false)
        val failed = AtomicBoolean(false)

        val deliveryThread = thread(name = "bind-delivery-test") {
            ready.countDown()
            check(start.await(5, TimeUnit.SECONDS))
            delivered.set(gate.tryDeliver(Any()))
        }
        val failureThread = thread(name = "bind-failure-test") {
            ready.countDown()
            check(start.await(5, TimeUnit.SECONDS))
            failed.set(gate.failPending())
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        deliveryThread.join(5_000)
        failureThread.join(5_000)

        assertFalse(deliveryThread.isAlive)
        assertFalse(failureThread.isAlive)
        assertTrue(delivered.get() xor failed.get())
    }
}
