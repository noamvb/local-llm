package com.noamv.localllm.service

import com.noamv.localllm.transfer.TransferNetworkBlockReason
import com.noamv.localllm.transfer.TransferNetworkDecision
import com.noamv.localllm.transfer.TransferNetworkPolicyException
import okhttp3.Call
import okhttp3.Call.Factory
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class TransferCallFenceTest {
    @Test
    fun `invalidate overlapping call creation synchronously cancels the registered call`() {
        val delegateEntered = CountDownLatch(1)
        val releaseDelegate = CountDownLatch(1)
        val invalidationStarted = CountDownLatch(1)
        val invalidationReturned = CountDownLatch(1)
        val created = AtomicReference<Call?>()
        val failure = AtomicReference<Throwable?>()
        val client = OkHttpClient()
        val delegate = Factory { request ->
            delegateEntered.countDown()
            check(releaseDelegate.await(2, TimeUnit.SECONDS))
            client.newCall(request)
        }
        val fence = TransferCallFence { TransferNetworkDecision.Allowed }
        val factory = fence.bind(delegate)

        val creator = Thread {
            runCatching { factory.newCall(request) }
                .onSuccess(created::set)
                .onFailure(failure::set)
        }
        creator.start()
        assertTrue(delegateEntered.await(2, TimeUnit.SECONDS))

        val invalidator = Thread {
            invalidationStarted.countDown()
            fence.invalidate(TransferNetworkBlockReason.NO_VALIDATED_NETWORK)
            invalidationReturned.countDown()
        }
        invalidator.start()
        assertTrue(invalidationStarted.await(2, TimeUnit.SECONDS))
        assertFalse(invalidationReturned.await(100, TimeUnit.MILLISECONDS))
        releaseDelegate.countDown()

        creator.join(2_000)
        invalidator.join(2_000)
        assertNotNull(created.get())
        assertEquals(null, failure.get())
        assertTrue(created.get()!!.isCanceled())
        assertEquals(0L, invalidationReturned.count)
    }

    @Test
    fun `invalidation winning the race prevents delegate call creation`() {
        val delegateCalls = AtomicInteger()
        val fence = TransferCallFence { TransferNetworkDecision.Allowed }
        val factory = fence.bind(Factory { request ->
            delegateCalls.incrementAndGet()
            OkHttpClient().newCall(request)
        })

        fence.invalidate(TransferNetworkBlockReason.REQUIRES_UNMETERED_WIFI)
        val failure = runCatching { factory.newCall(request) }.exceptionOrNull()

        assertTrue(failure is TransferNetworkPolicyException)
        assertEquals(0, delegateCalls.get())
    }

    @Test
    fun `post foreground setup boundary routes begin admission and factory failures`() {
        listOf(
            IOException("begin"),
            SecurityException("admission"),
            IllegalStateException("call factory"),
        ).forEach { expected ->
            val observed = AtomicReference<Throwable?>()
            runPostForegroundTransferSetup(
                setup = { throw expected },
                onFailure = observed::set,
            )
            assertTrue(observed.get() === expected)
        }
    }

    private val request = Request.Builder().url("https://example.invalid/model").build()
}
