package com.noamv.localllm

import android.content.ComponentCallbacks2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlmApplicationMemoryTrimTest {
    @Test
    fun `ui hidden and background callbacks do not evict resident model`() {
        assertFalse(shouldUnloadForMemoryTrim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertFalse(shouldUnloadForMemoryTrim(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
    }

    @Test
    fun `critical memory callbacks evict resident model`() {
        assertTrue(shouldUnloadForMemoryTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertTrue(shouldUnloadForMemoryTrim(ComponentCallbacks2.TRIM_MEMORY_MODERATE))
        assertTrue(shouldUnloadForMemoryTrim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }
}
