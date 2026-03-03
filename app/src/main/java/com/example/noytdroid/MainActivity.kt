package com.example.noytdroid

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import coil.compose.AsyncImage
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import org.json.JSONObject
import kotlin.text.Regex

private val Context.dataStore by preferencesDataStore(name = "channels")
private val channelsKey = stringSetPreferencesKey("channels")
private const val ADD_CHANNEL_TIMEOUT_MS = 20_000L
private const val TAG_ADD_CHANNEL = "AddChannel"
private val UC_CHANNEL_ID_REGEX = Regex("""/channel/(UC[a-zA-Z0-9_-]{10,})""")
private val HANDLE_REGEX = Regex("""/@([A-Za-z0-9._-]+)""")

private val channelResolveClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
}


private val channelFeedClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
}

data class ChannelInfo(
    val channelId: String,
    val title: String,
    val avatarUrl: String?,
    val finalUrl: String
)

private data class Channel(
    val id: String,
    val title: String,
    val avatarUrl: String?,
    val handle: String?
)

data class VideoItem(
    val videoId: String,
    val title: String,
    val published: Instant,
    val videoUrl: String
)

private sealed interface ChannelVideosUiState {
    object Loading : ChannelVideosUiState
    data class Success(val videos: List<VideoItem>) : ChannelVideosUiState
    data class Error(val message: String) : ChannelVideosUiState
}

private fun encodeChannel(channel: Channel): String {
    return JSONObject()
        .put("id", channel.id)
        .put("title", channel.title)
        .put("avatar_url", channel.avatarUrl)
        .put("handle", channel.handle)
        .toString()
}

private fun decodeChannel(serialized: String): Channel? {
    return try {
        val json = JSONObject(serialized)
        val id = json.optString("id")
        val title = json.optString("title", id)
        if (id.isBlank()) {
            null
        } else {
            Channel(
                id = id,
                title = title.ifBlank { id },
                avatarUrl = json.optString("avatar_url").ifBlank { null },
                handle = json.optString("handle").ifBlank { null }
            )
        }
    } catch (_: Exception) {
        val legacyId = serialized.trim()
        if (legacyId.isBlank()) null else Channel(legacyId, legacyId, null, null)
    }
}

private fun normalizeChannelInput(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return ""
    if (trimmed.startsWith("@")) {
        return "https://www.youtube.com/$trimmed"
    }
    if (!trimmed.contains("://")) {
        return "https://$trimmed"
    }
    return trimmed
}

