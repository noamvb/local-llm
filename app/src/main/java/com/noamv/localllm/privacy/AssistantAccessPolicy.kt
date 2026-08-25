package com.noamv.localllm.privacy

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.assistantAccessDataStore: DataStore<Preferences> by preferencesDataStore(name = "assistant_access_policy")

class AssistantAccessPolicy(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.applicationContext.assistantAccessDataStore)

    val masterEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[KEY_MASTER_ENABLED] ?: DEFAULT_MASTER_ENABLED
    }

    val cannsheetEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[KEY_CANNSHEET_ENABLED] ?: DEFAULT_CANNSHEET_ENABLED
    }

    val poopScheduleEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[KEY_POOP_SCHEDULE_ENABLED] ?: DEFAULT_POOP_SCHEDULE_ENABLED
    }

    suspend fun setMasterEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_MASTER_ENABLED] = enabled
        }
    }

    suspend fun setCannsheetEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_CANNSHEET_ENABLED] = enabled
        }
    }

    suspend fun setPoopScheduleEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_POOP_SCHEDULE_ENABLED] = enabled
        }
    }

    suspend fun isClientAccessAllowed(packageName: String): Boolean {
        val master = masterEnabled.first()
        if (!master) return false

        return when (packageName) {
            "com.noamv.cannsheet.mobile",
            "com.noamv.cannsheet.mobile.sandbox",
            "com.example.cannsheet",
            "com.example" -> cannsheetEnabled.first()

            "com.noamv.poopschedule",
            "com.noamv.poopschedule.sandbox",
            "com.noamv.poopschedule.debug",
            "com.example.poopschedule" -> poopScheduleEnabled.first()

            else -> false
        }
    }

    suspend fun isSourceQueryAllowed(sourceApp: String): Boolean {
        val master = masterEnabled.first()
        if (!master) return false

        return when (sourceApp.uppercase()) {
            "CANNSHEET" -> cannsheetEnabled.first()
            "POOP_SCHEDULE" -> poopScheduleEnabled.first()
            else -> false
        }
    }

    companion object {
        val KEY_MASTER_ENABLED = booleanPreferencesKey("master_assistant_enabled")
        val KEY_CANNSHEET_ENABLED = booleanPreferencesKey("cannsheet_access_enabled")
        val KEY_POOP_SCHEDULE_ENABLED = booleanPreferencesKey("poop_schedule_access_enabled")

        const val DEFAULT_MASTER_ENABLED = true
        const val DEFAULT_CANNSHEET_ENABLED = true
        const val DEFAULT_POOP_SCHEDULE_ENABLED = true
    }
}
