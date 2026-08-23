package com.noamv.localllm.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceApiCompatibilityTest {
    @Test
    fun acceptsDeclaredV1ServiceApi() {
        assertTrue(ServiceApiCompatibility.supportsV1(1))
    }

    @Test
    fun rejectsOlderServiceApi() {
        assertFalse(ServiceApiCompatibility.supportsV1(0))
    }

    @Test
    fun rejectsFutureApiUntilV1CompatibilityIsExplicitlyDeclared() {
        assertFalse(ServiceApiCompatibility.supportsV1(2))
        assertFalse(ServiceApiCompatibility.supportsV1(Int.MAX_VALUE))
    }

    @Test
    fun bindPhaseSharesOneDeadlineAcrossResolutionAndConnection() {
        assertEquals(4_000L, remainingDeadlineMillis(totalMillis = 5_000, elapsedMillis = 1_000))
        assertEquals(0L, remainingDeadlineMillis(totalMillis = 5_000, elapsedMillis = 5_000))
        assertEquals(0L, remainingDeadlineMillis(totalMillis = 5_000, elapsedMillis = 8_000))
    }
}
