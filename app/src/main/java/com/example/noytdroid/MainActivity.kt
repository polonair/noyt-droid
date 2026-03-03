package com.example.noytdroid

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.activity.result.contract.ActivityResultContracts
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import coil.compose.AsyncImage
import com.example.noytdroid.data.AppDatabase
import com.example.noytdroid.data.ChannelEntity
import com.example.noytdroid.data.DownloadState
import com.example.noytdroid.data.FeedRepository
import com.example.noytdroid.data.VideoEntity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.io.IOException
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.text.Regex

private const val ADD_CHANNEL_TIMEOUT_MS = 20_000L
private const val TAG_ADD_CHANNEL = "AddChannel"
private const val TAG_MP3_SAVE = "Mp3Save"
private const val POLIFM_ALBUM = "Polifm"
private const val LOG_SCREEN_LIMIT = 200
private const val MANUAL_FEED_SYNC_WORK_NAME = "feed_sync_manual"
private val UC_CHANNEL_ID_REGEX = Regex("""/channel/(UC[a-zA-Z0-9_-]{10,})""")

private val channelResolveClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
}


data class ChannelInfo(
    val channelId: String,
    val title: String,
    val avatarUrl: String?,
    val finalUrl: String
)

data class VideoItem(
    val videoId: String,
    val title: String,
    val published: Instant?,
    val videoUrl: String
)


data class ChannelDebugStats(
    val totalVideos: Int = 0,
    val newVideos: Int = 0,
    val doneVideos: Int = 0,
    val errorVideos: Int = 0,
    val latestFetchedAt: Long? = null
)

private fun formatDateTime(value: Long?): String {
    if (value == null) return "NULL"
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(value))
}

private fun debugStatusEmoji(state: String): String = when (state) {
    DownloadState.NEW -> "🟡"
    DownloadState.DOWNLOADING -> "🔵"
    DownloadState.DONE -> "🟢"
    DownloadState.ERROR -> "🔴"
    else -> "⚪"
}

private fun jsonString(value: String?): String {
    if (value == null) return "null"
    return "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r") + "\""
}

private fun videoDumpJson(video: VideoEntity): String {
    return """{
  "videoId": ${jsonString(video.videoId)},
  "channelId": ${jsonString(video.channelId)},
  "title": ${jsonString(video.title)},
  "publishedAt": ${video.publishedAt},
  "videoUrl": ${jsonString(video.videoUrl)},
  "fetchedAt": ${video.fetchedAt},
  "downloadState": ${jsonString(video.downloadState)},
  "downloadedUri": ${jsonString(video.downloadedUri)},
  "downloadedAt": ${video.downloadedAt ?: "null"},
  "downloadError": ${jsonString(video.downloadError)}
}""".trimIndent()
}

