package com.noamv.localllm.service

import com.noamv.localllm.contract.InsightRequest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V1RequestValidatorTest {
    private val valid = InsightRequest(clientId = "test", subject = "test facts")

    @Test
    fun acceptsOrdinaryV1Request() {
        assertNull(V1RequestValidator.errorMessage(valid))
    }

    @Test
    fun rejectsReservedResultSchema() {
        val error = V1RequestValidator.errorMessage(valid.copy(resultSchema = "{}"))
        assertTrue(error.orEmpty().contains("not implemented"))
    }

    @Test
    fun rejectsFutureContract() {
        val error = V1RequestValidator.errorMessage(valid.copy(contractVersion = 2))
        assertTrue(error.orEmpty().contains("contract v2"))
    }

    @Test
    fun rejectsUndefinedPreV1Contract() {
        val error = V1RequestValidator.errorMessage(valid.copy(contractVersion = 0))
        assertTrue(error.orEmpty().contains("not supported"))
    }
}
