package com.noamv.localllm.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class BindRegistrationCleanupTest {

    @Test
    fun `timeout immediately before registration unbinds after bind returns`() {
        val fake = FakeBinding()
        val cleanup = BindRegistrationCleanup(fake::unbind)

        cleanup.requestCleanup()
        assertEquals(0, fake.unbindCount)

        cleanup.runBindingCall { fake.bind() }

        assertFalse(fake.registered)
        assertEquals(1, fake.unbindCount)
    }

    @Test
    fun `timeout during synchronous registration cannot leak the later registration`() {
        val fake = FakeBinding()
        val cleanup = BindRegistrationCleanup(fake::unbind)
        val bindEntered = CountDownLatch(1)
        val allowBindReturn = CountDownLatch(1)

        val bindThread = thread(name = "fake-bind-registration") {
            cleanup.runBindingCall {
                fake.bind {
                    bindEntered.countDown()
                    check(allowBindReturn.await(5, TimeUnit.SECONDS))
                }
            }
        }
        assertTrue(bindEntered.await(5, TimeUnit.SECONDS))

        cleanup.requestCleanup()
        assertEquals(0, fake.unbindCount)
        allowBindReturn.countDown()
        bindThread.join(5_000)

        assertFalse(bindThread.isAlive)
        assertFalse(fake.registered)
        assertEquals(1, fake.unbindCount)
    }

    @Test
    fun `ordinary close after completed registration unbinds exactly once`() {
        val fake = FakeBinding()
        val cleanup = BindRegistrationCleanup(fake::unbind)

        cleanup.runBindingCall { fake.bind() }
        assertTrue(fake.registered)

        cleanup.requestCleanup()
        cleanup.requestCleanup()

        assertFalse(fake.registered)
        assertEquals(1, fake.unbindCount)
    }

    @Test
    fun `completed registration remains bound until cleanup is requested`() {
        val fake = FakeBinding()
        val cleanup = BindRegistrationCleanup(fake::unbind)

        cleanup.runBindingCall { fake.bind() }

        assertTrue(fake.registered)
        assertEquals(0, fake.unbindCount)
    }

    private class FakeBinding {
        @Volatile
        var registered = false
            private set

        @Volatile
        var unbindCount = 0
            private set

        fun bind(beforeReturn: () -> Unit = {}) {
            beforeReturn()
            registered = true
        }

        @Synchronized
        fun unbind() {
            check(registered) { "unbind ran before registration completed" }
            registered = false
            unbindCount++
        }
    }
}
