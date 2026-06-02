package com.example.tempoz.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** App-wide DataStore instance; created once on the application context. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Repository backed by DataStore Preferences, exposing [Flow<AppSettings>] and per-field setters. */
class SettingsRepository(context: Context) {

    private val store = context.applicationContext.settingsDataStore

    val settings: Flow<AppSettings> = store.data.map { prefs ->
        AppSettings(
            clickSound = prefs[KEY_CLICK_SOUND] ?: AppSettings().clickSound,
            subdivision = prefs[KEY_SUBDIVISION] ?: AppSettings().subdivision,
            ghostVolume = prefs[KEY_GHOST_VOLUME] ?: AppSettings().ghostVolume,
            countInBars = prefs[KEY_COUNT_IN_BARS] ?: AppSettings().countInBars,
            defaultTrackVolume = prefs[KEY_DEFAULT_TRACK_VOLUME] ?: AppSettings().defaultTrackVolume,
            defaultClickVolume = prefs[KEY_DEFAULT_CLICK_VOLUME] ?: AppSettings().defaultClickVolume,
        )
    }

    suspend fun setClickSound(value: String) {
        store.edit { it[KEY_CLICK_SOUND] = value }
    }

    suspend fun setSubdivision(value: Int) {
        store.edit { it[KEY_SUBDIVISION] = value }
    }

    suspend fun setGhostVolume(value: Float) {
        store.edit { it[KEY_GHOST_VOLUME] = value }
    }

    suspend fun setCountInBars(value: Int) {
        store.edit { it[KEY_COUNT_IN_BARS] = value }
    }

    suspend fun setDefaultTrackVolume(value: Float) {
        store.edit { it[KEY_DEFAULT_TRACK_VOLUME] = value }
    }

    suspend fun setDefaultClickVolume(value: Float) {
        store.edit { it[KEY_DEFAULT_CLICK_VOLUME] = value }
    }

    companion object {
        private val KEY_CLICK_SOUND = stringPreferencesKey("click_sound")
        private val KEY_SUBDIVISION = intPreferencesKey("subdivision")
        private val KEY_GHOST_VOLUME = floatPreferencesKey("ghost_volume")
        private val KEY_COUNT_IN_BARS = intPreferencesKey("count_in_bars")
        private val KEY_DEFAULT_TRACK_VOLUME = floatPreferencesKey("default_track_volume")
        private val KEY_DEFAULT_CLICK_VOLUME = floatPreferencesKey("default_click_volume")
    }
}