private fun findMetaContent(html: String, property: String): String? {
    val escaped = Regex.escape(property)
    val pattern = Regex(
        """<meta\s+[^>]*?(?:property|name)\s*=\s*["']$escaped["'][^>]*?content\s*=\s*["']([^"']+)["'][^>]*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    return pattern.find(html)?.groupValues?.get(1)?.trim()
}

private fun findCanonicalHref(html: String): String? {
    val pattern = Regex(
        """<link\s+[^>]*?rel\s*=\s*["']canonical["'][^>]*?href\s*=\s*["']([^"']+)["'][^>]*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    return pattern.find(html)?.groupValues?.get(1)?.trim()
}

private fun findTitleTag(html: String): String? {
    val pattern = Regex("""<title>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    return pattern.find(html)?.groupValues?.get(1)?.trim()
}

private fun extractChannelId(value: String): String? {
    return UC_CHANNEL_ID_REGEX.find(value)?.groupValues?.get(1)
}

suspend fun resolveChannel(urlOrHandle: String): ChannelInfo {
    val normalized = normalizeChannelInput(urlOrHandle)
    if (normalized.isBlank()) {
        throw IllegalArgumentException("Empty channel link")
    }

    val request = Request.Builder()
        .url(normalized)
        .header(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        )
        .header("Accept-Language", "en-US,en;q=0.9")
        .header("Accept", "text/html")
        .get()
        .build()

    val response = withContext(Dispatchers.IO) {
        channelResolveClient.newCall(request).execute()
    }

    response.use { httpResponse ->
        val responseCode = httpResponse.code
        if (responseCode !in 200..399) {
            throw IOException("HTTP $responseCode")
        }

        val finalUrl = httpResponse.request.url.toString()
        val html = httpResponse.body?.string().orEmpty()
        if (html.isBlank()) {
            throw IOException("Empty response body")
        }

        val channelId = extractChannelId(finalUrl)
            ?: findMetaContent(html, "og:url")?.let(::extractChannelId)
            ?: findCanonicalHref(html)?.let(::extractChannelId)
            ?: extractChannelId(html)
            ?: throw IllegalStateException("Missing channel_id")

        val title = findMetaContent(html, "og:title")
            ?: findTitleTag(html)
                ?.removeSuffix(" - YouTube")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Missing title")

        val avatarUrl = findMetaContent(html, "og:image")?.takeIf { it.isNotBlank() }

        return ChannelInfo(
            channelId = channelId,
            title = title,
            avatarUrl = avatarUrl,
            finalUrl = finalUrl
        )
    }
}


private fun parseChannelFeed(xml: String): List<VideoItem> {
    if (xml.isBlank()) return emptyList()

    val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(xml.reader())
    }

    val videos = mutableListOf<VideoItem>()
    var insideEntry = false
    var videoId: String? = null
    var title: String? = null
    var published: Instant? = null
    var videoUrl: String? = null

    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> {
                when (parser.name) {
                    "entry" -> {
                        insideEntry = true
                        videoId = null
                        title = null
                        published = null
                        videoUrl = null
                    }

                    "videoId" -> if (insideEntry) {
                        videoId = parser.nextText().trim()
                    }

                    "title" -> if (insideEntry) {
                        title = parser.nextText().trim()
                    }

                    "published" -> if (insideEntry) {
                        published = runCatching { Instant.parse(parser.nextText().trim()) }.getOrNull()
                    }

                    "link" -> if (insideEntry) {
                        val href = parser.getAttributeValue(null, "href")?.trim()
                        if (!href.isNullOrBlank()) {
                            videoUrl = href
                        }
                    }
                }
            }

            XmlPullParser.END_TAG -> {
                if (parser.name == "entry") {
                    insideEntry = false
                    if (!videoId.isNullOrBlank() && !title.isNullOrBlank() && published != null) {
                        val resolvedUrl = videoUrl ?: "https://www.youtube.com/watch?v=$videoId"
                        videos += VideoItem(
                            videoId = videoId,
                            title = title,
                            published = published,
                            videoUrl = resolvedUrl
                        )
                    }
                }
            }
        }
        parser.next()
    }

    return videos
}

suspend fun fetchChannelFeed(channelId: String): List<VideoItem> {
    val request = Request.Builder()
        .url("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId")
        .get()
        .build()

    val response = withContext(Dispatchers.IO) {
        channelFeedClient.newCall(request).execute()
    }

    response.use { httpResponse ->
        if (httpResponse.code != 200) {
            throw IOException("Feed unavailable")
        }

        val xml = httpResponse.body?.string().orEmpty()
        return parseChannelFeed(xml)
    }
}

private fun extractHandleFromUrl(url: String): String? {
    return HANDLE_REGEX.find(url)?.groupValues?.get(1)?.let { "@$it" }
}

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
    var selectedChannel by remember { mutableStateOf<ChannelInfo?>(null) }
    val channels by context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val decoded = (preferences[channelsKey] ?: emptySet())
                .mapNotNull(::decodeChannel)
                .associateBy { it.id }
                .values
            decoded.toList()
        }
        .collectAsState(initial = emptyList())

    var showDialog by rememberSaveable { mutableStateOf(false) }
    var inputChannelLink by rememberSaveable { mutableStateOf("") }
    var isResolvingChannel by rememberSaveable { mutableStateOf(false) }
    var addChannelError by rememberSaveable { mutableStateOf<String?>(null) }
    var isRunning by rememberSaveable { mutableStateOf(false) }
    var runResults by remember { mutableStateOf(emptyList<String>()) }
    var ffmpegResultText by rememberSaveable { mutableStateOf("ffmpeg: idle") }
    var lastSavedLocation by rememberSaveable { mutableStateOf("") }

    val sortedChannels = remember(channels) {
        channels.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    if (selectedChannel != null) {
        ChannelVideosScreen(
            channel = selectedChannel!!,
            onBack = { selectedChannel = null }
        )
        return
    }

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
            items(sortedChannels, key = { it.id }) { channel ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedChannel = ChannelInfo(
                                channelId = channel.id,
                                title = channel.title,
                                avatarUrl = channel.avatarUrl,
                                finalUrl = channel.handle.orEmpty()
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AsyncImage(
                        model = channel.avatarUrl,
                        contentDescription = "Avatar ${channel.title}",
                        modifier = Modifier
                            .size(40.dp)
                            .clip(MaterialTheme.shapes.small)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = channel.title,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = channel.handle ?: channel.id,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    addChannelError = null
                    showDialog = true
                },
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
                        val results = sortedChannels.map { channel ->
                            async {
                                try {
                                    val url = bridgeModule.callAttr("latest_video_url", channel.id)
                                        .toJava(String::class.java)
                                    if (url.isNullOrBlank()) {
                                        "${channel.id} -> none"
                                    } else {
                                        val baseName = safeBaseName(channel.id, System.currentTimeMillis())
                                        val downloaded = bridgeModule.callAttr(
                                            "download_best_audio",
                                            url,
                                            outDir.absolutePath,
                                            baseName
                                        ).toJava(String::class.java) ?: "ERROR: empty response"

                                        if (downloaded.startsWith("ERROR:")) {
                                            "${channel.id} -> error: $downloaded"
                                        } else {
                                            "${channel.id} -> downloaded: ${File(downloaded).name}"
                                        }
                                    }
                                } catch (e: Exception) {
                                    "${channel.id} -> error: ${e.message ?: "unknown"}"
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
                if (!isResolvingChannel) {
                    showDialog = false
                    inputChannelLink = ""
                    addChannelError = null
                }
            },
            title = { Text(text = "Add channel") },
            text = {
                Column {
                    OutlinedTextField(
                        value = inputChannelLink,
                        onValueChange = {
                            inputChannelLink = it
                            addChannelError = null
                        },
                        label = { Text(text = "Paste YouTube channel link") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isResolvingChannel
                    )
                    if (isResolvingChannel) {
                        Text(
                            text = "Adding...",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (!addChannelError.isNullOrBlank()) {
                        Text(
                            text = addChannelError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        Log.d(TAG_ADD_CHANNEL, "UI click")
                        val normalized = normalizeChannelInput(inputChannelLink)
                        if (normalized.isBlank()) {
                            addChannelError = "Empty channel link"
                            Toast.makeText(context, "Empty channel link", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }

                        isResolvingChannel = true
                        addChannelError = null
                        scope.launch {
                            try {
                                val resolved = withTimeout(ADD_CHANNEL_TIMEOUT_MS) {
                                    resolveChannel(normalized)
                                }

                                val exists = channels.any { it.id == resolved.channelId }
                                if (exists) {
                                    Toast.makeText(
                                        context,
                                        "Channel already added: ${resolved.channelId}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    val channel = Channel(
                                        id = resolved.channelId,
                                        title = resolved.title,
                                        avatarUrl = resolved.avatarUrl,
                                        handle = extractHandleFromUrl(resolved.finalUrl)
                                    )
                                    context.dataStore.edit { preferences ->
                                        val existing = preferences[channelsKey] ?: emptySet()
                                        preferences[channelsKey] = existing + encodeChannel(channel)
                                    }
                                    Toast.makeText(
                                        context,
                                        "Added: ${channel.title}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    showDialog = false
                                    inputChannelLink = ""
                                    addChannelError = null
                                }
                            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                                Log.e(TAG_ADD_CHANNEL, "timeout", e)
                                addChannelError = "Timed out while resolving channel. Please try again."
                                Toast.makeText(
                                    context,
                                    addChannelError,
                                    Toast.LENGTH_LONG
                                ).show()
                            } catch (e: Exception) {
                                Log.e(TAG_ADD_CHANNEL, "error ${e.message}", e)
                                addChannelError = e.message ?: "Failed to resolve channel"
                                Toast.makeText(
                                    context,
                                    addChannelError,
                                    Toast.LENGTH_LONG
                                ).show()
                            } finally {
                                isResolvingChannel = false
                            }
                        }
                    },
                    enabled = !isResolvingChannel
                ) {
                    Text(text = if (isResolvingChannel) "Adding..." else "Add")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDialog = false
                        inputChannelLink = ""
                        addChannelError = null
                    },
                    enabled = !isResolvingChannel
                ) {
                    Text(text = "Cancel")
                }
            }
        )
    }
}


private fun formatPublishedDate(value: Instant): String {
    return DateTimeFormatter.ofPattern("yyyy-MM-dd")
        .withZone(ZoneId.systemDefault())
        .format(value)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelVideosScreen(
    channel: ChannelInfo,
    onBack: () -> Unit
) {
    var uiState by remember(channel.channelId) {
        mutableStateOf<ChannelVideosUiState>(ChannelVideosUiState.Loading)
    }

    LaunchedEffect(channel.channelId) {
        uiState = ChannelVideosUiState.Loading
        uiState = try {
            val videos = withContext(Dispatchers.IO) {
                fetchChannelFeed(channel.channelId)
            }
            if (videos.isEmpty()) {
                ChannelVideosUiState.Error("No videos")
            } else {
                ChannelVideosUiState.Success(videos)
            }
        } catch (_: IOException) {
            ChannelVideosUiState.Error("Feed unavailable")
        } catch (_: Exception) {
            ChannelVideosUiState.Error("Feed unavailable")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = channel.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        when (val state = uiState) {
            ChannelVideosUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is ChannelVideosUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            is ChannelVideosUiState.Success -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.videos, key = { it.videoId }) { video ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { }
                        ) {
                            Text(
                                text = video.title,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = formatPublishedDate(video.published),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}
