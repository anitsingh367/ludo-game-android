package com.example.ludoduel.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.uiDataStore by preferencesDataStore(name = "ui_settings")

/**
 * Display-only preferences (kept apart from the game's data layer): colorblind mode and vibration.
 * Sound on/off is the existing mute setting in [com.example.ludoduel.data.SettingsStore].
 */
class UiSettings private constructor(private val context: Context) {
    private val colorblindKey = booleanPreferencesKey("colorblind")
    private val vibrationKey = booleanPreferencesKey("vibration")

    val colorblind: Flow<Boolean> = context.uiDataStore.data.map { it[colorblindKey] ?: false }
    val vibration: Flow<Boolean> = context.uiDataStore.data.map { it[vibrationKey] ?: true }

    suspend fun setColorblind(on: Boolean) = context.uiDataStore.edit { it[colorblindKey] = on }
    suspend fun setVibration(on: Boolean) = context.uiDataStore.edit { it[vibrationKey] = on }

    companion object {
        @Volatile private var instance: UiSettings? = null

        fun get(context: Context): UiSettings =
            instance ?: synchronized(this) { instance ?: UiSettings(context.applicationContext).also { instance = it } }
    }
}

data class UiPrefs(val colorblind: Boolean, val vibration: Boolean)

@Composable
fun rememberUiPrefs(): UiPrefs {
    val settings = UiSettings.get(LocalContext.current)
    val colorblind by remember(settings) { settings.colorblind }.collectAsState(initial = false)
    val vibration by remember(settings) { settings.vibration }.collectAsState(initial = true)
    return UiPrefs(colorblind, vibration)
}
