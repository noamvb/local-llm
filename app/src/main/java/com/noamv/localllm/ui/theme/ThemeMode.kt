package com.noamv.localllm.ui.theme

/** How the manager screen picks between the light and dark palettes. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Resolves the theme choice outside Android and Compose so its precedence can be
 * checked by a plain JVM unit test.
 */
fun resolveDarkTheme(mode: ThemeMode, systemInDarkTheme: Boolean): Boolean = when (mode) {
    ThemeMode.SYSTEM -> systemInDarkTheme
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
