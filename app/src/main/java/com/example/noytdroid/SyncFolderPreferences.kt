package com.example.noytdroid

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "sync_prefs")
private val KEY_SYNC_TREE_URI = stringPreferencesKey("sync_tree_uri")
private val KEY_DEBUG_UI_ENABLED = booleanPreferencesKey("debug_ui_enabled")

class SyncFolderPreferences(private val context: Context) {
    val debugUiEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEBUG_UI_ENABLED] ?: false
    }

    suspend fun saveTreeUri(uri: Uri) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SYNC_TREE_URI] = uri.toString()
        }
    }

    suspend fun setDebugUiEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DEBUG_UI_ENABLED] = enabled
        }
    }

    suspend fun getTreeUriString(): String? {
        return context.dataStore.data.first()[KEY_SYNC_TREE_URI]
    }
}
