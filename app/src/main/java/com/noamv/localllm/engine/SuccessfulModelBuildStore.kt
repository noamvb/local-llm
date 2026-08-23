package com.noamv.localllm.engine

import android.content.Context
import com.noamv.localllm.model.ModelBuild

/** Durable pointer to the last model/backend combination proven to initialize. */
internal interface SuccessfulModelBuildStore {
    fun readBuildId(): String?
    fun write(build: ModelBuild): Boolean
}

internal class PreferencesSuccessfulModelBuildStore(context: Context) : SuccessfulModelBuildStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun readBuildId(): String? = preferences.getString(KEY_BUILD_ID, null)

    override fun write(build: ModelBuild): Boolean =
        preferences.edit().putString(KEY_BUILD_ID, build.id).commit()

    private companion object {
        const val PREFERENCES_NAME = "engine_state"
        const val KEY_BUILD_ID = "last_successful_build_id"
    }
}