private fun channelDumpJson(channel: ChannelEntity, stats: ChannelDebugStats): String {
    return """{
  "channelId": ${jsonString(channel.channelId)},
  "title": ${jsonString(channel.title)},
  "avatarUrl": ${jsonString(channel.avatarUrl)},
  "sourceUrl": ${jsonString(channel.sourceUrl)},
  "createdAt": ${channel.createdAt},
  "updatedAt": ${channel.updatedAt},
  "lastFeedSyncAt": ${channel.lastFeedSyncAt ?: "null"},
  "feedError": ${jsonString(channel.feedError)},
  "feedErrorAt": ${channel.feedErrorAt ?: "null"},
  "stats": {
    "totalVideos": ${stats.totalVideos},
    "newVideos": ${stats.newVideos},
    "doneVideos": ${stats.doneVideos},
    "errorVideos": ${stats.errorVideos},
    "latestFetchedAt": ${stats.latestFetchedAt ?: "null"}
  }
}""".trimIndent()
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


suspend fun fetchChannelFeed(channelId: String, logger: AppLogger? = null, limit: Int = 15): List<VideoItem> {
    return withContext(Dispatchers.IO) {
        val bridgeModule = Python.getInstance().getModule("bridge")
        logger?.info("YT-DLP", "Fetch channel=$channelId limit=$limit")
        val rawItems = try {
            bridgeModule.callAttr("list_latest_videos", channelId, limit).asList()
        } catch (error: RuntimeException) {
            logger?.error("YT-DLP", "Python list conversion failed channel=$channelId", error)
            emptyList<PyObject>()
        }

        val parsed = rawItems.mapNotNull { raw ->
            val item: Map<String, PyObject> = try {
                raw.asMap().mapNotNull { (key, value) ->
                    (key.toJava(String::class.java) as? String)?.let { it to value }
                }.toMap()
            } catch (_: RuntimeException) {
                return@mapNotNull null
            }

            val videoId = item["videoId"]?.toJava(String::class.java) as? String ?: return@mapNotNull null
            val title = (item["title"]?.toJava(String::class.java) as? String).orEmpty().ifBlank { videoId }
            val videoUrl = ((item["videoUrl"]?.toJava(String::class.java) as? String).orEmpty())
                .ifBlank { "https://www.youtube.com/watch?v=$videoId" }

            val published = (item["publishedAt"]?.toJava(Long::class.java) as? Long)?.let {
                Instant.ofEpochSecond(it)
            }

            VideoItem(
                videoId = videoId,
                title = title,
                published = published,
                videoUrl = videoUrl
            )
        }

        logger?.info("YT-DLP", "Parse ok channel=$channelId entries=${parsed.size}")
        parsed
    }
}

private data class ConversionResult(
    val success: Boolean,
    val message: String
)

private fun convertToMp3(inputPath: String, outputPath: String): ConversionResult {
    val command = "-y -i \"$inputPath\" -vn -c:a libmp3lame -b:a 192k -id3v2_version 3 -metadata album=\"$POLIFM_ALBUM\" \"$outputPath\""
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

private fun buildFinalMp3Name(epochSeconds: Long = System.currentTimeMillis() / 1000): String {
    val today = LocalDate.now(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
    return "$today-$epochSeconds.mp3"
}

private fun buildUniqueMp3Name(exists: (String) -> Boolean): String {
    val epochSeconds = System.currentTimeMillis() / 1000
    val baseName = buildFinalMp3Name(epochSeconds).removeSuffix(".mp3")
    var candidate = "$baseName.mp3"
    var suffix = 1
    while (exists(candidate)) {
        candidate = "$baseName-$suffix.mp3"
        suffix += 1
    }
    return candidate
}

private fun mediaStoreFileExists(context: Context, relativePath: String, fileName: String): Boolean {
    val resolver = context.contentResolver
    val selection = "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME}=?"
    val args = arrayOf("$relativePath/", fileName)
    resolver.query(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Downloads._ID),
        selection,
        args,
        null
    )?.use { cursor ->
        return cursor.moveToFirst()
    }
    return false
}

private fun saveMp3ToDownloads(context: Context, sourceMp3: File, logger: AppLogger? = null): Result<Pair<String, String>> {
    val relativePath = Environment.DIRECTORY_DOWNLOADS + "/YT Audio"

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return runCatching {
            val fallbackDir = File(context.filesDir, "Downloads/YT Audio").apply { mkdirs() }
            val fileName = buildUniqueMp3Name { candidate -> File(fallbackDir, candidate).exists() }
            val outputFile = File(fallbackDir, fileName)
            sourceMp3.inputStream().use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            logger?.info(TAG_MP3_SAVE, "Saved fileName=$fileName")
            logger?.info(TAG_MP3_SAVE, "Album=$POLIFM_ALBUM")
            Pair(
                "App storage/Downloads/YT Audio/${outputFile.name}",
                outputFile.absolutePath
            )
        }
    }

    val resolver = context.contentResolver
    val fileName = buildUniqueMp3Name { candidate -> mediaStoreFileExists(context, relativePath, candidate) }
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
        logger?.info(TAG_MP3_SAVE, "Saved fileName=$fileName")
        logger?.info(TAG_MP3_SAVE, "Album=$POLIFM_ALBUM")
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
@OptIn(ExperimentalFoundationApi::class)
private fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { FeedRepository(AppDatabase.getInstance(context)) }
    val logger = remember { AppLogger.getInstance(context) }
    val crashStore = remember { CrashStore(context) }
    var selectedChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    var showLogsScreen by rememberSaveable { mutableStateOf(false) }
    val channels by repository.observeChannels().collectAsState(initial = emptyList())

    var showDialog by rememberSaveable { mutableStateOf(false) }
    var inputChannelLink by rememberSaveable { mutableStateOf("") }
    var isResolvingChannel by rememberSaveable { mutableStateOf(false) }
    var addChannelError by rememberSaveable { mutableStateOf<String?>(null) }
    var isRunning by rememberSaveable { mutableStateOf(false) }
    var runStatus by rememberSaveable { mutableStateOf("Idle") }
    var manualWorkId by remember { mutableStateOf<UUID?>(null) }
    var ffmpegResultText by rememberSaveable { mutableStateOf("ffmpeg: idle") }
    var lastSavedLocation by rememberSaveable { mutableStateOf("") }
    val lastCrash = remember { crashStore.getLastCrash() }

    val sortedChannels = channels
    val syncFolderPreferences = remember { SyncFolderPreferences(context) }
    val debugUiEnabled by syncFolderPreferences.debugUiEnabledFlow.collectAsState(initial = false)
    var syncFolderUriText by rememberSaveable { mutableStateOf("") }
    var channelStats by remember { mutableStateOf<Map<String, ChannelDebugStats>>(emptyMap()) }
    var selectedChannelDump by remember { mutableStateOf<String?>(null) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
            scope.launch(Dispatchers.IO) {
                syncFolderPreferences.saveTreeUri(uri)
            }
            syncFolderUriText = uri.toString()
            Toast.makeText(context, "Sync folder selected", Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            Toast.makeText(context, "Cannot persist folder permission: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        syncFolderUriText = syncFolderPreferences.getTreeUriString().orEmpty()
    }

    LaunchedEffect(channels) {
        channelStats = withContext(Dispatchers.IO) {
            channels.associate { channel ->
                val total = repository.countVideos(channel.channelId)
                val newCount = repository.countVideosByState(channel.channelId, DownloadState.NEW)
                val doneCount = repository.countVideosByState(channel.channelId, DownloadState.DONE)
                val errorCount = repository.countVideosByState(channel.channelId, DownloadState.ERROR)
                val latestFetchedAt = repository.getLatestFetchedAt(channel.channelId)
                channel.channelId to ChannelDebugStats(total, newCount, doneCount, errorCount, latestFetchedAt)
            }
        }
    }
    LaunchedEffect(manualWorkId) {
        val workId = manualWorkId ?: return@LaunchedEffect
        WorkManager.getInstance(context)
            .getWorkInfoByIdFlow(workId)
            .collect { info ->
                if (info == null) return@collect
                runStatus = when (info.state) {
                    WorkInfo.State.ENQUEUED -> "Enqueued"
                    WorkInfo.State.RUNNING -> "Running"
                    WorkInfo.State.SUCCEEDED -> "Success"
                    WorkInfo.State.FAILED -> "Failure"
                    WorkInfo.State.CANCELLED -> "Cancelled"
                    WorkInfo.State.BLOCKED -> "Blocked"
                }
                if (info.state.isFinished) {
                    isRunning = false
                }
            }
    }

    if (showLogsScreen) {
        LogsScreen(
            repository = repository,
            onBack = { showLogsScreen = false }
        )
        return
    }

    if (selectedChannel != null) {
        ChannelVideosScreen(
            channel = selectedChannel!!,
            repository = repository,
            debugUiEnabled = debugUiEnabled,
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

        if (lastCrash != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Last crash: ${crashStore.formattedTime(lastCrash)}", modifier = Modifier.weight(1f))
                val clipboard = LocalClipboardManager.current
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(lastCrash.stacktrace))
                    Toast.makeText(context, "Stacktrace copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy stacktrace") }
                TextButton(onClick = { showLogsScreen = true }) { Text("Open logs") }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Show debug details")
            Switch(
                checked = debugUiEnabled,
                onCheckedChange = { enabled ->
                    scope.launch(Dispatchers.IO) {
                        syncFolderPreferences.setDebugUiEnabled(enabled)
                    }
                }
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(sortedChannels, key = { it.channelId }) { channel ->
                val stats = channelStats[channel.channelId] ?: ChannelDebugStats()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { selectedChannel = channel },
                            onLongClick = {
                                if (debugUiEnabled) {
                                    selectedChannelDump = channelDumpJson(channel, stats)
                                }
                            }
                        ),
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
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(text = channel.title, style = MaterialTheme.typography.bodyLarge)
                        Text(text = channel.channelId.take(10) + "…", style = MaterialTheme.typography.bodySmall)
                        if (debugUiEnabled) {
                            Text(
                                text = """
channelId=${channel.channelId}
sourceUrl=${channel.sourceUrl}
createdAt=${formatDateTime(channel.createdAt)} updatedAt=${formatDateTime(channel.updatedAt)}
lastFeedSyncAt=${formatDateTime(channel.lastFeedSyncAt)} feedError=${channel.feedError ?: "NULL"}
videos total=${stats.totalVideos} new=${stats.newVideos} done=${stats.doneVideos} error=${stats.errorVideos} latestFetchedAt=${formatDateTime(stats.latestFetchedAt)}
                                """.trimIndent(),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 8
                            )
                        }
                    }
                }
                if (debugUiEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            val request = OneTimeWorkRequestBuilder<FeedSyncWorker>()
                                .setConstraints(
                                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                                )
                                .setInputData(workDataOf("batchSize" to 1, "channelId" to channel.channelId))
                                .build()
                            WorkManager.getInstance(context).enqueue(request)
                            Toast.makeText(context, "Forced sync enqueued", Toast.LENGTH_SHORT).show()
                        }) { Text("Force sync now") }
                        TextButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                repository.deleteVideosForChannel(channel.channelId)
                            }
                        }) { Text("Clear videos") }
                    }
                    HorizontalDivider()
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
                onClick = { showLogsScreen = true },
                modifier = Modifier.weight(1f)
            ) {
                Text(text = "Logs")
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    val request = OneTimeWorkRequestBuilder<FeedSyncWorker>()
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build()
                        )
                        .setInputData(workDataOf("batchSize" to 15))
                        .build()

                    manualWorkId = request.id
                    runStatus = "Sync started..."
                    isRunning = true

                    WorkManager.getInstance(context)
                        .enqueueUniqueWork(
                            MANUAL_FEED_SYNC_WORK_NAME,
                            ExistingWorkPolicy.REPLACE,
                            request
                        )
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
                                val saveResult = saveMp3ToDownloads(context, output, logger)
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

        Button(
            onClick = { folderPickerLauncher.launch(null) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Choose sync folder")
        }

        if (syncFolderUriText.isNotBlank()) {
            Text(
                text = "Sync folder: $syncFolderUriText",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
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
            text = "Run status",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = runStatus,
            style = MaterialTheme.typography.bodySmall
        )
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
                        logger.info("UI", "Add channel requested")
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
                                logger.info("UI", "Resolve channel success id=${resolved.channelId}")

                                val now = System.currentTimeMillis()
                                val existing = channels.firstOrNull { it.channelId == resolved.channelId }
                                withContext(Dispatchers.IO) {
                                    repository.upsertChannel(
                                        ChannelEntity(
                                            channelId = resolved.channelId,
                                            title = resolved.title,
                                            avatarUrl = resolved.avatarUrl,
                                            sourceUrl = normalized,
                                            createdAt = existing?.createdAt ?: now,
                                            updatedAt = now,
                                            lastFeedSyncAt = existing?.lastFeedSyncAt
                                        )
                                    )
                                }
                                val wasExisting = existing != null
                                Toast.makeText(
                                    context,
                                    if (wasExisting) "Updated: ${resolved.title}" else "Added: ${resolved.title}",
                                    Toast.LENGTH_SHORT
                                ).show()
                                showDialog = false
                                inputChannelLink = ""
                                addChannelError = null
                            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                                Log.e(TAG_ADD_CHANNEL, "timeout", e)
                                logger.error("UI", "Resolve channel timeout", e)
                                addChannelError = "Timed out while resolving channel. Please try again."
                                Toast.makeText(
                                    context,
                                    addChannelError,
                                    Toast.LENGTH_LONG
                                ).show()
                            } catch (e: Exception) {
                                Log.e(TAG_ADD_CHANNEL, "error ${e.message}", e)
                                logger.error("UI", "Resolve channel error: ${e.message}", e)
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

    if (selectedChannelDump != null) {
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { selectedChannelDump = null },
            title = { Text("Channel full dump") },
            text = { Text(selectedChannelDump.orEmpty(), fontFamily = FontFamily.Monospace) },
            confirmButton = {
                TextButton(onClick = { selectedChannelDump = null }) { Text("Close") }
            },
            dismissButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(selectedChannelDump.orEmpty()))
                    Toast.makeText(context, "Dump copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy JSON") }
            }
        )
    }

}


