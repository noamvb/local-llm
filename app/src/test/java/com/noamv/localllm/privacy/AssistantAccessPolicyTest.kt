package com.noamv.localllm.privacy

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AssistantAccessPolicyTest {

    private lateinit var fakeDataStore: FakeDataStore
    private lateinit var policy: AssistantAccessPolicy

    @Before
    fun setUp() {
        fakeDataStore = FakeDataStore()
        policy = AssistantAccessPolicy(fakeDataStore)
    }

    @Test
    fun testDefaultsAreAllEnabled() = runTest {
        assertTrue(policy.masterEnabled.first())
        assertTrue(policy.cannsheetEnabled.first())
        assertTrue(policy.poopScheduleEnabled.first())
        assertTrue(policy.isClientAccessAllowed("com.example.cannsheet"))
        assertTrue(policy.isClientAccessAllowed("com.example.poopschedule"))
        assertTrue(policy.isSourceQueryAllowed("CANNSHEET"))
        assertTrue(policy.isSourceQueryAllowed("POOP_SCHEDULE"))
    }

    @Test
    fun testMasterDisabledBlocksAllClientsAndSources() = runTest {
        policy.setMasterEnabled(false)

        assertFalse(policy.masterEnabled.first())
        assertFalse(policy.isClientAccessAllowed("com.example.cannsheet"))
        assertFalse(policy.isClientAccessAllowed("com.example.poopschedule"))
        assertFalse(policy.isSourceQueryAllowed("CANNSHEET"))
        assertFalse(policy.isSourceQueryAllowed("POOP_SCHEDULE"))
    }

    @Test
    fun testPerAppAccessEnforcement() = runTest {
        // Disable Cannsheet only
        policy.setCannsheetEnabled(false)

        assertTrue(policy.masterEnabled.first())
        assertFalse(policy.cannsheetEnabled.first())
        assertTrue(policy.poopScheduleEnabled.first())

        assertFalse(policy.isClientAccessAllowed("com.example.cannsheet"))
        assertTrue(policy.isClientAccessAllowed("com.example.poopschedule"))

        assertFalse(policy.isSourceQueryAllowed("CANNSHEET"))
        assertTrue(policy.isSourceQueryAllowed("POOP_SCHEDULE"))

        // Re-enable Cannsheet and disable Poop Schedule
        policy.setCannsheetEnabled(true)
        policy.setPoopScheduleEnabled(false)

        assertTrue(policy.isClientAccessAllowed("com.example.cannsheet"))
        assertFalse(policy.isClientAccessAllowed("com.example.poopschedule"))

        assertTrue(policy.isSourceQueryAllowed("CANNSHEET"))
        assertFalse(policy.isSourceQueryAllowed("POOP_SCHEDULE"))
    }
}

class FakeDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(mutablePreferencesOf())

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val current = state.value.toMutablePreferences()
        val updated = transform(current)
        state.value = updated
        return updated
    }
}
