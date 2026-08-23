package com.noamv.localllm.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizedServiceCallTest {

    @Test
    fun `failed caller authorization cannot trigger expensive work`() {
        var prewarmed = false

        val failure = runCatching {
            authorizedServiceCall(
                authorize = { throw SecurityException("unapproved package") },
                afterAuthorization = { prewarmed = true },
                call = { 1 },
            )
        }.exceptionOrNull()

        assertTrue(failure is SecurityException)
        assertFalse(prewarmed)
    }

    @Test
    fun `approved caller performs post-authorization work before the result`() {
        val events = mutableListOf<String>()

        val result = authorizedServiceCall(
            authorize = { events += "authorized" },
            afterAuthorization = { events += "prewarm" },
            call = {
                events += "result"
                1
            },
        )

        assertEquals(1, result)
        assertEquals(listOf("authorized", "prewarm", "result"), events)
    }
}