private fun formatPublishedDate(value: Instant?): String {
    if (value == null) return "Unknown date"
    return DateTimeFormatter.ofPattern("yyyy-MM-dd")
        .withZone(ZoneId.systemDefault())
        .format(value)
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class
)
@Composable
private fun ChannelVideosScreen(
    channel: ChannelEntity,
    repository: FeedRepository,
    debugUiEnabled: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val logger = remember { AppLogger.getInstance(context) }
    val scope = rememberCoroutineScope()
    val videos by repository.observeVideos(channel.channelId).collectAsState(initial = emptyList())
    var isRefreshing by remember(channel.channelId) { mutableStateOf(false) }
    var refreshError by remember(channel.channelId) { mutableStateOf<String?>(null) }
    var selectedVideoDump by remember { mutableStateOf<String?>(null) }
    var selectedVideoError by remember { mutableStateOf<String?>(null) }
    var selectedActionVideoId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(channel.channelId) {
        isRefreshing = true
        refreshError = null
        try {
            val fetchedVideos = withContext(Dispatchers.IO) {
                fetchChannelFeed(channel.channelId, logger)
            }
            val fetchedAt = System.currentTimeMillis()
            val entities = fetchedVideos.map { video ->
                VideoEntity(
                    videoId = video.videoId,
                    channelId = channel.channelId,
                    title = video.title,
                    publishedAt = video.published?.toEpochMilli() ?: 0L,
                    videoUrl = video.videoUrl,
                    fetchedAt = fetchedAt,
                    downloadState = DownloadState.NEW,
                    downloadedUri = null,
                    downloadedAt = null,
                    downloadError = null
                )
            }
            withContext(Dispatchers.IO) {
                repository.upsertVideos(entities)
            }
        } catch (_: IOException) {
            refreshError = "Feed unavailable"
        } catch (_: Exception) {
            refreshError = "Feed unavailable"
        } finally {
            isRefreshing = false
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
        if (videos.isEmpty() && isRefreshing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!refreshError.isNullOrBlank()) {
                    Text(
                        text = refreshError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (videos.isEmpty()) {
                    Text(text = "No videos", style = MaterialTheme.typography.bodyLarge)
                } else {
                    val clipboard = LocalClipboardManager.current
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(videos, key = { it.videoId }) { video ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (debugUiEnabled) {
                                                selectedVideoDump = videoDumpJson(video)
                                            }
                                        },
                                        onLongClick = {
                                            if (debugUiEnabled) {
                                                selectedActionVideoId = video.videoId
                                            }
                                        }
                                    )
                            ) {
                                Text(text = video.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = "${formatPublishedDate(if (video.publishedAt > 0) Instant.ofEpochMilli(video.publishedAt) else null)} • ${video.videoId}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (debugUiEnabled) {
                                    Text(
                                        text = """
                                            ${debugStatusEmoji(video.downloadState)} state=${video.downloadState}
                                            videoId=${video.videoId}
                                            channelId=${video.channelId}
                                            videoUrl=${video.videoUrl}
                                            fetchedAt=${formatDateTime(video.fetchedAt)} downloadedAt=${formatDateTime(video.downloadedAt)}
                                            downloadedUri=${video.downloadedUri ?: "NULL"}
                                            downloadError=${video.downloadError?.take(160) ?: "NULL"}
                                        """.trimIndent(),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 10
                                    )
                                    if (!video.downloadError.isNullOrBlank()) {
                                        Text(
                                            text = "Open full downloadError",
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.clickable {
                                                selectedVideoError = video.downloadError
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (selectedVideoDump != null) {
                        AlertDialog(
                            onDismissRequest = { selectedVideoDump = null },
                            title = { Text("Video full dump") },
                            text = { Text(selectedVideoDump.orEmpty(), fontFamily = FontFamily.Monospace) },
                            confirmButton = {
                                TextButton(onClick = { selectedVideoDump = null }) { Text("Close") }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    clipboard.setText(AnnotatedString(selectedVideoDump.orEmpty()))
                                    Toast.makeText(context, "JSON copied", Toast.LENGTH_SHORT).show()
                                }) { Text("Copy JSON") }
                            }
                        )
                    }

                    if (selectedActionVideoId != null) {
                        val videoId = selectedActionVideoId.orEmpty()
                        AlertDialog(
                            onDismissRequest = { selectedActionVideoId = null },
                            title = { Text("Debug actions") },
                            text = { Text(videoId) },
                            confirmButton = {
                                TextButton(onClick = {
                                    selectedActionVideoId = null
                                    if (videoId.isNotBlank()) {
                                        scope.launch(Dispatchers.IO) {
                                            repository.markVideoState(videoId, DownloadState.NEW)
                                        }
                                    }
                                }) { Text("Mark NEW") }
                            },
                            dismissButton = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = {
                                        selectedActionVideoId = null
                                        if (videoId.isNotBlank()) {
                                            scope.launch(Dispatchers.IO) {
                                                repository.markVideoError(videoId, "Manually marked as ERROR from debug UI", System.currentTimeMillis())
                                            }
                                        }
                                    }) { Text("Mark ERROR") }
                                    TextButton(onClick = {
                                        selectedActionVideoId = null
                                        if (videoId.isNotBlank()) {
                                            scope.launch(Dispatchers.IO) {
                                                repository.deleteVideo(videoId)
                                            }
                                        }
                                    }) { Text("Delete") }
                                }
                            }
                        )
                    } else if (!selectedVideoError.isNullOrBlank()) {
                        AlertDialog(
                            onDismissRequest = { selectedVideoError = null },
                            title = { Text("Download error") },
                            text = { Text(selectedVideoError.orEmpty()) },
                            confirmButton = { TextButton(onClick = { selectedVideoError = null }) { Text("Close") } }
                        )
                    }
                }
            }
        }
    }
}


