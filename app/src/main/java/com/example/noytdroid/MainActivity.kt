package com.example.noytdroid

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Context.dataStore by preferencesDataStore(name = "channels")
private val channelsKey = stringSetPreferencesKey("channels")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        val bridgeModule = Python.getInstance().getModule("bridge")
        val pyResult = bridgeModule.callAttr("hello").toString()
        val ytDlpVersion = bridgeModule.callAttr("ytdlp_version").toString()

        Toast.makeText(this, pyResult, Toast.LENGTH_SHORT).show()
        Toast.makeText(this, ytDlpVersion, Toast.LENGTH_SHORT).show()

        setContent {
            MaterialTheme {
                MainScreen()
            }
        }
    }
}

@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val channels by context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences -> preferences[channelsKey] ?: emptySet() }
        .collectAsState(initial = emptySet())

    var showDialog by rememberSaveable { mutableStateOf(false) }
    var inputChannelId by rememberSaveable { mutableStateOf("") }
    var isRunning by rememberSaveable { mutableStateOf(false) }
    var runResults by remember { mutableStateOf(emptyList<Pair<String, String?>>()) }

    val sortedChannels = remember(channels) { channels.toList().sorted() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Channels",
            style = MaterialTheme.typography.headlineMedium
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(sortedChannels) { channelId ->
                Text(
                    text = channelId,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        Button(
            onClick = { showDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Add channel")
        }

        Button(
            onClick = {
                isRunning = true
                runResults = emptyList()
                scope.launch(Dispatchers.IO) {
                    val bridgeModule = Python.getInstance().getModule("bridge")
                    val results = sortedChannels.map { channelId ->
                        val url = bridgeModule.callAttr("latest_video_url", channelId).toJava(String::class.java)
                        channelId to url
                    }
                    withContext(Dispatchers.Main) {
                        runResults = results
                        isRunning = false
                    }
                }
            },
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = if (isRunning) "Running..." else "Run")
        }

        Text(
            text = "Run results",
            style = MaterialTheme.typography.titleMedium
        )

        if (runResults.isEmpty()) {
            Text(
                text = "No results yet",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            runResults.forEach { (channelId, url) ->
                Text(
                    text = "$channelId -> ${url ?: "none/error"}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = {
                showDialog = false
                inputChannelId = ""
            },
            title = { Text(text = "Add channel") },
            text = {
                Column {
                    OutlinedTextField(
                        value = inputChannelId,
                        onValueChange = { inputChannelId = it },
                        label = { Text(text = "channel_id") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = inputChannelId.trim()
                        if (trimmed.isNotEmpty() && trimmed !in channels) {
                            scope.launch {
                                context.dataStore.edit { preferences ->
                                    val updated = (preferences[channelsKey] ?: emptySet()) + trimmed
                                    preferences[channelsKey] = updated
                                }
                            }
                        }
                        showDialog = false
                        inputChannelId = ""
                    }
                ) {
                    Text(text = "Add")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDialog = false
                        inputChannelId = ""
                    }
                ) {
                    Text(text = "Cancel")
                }
            }
        )
    }
}
