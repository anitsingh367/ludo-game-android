package com.example.ludoduel.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Small local settings: player name, mute toggle, and the room to offer "Rejoin game" for. */
class SettingsStore(private val context: Context) {
    private val nameKey = stringPreferencesKey("player_name")
    private val mutedKey = booleanPreferencesKey("muted")
    private val activeRoomKey = stringPreferencesKey("active_room")

    val name: Flow<String> = context.dataStore.data.map { it[nameKey] ?: DEFAULT_NAME }
    val muted: Flow<Boolean> = context.dataStore.data.map { it[mutedKey] ?: false }
    val activeRoom: Flow<String?> = context.dataStore.data.map { it[activeRoomKey] }

    suspend fun setName(name: String) = context.dataStore.edit { it[nameKey] = name }
    suspend fun setMuted(muted: Boolean) = context.dataStore.edit { it[mutedKey] = muted }
    suspend fun setActiveRoom(code: String?) = context.dataStore.edit {
        if (code == null) it.remove(activeRoomKey) else it[activeRoomKey] = code
    }

    companion object {
        const val DEFAULT_NAME = "Player"
        const val NAME_MIN = 2
        const val NAME_MAX = 16
    }
}