private fun formatLogDate(value: Long): String {
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(value))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogsScreen(
    repository: FeedRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var workerFilter by rememberSaveable { mutableStateOf("") }
    var videoFilter by rememberSaveable { mutableStateOf("") }
    var levelFilter by rememberSaveable { mutableStateOf("") }
    val logs by repository.observeLatestLogs(
        LOG_SCREEN_LIMIT,
        workerFilter.trim().ifBlank { null },
        videoFilter.trim().ifBlank { null },
        levelFilter.trim().uppercase().ifBlank { null }
    ).collectAsState(initial = emptyList())
    val crashStore = remember { CrashStore(context) }
    var selectedLog by remember { mutableStateOf<com.example.noytdroid.data.LogEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    TextButton(onClick = {
                        crashStore.getLastCrash()?.let {
                            clipboard.setText(AnnotatedString(it.stacktrace))
                            Toast.makeText(context, "Last crash copied", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("Copy last crash") }
                    TextButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            exportLogsToDownloads(context, repository)
                        }
                    }) { Text("Export") }
                    TextButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            repository.deleteLogsOlderThan(System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000))
                        }
                    }) {
                        Text("Keep 7 days")
                    }
                    TextButton(onClick = {
                        scope.launch(Dispatchers.IO) { repository.clearLogs() }
                    }) {
                        Text("Clear")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = workerFilter, onValueChange = { workerFilter = it }, label = { Text("workerId") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = videoFilter, onValueChange = { videoFilter = it }, label = { Text("videoId") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = levelFilter, onValueChange = { levelFilter = it }, label = { Text("level (INFO/WARN/ERROR/FATAL)") }, modifier = Modifier.fillMaxWidth())

            if (logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No logs yet")
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(logs, key = { it.id }) { entry ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedLog = entry }
                        ) {
                            Text(
                                text = "${formatLogDate(entry.ts)} ${entry.level}/${entry.tag}",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                text = "worker=${entry.workerId ?: "-"} video=${entry.videoId ?: "-"} step=${entry.step ?: "-"}",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                text = entry.message,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }

    if (selectedLog != null) {
        AlertDialog(
            onDismissRequest = { selectedLog = null },
            title = { Text("${selectedLog?.level} / ${selectedLog?.tag}") },
            text = {
                Text(selectedLog?.details ?: "No details")
            },
            confirmButton = {
                TextButton(onClick = { selectedLog = null }) {
                    Text("Close")
                }
            }
        )
    }
}

private suspend fun exportLogsToDownloads(context: Context, repository: FeedRepository) {
    val logs = repository.getLatestLogs(5000)
    if (logs.isEmpty()) return
    val fileName = "noyt_logs_${System.currentTimeMillis()}.txt"
    val body = logs.reversed().joinToString("\n") {
        "${it.ts}|${it.level}|${it.tag}|session=${it.sessionId}|worker=${it.workerId}|video=${it.videoId}|channel=${it.channelId}|step=${it.step}|${it.message}\n${it.details.orEmpty()}"
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        val output = File(context.filesDir, fileName)
        output.writeText(body)
        return
    }

    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
    resolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
}
