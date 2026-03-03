package com.example.noytdroid

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "sync_prefs")
private val KEY_SYNC_TREE_URI = stringPreferencesKey("sync_tree_uri")

class SyncFolderPreferences(private val context: Context) {
    suspend fun saveTreeUri(uri: Uri) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SYNC_TREE_URI] = uri.toString()
        }
    }

    suspend fun getTreeUriString(): String? {
        return context.dataStore.data.first()[KEY_SYNC_TREE_URI]
    }
}
