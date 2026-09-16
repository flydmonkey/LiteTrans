package com.videoconverter.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class SessionSettings(
    val preset: String?,
    val quality: String?,
    val maxWidth: Int?,
    val maxHeight: Int?,
    val output: OutputTarget,
)

private val Context.sessionDataStore by preferencesDataStore(name = "session")

class SessionStore(private val context: Context) {
    val settings: Flow<SessionSettings> = context.sessionDataStore.data.map { preferences ->
        SessionSettings(
            preset = preferences[Keys.PRESET] ?: DEFAULT_PRESET,
            quality = preferences[Keys.QUALITY] ?: DEFAULT_QUALITY,
            maxWidth = preferences[Keys.MAX_WIDTH],
            maxHeight = preferences[Keys.MAX_HEIGHT],
            output = OutputTarget(
                kind = preferences[Keys.OUTPUT_KIND]
                    ?.let { stored -> OutputTarget.Kind.entries.firstOrNull { it.name == stored } }
                    ?: OutputTarget.Kind.Downloads,
                treeUri = preferences[Keys.OUTPUT_TREE_URI],
            ),
        )
    }

    suspend fun load(): SessionSettings = settings.first()

    suspend fun save(settings: SessionSettings) {
        context.sessionDataStore.edit { preferences ->
            preferences.putOrRemove(Keys.PRESET, settings.preset)
            preferences.putOrRemove(Keys.QUALITY, settings.quality)
            preferences.putOrRemove(Keys.MAX_WIDTH, settings.maxWidth)
            preferences.putOrRemove(Keys.MAX_HEIGHT, settings.maxHeight)
            preferences[Keys.OUTPUT_KIND] = settings.output.kind.name
            preferences.putOrRemove(Keys.OUTPUT_TREE_URI, settings.output.treeUri)
        }
    }

    suspend fun saveOutputTarget(output: OutputTarget) {
        context.sessionDataStore.edit { preferences ->
            preferences[Keys.OUTPUT_KIND] = output.kind.name
            preferences.putOrRemove(Keys.OUTPUT_TREE_URI, output.treeUri)
        }
    }

    private object Keys {
        val PRESET = stringPreferencesKey("preset")
        val QUALITY = stringPreferencesKey("quality")
        val MAX_WIDTH = intPreferencesKey("maxWidth")
        val MAX_HEIGHT = intPreferencesKey("maxHeight")
        val OUTPUT_KIND = stringPreferencesKey("outputKind")
        val OUTPUT_TREE_URI = stringPreferencesKey("outputTreeUri")
    }

    companion object {
        const val DEFAULT_PRESET = "mp4-h264"
        const val DEFAULT_QUALITY = "standard"
    }
}

private fun <T> androidx.datastore.preferences.core.MutablePreferences.putOrRemove(
    key: androidx.datastore.preferences.core.Preferences.Key<T>,
    value: T?,
) {
    if (value == null) remove(key) else this[key] = value
}
