package com.noamv.localllm.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Theme selection is pure so its precedence can be checked without Android or a device.
 */
class ThemeModeTest {

    @Test
    fun `system mode follows the dark system setting`() {
        assertTrue(resolveDarkTheme(ThemeMode.SYSTEM, systemInDarkTheme = true))
    }

    @Test
    fun `system mode follows the light system setting`() {
        assertFalse(resolveDarkTheme(ThemeMode.SYSTEM, systemInDarkTheme = false))
    }

    @Test
    fun `light mode overrides a dark system setting`() {
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, systemInDarkTheme = true))
    }

    @Test
    fun `light mode stays light when the system is light`() {
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, systemInDarkTheme = false))
    }

    @Test
    fun `dark mode stays dark when the system is dark`() {
        assertTrue(resolveDarkTheme(ThemeMode.DARK, systemInDarkTheme = true))
    }

    @Test
    fun `dark mode overrides a light system setting`() {
        assertTrue(resolveDarkTheme(ThemeMode.DARK, systemInDarkTheme = false))
    }
}
