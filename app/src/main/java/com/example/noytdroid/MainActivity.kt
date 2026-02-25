package com.example.noytdroid

import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.text.Regex

private val Context.dataStore by preferencesDataStore(name = "channels")
private val channelsKey = stringSetPreferencesKey("channels")


private fun safeBaseName(channelId: String, timestamp: Long): String {
    val sanitized = channelId
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('_', '.', ' ')
        .ifEmpty { "channel" }
    return "${sanitized}_$timestamp"
}

private data class ConversionResult(
    val success: Boolean,
    val message: String
)

private fun convertToMp3(inputPath: String, outputPath: String): ConversionResult {
    val command = "-y -i \"$inputPath\" -vn -c:a libmp3lame -b:a 192k \"$outputPath\""
    val session = FFmpegKit.execute(command)
    val returnCode = session.returnCode
    val output = buildString {
        append(session.failStackTrace.orEmpty())
        if (session.allLogsAsString.isNotBlank()) {
            if (isNotBlank()) append('\n')
            append(session.allLogsAsString)
        }
        if (isBlank()) {
            append(session.output.orEmpty())
        }
    }.trim().ifBlank { "Unknown ffmpeg result" }

    return if (ReturnCode.isSuccess(returnCode)) {
        ConversionResult(true, "ok")
    } else {
        ConversionResult(false, output)
    }
}

private fun findLastDownloadedFile(downloadsDir: File): File? {
    return downloadsDir
        .listFiles { file ->
            file.isFile && file.extension.lowercase() != "mp3"
        }
        ?.maxByOrNull { it.lastModified() }
}

private fun buildFinalMp3Name(): String {
    val today = LocalDate.now(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
    val epochSeconds = System.currentTimeMillis() / 1000
    return "$today-$epochSeconds.mp3"
}

private fun saveMp3ToDownloads(context: Context, sourceMp3: File): Result<Pair<String, String>> {
    val fileName = buildFinalMp3Name()

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return runCatching {
            val fallbackDir = File(context.filesDir, "Downloads/YT Audio").apply { mkdirs() }
            val outputFile = File(fallbackDir, fileName)
            sourceMp3.inputStream().use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Pair(
                "App storage/Downloads/YT Audio/${outputFile.name}",
                outputFile.absolutePath
            )
        }
    }

    val resolver = context.contentResolver
    val relativePath = Environment.DIRECTORY_DOWNLOADS + "/YT Audio"
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg")
        put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
    }

    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: return Result.failure(IOException("Failed to create MediaStore entry"))

    return runCatching {
        resolver.openOutputStream(uri)?.use { output ->
            sourceMp3.inputStream().use { input ->
                input.copyTo(output)
            }
        } ?: error("Failed to open output stream")
        Pair("Downloads/YT Audio/$fileName", uri.toString())
    }.onFailure {
        resolver.delete(uri, null, null)
    }
}

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
    var runResults by remember { mutableStateOf(emptyList<String>()) }
    var ffmpegResultText by rememberSaveable { mutableStateOf("ffmpeg: idle") }
    var lastSavedLocation by rememberSaveable { mutableStateOf("") }

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

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { showDialog = true },
                modifier = Modifier.weight(1f)
            ) {
                Text(text = "Add channel")
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    isRunning = true
                    runResults = emptyList()
                    scope.launch(Dispatchers.IO) {
                        val bridgeModule = Python.getInstance().getModule("bridge")
                        val outDir = File(context.filesDir, "Downloads").apply { mkdirs() }
                        val results = sortedChannels.map { channelId ->
                            async {
                                try {
                                    val url = bridgeModule.callAttr("latest_video_url", channelId)
                                        .toJava(String::class.java)
                                    if (url.isNullOrBlank()) {
                                        "$channelId -> none"
                                    } else {
                                        val baseName = safeBaseName(channelId, System.currentTimeMillis())
                                        val downloaded = bridgeModule.callAttr(
                                            "download_best_audio",
                                            url,
                                            outDir.absolutePath,
                                            baseName
                                        ).toJava(String::class.java) ?: "ERROR: empty response"

                                        if (downloaded.startsWith("ERROR:")) {
                                            "$channelId -> error: $downloaded"
                                        } else {
                                            "$channelId -> downloaded: ${File(downloaded).name}"
                                        }
                                    }
                                } catch (e: Exception) {
                                    "$channelId -> error: ${e.message ?: "unknown"}"
                                }
                            }
                        }.awaitAll()
                        withContext(Dispatchers.Main) {
                            runResults = results
                            isRunning = false
                        }
                    }
                },
                enabled = !isRunning,
                modifier = Modifier.weight(1f)
            ) {
                Text(text = if (isRunning) "Running..." else "Run")
            }


            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        val downloadsDir = File(context.filesDir, "Downloads").apply { mkdirs() }
                        val source = findLastDownloadedFile(downloadsDir)
                        val result = if (source == null) {
                            "ffmpeg: error no source file in Downloads"
                        } else {
                            val output = File.createTempFile("yt_audio_", ".mp3", context.cacheDir)
                            val conversion = convertToMp3(source.absolutePath, output.absolutePath)
                            if (conversion.success) {
                                val saveResult = saveMp3ToDownloads(context, output)
                                output.delete()
                                saveResult.fold(
                                    onSuccess = { (path, uri) ->
                                        withContext(Dispatchers.Main) {
                                            lastSavedLocation = "Saved to $path ($uri)"
                                        }
                                        "ffmpeg: ok"
                                    },
                                    onFailure = { error ->
                                        "ffmpeg: error save failed: ${error.message ?: "unknown"}"
                                    }
                                )
                            } else {
                                output.delete()
                                "ffmpeg: error ${conversion.message}"
                            }
                        }
                        withContext(Dispatchers.Main) {
                            ffmpegResultText = result
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(text = "Convert last to mp3")
            }
        }

        Text(
            text = ffmpegResultText,
            style = MaterialTheme.typography.bodySmall
        )

        if (lastSavedLocation.isNotBlank()) {
            Text(
                text = lastSavedLocation,
                style = MaterialTheme.typography.bodySmall
            )
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
            runResults.forEach { result ->
                Text(
                    text = result,
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
