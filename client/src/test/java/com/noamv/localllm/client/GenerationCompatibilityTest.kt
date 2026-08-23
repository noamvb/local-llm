package com.noamv.localllm.client

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GenerationCompatibilityTest {
    @Test
    fun appendBasedCollectorReceivesOnlyAuthoritativeCompletion() = runTest {
        val appended = StringBuilder()

        flowOf(
            GenerationEvent.Progress(15, "Preparing"),
            GenerationEvent.Draft("draft "),
            GenerationEvent.Draft("draft text"),
            GenerationEvent.Complete("authoritative final"),
        ).completionOnlyText().collect { appended.append(it) }

        assertEquals("authoritative final", appended.toString())
    }

    @Test
    fun completionOnlyResponseIsNotLost() = runTest {
        val values = flowOf(
            GenerationEvent.Complete("complete only"),
        ).completionOnlyText().toList()

        assertEquals(listOf("complete only"), values)
    }

    @Test
    fun compatibilityAdapterThrowsTypedFailure() = runTest {
        val expected = LocalLlmClient.TimedOut(LocalLlmClient.TimeoutPhase.TOTAL)
        var actual: Throwable? = null

        try {
            flowOf(GenerationEvent.Failure(expected)).completionOnlyText().toList()
        } catch (error: Throwable) {
            actual = error
        }

        assertSame(expected, actual)
    }
}
