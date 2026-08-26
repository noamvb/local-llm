package com.noamv.localllm.ui.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_preferences")

class ThemePreferences(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.applicationContext.themeDataStore)

    val themeMode: Flow<ThemeMode> = dataStore.data.map { preferences ->
        val stored = preferences[KEY_THEME_MODE]
        enumValues<ThemeMode>().firstOrNull { it.name == stored } ?: DEFAULT_THEME_MODE
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { preferences ->
            preferences[KEY_THEME_MODE] = mode.name
        }
    }

    companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val DEFAULT_THEME_MODE = ThemeMode.SYSTEM
    }
}
