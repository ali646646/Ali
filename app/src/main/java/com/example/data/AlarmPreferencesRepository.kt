package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AlarmPreferencesState
import com.example.model.AlarmSessionStatus
import com.example.model.AppThemeMode
import com.example.model.AudioStreamPreference
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.alarmDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "battery_charge_alarm_preferences"
)

class AlarmPreferencesRepository(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(
        overrideDataStoreForTesting ?: context.applicationContext.alarmDataStore
    )

    companion object {
        @Volatile
        var overrideDataStoreForTesting: DataStore<Preferences>? = null
    }

    private object Keys {
        val TARGET_PERCENTAGE = intPreferencesKey("target_percentage")
        val SOUND_URI = stringPreferencesKey("sound_uri")
        val SOUND_DISPLAY_NAME = stringPreferencesKey("sound_display_name")
        val SESSION_STATUS = stringPreferencesKey("session_status")
        val AUDIO_STREAM = stringPreferencesKey("audio_stream")
        val LAST_COMPLETED_TARGET = intPreferencesKey("last_completed_target")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }

    val preferencesFlow: Flow<AlarmPreferencesState> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            val rawTarget = prefs[Keys.TARGET_PERCENTAGE]
                ?: AlarmPreferencesState.DEFAULT_TARGET_PERCENTAGE
            val clampedTarget = rawTarget.coerceIn(
                AlarmPreferencesState.MIN_TARGET_PERCENTAGE,
                AlarmPreferencesState.MAX_TARGET_PERCENTAGE
            )

            val status = prefs[Keys.SESSION_STATUS]?.let {
                runCatching { AlarmSessionStatus.valueOf(it) }.getOrNull()
            } ?: AlarmSessionStatus.IDLE

            val stream = prefs[Keys.AUDIO_STREAM]?.let {
                runCatching { AudioStreamPreference.valueOf(it) }.getOrNull()
            } ?: AudioStreamPreference.ALARM

            val themeMode = prefs[Keys.THEME_MODE]?.let {
                runCatching { AppThemeMode.valueOf(it) }.getOrNull()
            } ?: AppThemeMode.SYSTEM

            AlarmPreferencesState(
                targetPercentage = clampedTarget,
                soundUriString = prefs[Keys.SOUND_URI],
                soundDisplayName = prefs[Keys.SOUND_DISPLAY_NAME],
                sessionStatus = status,
                audioStream = stream,
                lastCompletedTarget = prefs[Keys.LAST_COMPLETED_TARGET],
                themeMode = themeMode
            )
        }

    suspend fun readCurrentPreferences(): AlarmPreferencesState = preferencesFlow.first()

    suspend fun setTargetPercentage(percent: Int) {
        val clamped = percent.coerceIn(
            AlarmPreferencesState.MIN_TARGET_PERCENTAGE,
            AlarmPreferencesState.MAX_TARGET_PERCENTAGE
        )
        dataStore.edit { prefs ->
            prefs[Keys.TARGET_PERCENTAGE] = clamped
        }
    }

    suspend fun setSelectedSound(uriString: String?, displayName: String?) {
        dataStore.edit { prefs ->
            if (uriString.isNullOrBlank()) {
                prefs.remove(Keys.SOUND_URI)
            } else {
                prefs[Keys.SOUND_URI] = uriString
            }

            if (displayName.isNullOrBlank()) {
                prefs.remove(Keys.SOUND_DISPLAY_NAME)
            } else {
                prefs[Keys.SOUND_DISPLAY_NAME] = displayName
            }
        }
    }

    suspend fun setAudioStream(stream: AudioStreamPreference) {
        dataStore.edit { prefs ->
            prefs[Keys.AUDIO_STREAM] = stream.name
        }
    }

    suspend fun setThemeMode(mode: AppThemeMode) {
        dataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = mode.name
        }
    }

    suspend fun activateMonitoring() {
        dataStore.edit { prefs ->
            prefs[Keys.SESSION_STATUS] = AlarmSessionStatus.ACTIVE.name
        }
    }

    suspend fun markCompleted(targetPercent: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.SESSION_STATUS] = AlarmSessionStatus.COMPLETED.name
            prefs[Keys.LAST_COMPLETED_TARGET] = targetPercent
        }
    }

    suspend fun deactivateMonitoring() {
        dataStore.edit { prefs ->
            prefs[Keys.SESSION_STATUS] = AlarmSessionStatus.IDLE.name
        }
    }

    suspend fun clearCompletedOnUnplug() {
        dataStore.edit { prefs ->
            if (prefs[Keys.SESSION_STATUS] == AlarmSessionStatus.COMPLETED.name) {
                prefs[Keys.SESSION_STATUS] = AlarmSessionStatus.IDLE.name
            }
        }
    }
}
