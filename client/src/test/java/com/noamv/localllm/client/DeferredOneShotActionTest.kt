package com.noamv.localllm.client

import org.junit.Assert.assertEquals
import org.junit.Test

class DeferredOneShotActionTest {
    @Test
    fun cancellationBeforeRequestIdIsDeliveredWhenIdArrives() {
        val delivered = mutableListOf<String>()
        val action = DeferredOneShotAction<String>(delivered::add)

        action.request()
        action.assign("request-1")

        assertEquals(listOf("request-1"), delivered)
    }

    @Test
    fun requestIdBeforeCancellationIsDeliveredWhenCancelled() {
        val delivered = mutableListOf<String>()
        val action = DeferredOneShotAction<String>(delivered::add)

        action.assign("request-1")
        action.request()

        assertEquals(listOf("request-1"), delivered)
    }

    @Test
    fun repeatedSignalsStillDeliverExactlyOnce() {
        val delivered = mutableListOf<String>()
        val action = DeferredOneShotAction<String>(delivered::add)

        action.request()
        action.request()
        action.assign("request-1")
        action.assign("request-2")
        action.request()

        assertEquals(listOf("request-1"), delivered)
    }
}
