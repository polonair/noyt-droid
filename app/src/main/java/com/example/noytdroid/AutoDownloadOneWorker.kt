package com.example.noytdroid

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.example.noytdroid.data.AppDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val AUTO_DOWNLOAD_CHANNEL_ID = "auto_download"
private const val AUTO_DOWNLOAD_NOTIFICATION_ID = 2001
private const val AUTO_DOWNLOAD_RESULT_NOTIFICATION_ID = 2002
private const val TAG_AUTO_DOWNLOAD = "AutoDownload"

class AutoDownloadOneWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val logger = AppLogger.getInstance(applicationContext)
    private val httpClient = OkHttpClient()
    private val workerId = id.toString()

    override suspend fun doWork(): Result {
        ensureNotificationChannel()
        setForeground(getForegroundInfo())
        var currentVideoId: String? = null
        var currentChannelId: String? = null
        logStep("start")

        try {
            if (!hasNetwork()) {
                logStep("validation", "No network, retry")
                return Result.retry()
            }
            if (!hasEnoughFreeSpace()) {
                notifyResult("Download failed: not enough free space")
                logStep("validation", "Not enough free space")
                return Result.retry()
            }

            val syncFolderPrefs = SyncFolderPreferences(applicationContext)
            val treeUriString = syncFolderPrefs.getTreeUriString()
            if (treeUriString.isNullOrBlank()) {
                notifyResult("Choose sync folder to enable auto-download")
                logStep("validation", "No folder selected")
                return Result.success()
            }
            if (!hasPersistedPermission(treeUriString)) {
                notifyResult("Folder permission lost, choose folder again")
                logStep("validation", "Folder permission lost")
                return Result.success()
            }

            val db = AppDatabase.getInstance(applicationContext)
            val videoDao = db.videoDao()
            val channelDao = db.channelDao()
            val video = videoDao.pickAndMarkDownloading(System.currentTimeMillis()) ?: run {
                logStep("end", "No NEW/ERROR videos")
                return Result.success()
            }

            currentVideoId = video.videoId
            currentChannelId = video.channelId
            logStep("picked", "picked videoId=${video.videoId} channelId=${video.channelId}", video.videoId, video.channelId)
            logStep("mark_downloading_ok", videoId = video.videoId, channelId = video.channelId)

            if (video.videoId.isBlank() || !video.videoUrl.contains("youtube", true)) {
                val reason = "Invalid videoUrl/videoId"
                videoDao.markError(video.videoId, reason, System.currentTimeMillis())
                notifyResult("Download failed: invalid video data")
                logStep("validation", reason, video.videoId, video.channelId)
                return Result.failure()
            }

            val channel = channelDao.getChannel(video.channelId)
                ?: throw IllegalStateException("Channel not found for ${video.channelId}")

            val tempDir = File(applicationContext.cacheDir, "auto_download").apply { mkdirs() }
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(applicationContext))
            }
            val bridge = Python.getInstance().getModule("bridge")
            val baseName = sanitizeForFileName("${channel.title} - ${video.title}")

            setStepForeground("yt-dlp")
            logStep("yt_dlp_start", videoId = video.videoId, channelId = video.channelId)
            val downloadedPath = withContext(Dispatchers.IO) {
                bridge.callAttr("download_best_audio", video.videoUrl, tempDir.absolutePath, baseName).toString()
            }
            if (downloadedPath.startsWith("ERROR:")) {
                throw IllegalStateException(downloadedPath.removePrefix("ERROR:").trim())
            }
            val downloadedFile = File(downloadedPath)
            logStep("yt_dlp_done", "path=${downloadedFile.absolutePath} size=${downloadedFile.length()}", video.videoId, video.channelId)

            val metadata = bridge.callAttr("video_metadata", video.videoUrl)
            val title = metadata.callAttr("get", "title")?.toString()?.takeIf { it != "None" } ?: video.title
            val artist = metadata.callAttr("get", "uploader")?.toString()?.takeIf { it != "None" } ?: channel.title
            val thumbnailUrl = metadata.callAttr("get", "thumbnail")?.toString()?.takeIf { it != "None" } ?: channel.avatarUrl

            setStepForeground("ffmpeg")
            logStep("ffmpeg_start", videoId = video.videoId, channelId = video.channelId)
            val coverFile = thumbnailUrl?.let { downloadCoverToTemp(it, tempDir) }
            val taggedMp3 = File(tempDir, "$baseName.mp3")
            val ffmpegCommand = buildTaggingCommand(downloadedPath, taggedMp3.absolutePath, title, artist, coverFile?.absolutePath)
            val session = withContext(Dispatchers.IO) { FFmpegKit.execute(ffmpegCommand) }
            if (!ReturnCode.isSuccess(session.returnCode)) {
                throw IllegalStateException(session.failStackTrace ?: session.allLogsAsString ?: "ffmpeg conversion failed")
            }
            logStep("ffmpeg_done", "path=${taggedMp3.absolutePath} size=${taggedMp3.length()}", video.videoId, video.channelId)

            setStepForeground("tags")
            logStep("tags_start", videoId = video.videoId, channelId = video.channelId)
            logStep("tags_done", videoId = video.videoId, channelId = video.channelId)

            setStepForeground("save")
            val treeUri = android.net.Uri.parse(treeUriString)
            val root = DocumentFile.fromTreeUri(applicationContext, treeUri)
                ?: throw IllegalStateException("Cannot access selected sync folder")
            logStep("save_start", "uri=$treeUri", video.videoId, video.channelId)
            val targetFile = root.createFile("audio/mpeg", "$baseName.mp3")
                ?: throw IllegalStateException("Cannot create target file in sync folder")
            withContext(Dispatchers.IO) {
                applicationContext.contentResolver.openOutputStream(targetFile.uri)?.use { output ->
                    taggedMp3.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Cannot open output stream")
            }
            logStep("save_done", "uri=${targetFile.uri}", video.videoId, video.channelId)

            videoDao.markDone(video.videoId, targetFile.uri.toString(), System.currentTimeMillis())
            logStep("mark_done_ok", videoId = video.videoId, channelId = video.channelId)
            notifyResult("Downloaded 1: ${video.title}")
            logStep("end", videoId = video.videoId, channelId = video.channelId)
            return Result.success()
        } catch (t: Throwable) {
            val db = AppDatabase.getInstance(applicationContext)
            currentVideoId?.let { db.videoDao().markError(it, t.stackTraceToString(), System.currentTimeMillis()) }
            currentChannelId?.let { db.channelDao().markFeedError(it, t.message ?: "worker crash", System.currentTimeMillis()) }
            logger.error(TAG_AUTO_DOWNLOAD, "crash", t, LogContext(workerId = workerId, videoId = currentVideoId, channelId = currentChannelId, step = "crash"))
            notifyResult("Background task failed (tap to open Logs)")
            return if (isTransientError(t)) Result.retry() else Result.failure()
        } finally {
            logStep("finally", videoId = currentVideoId, channelId = currentChannelId)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureNotificationChannel()
        val notification = NotificationCompat.Builder(applicationContext, AUTO_DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Auto download")
            .setContentText("Downloading…")
            .setOngoing(true)
            .build()
        return ForegroundInfo(
            AUTO_DOWNLOAD_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private suspend fun setStepForeground(step: String) {
        val notification = NotificationCompat.Builder(applicationContext, AUTO_DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Auto download")
            .setContentText("Step: $step")
            .setOngoing(true)
            .build()
        setForeground(
            ForegroundInfo(
                AUTO_DOWNLOAD_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        )
    }

    private fun hasNetwork(): Boolean {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun hasPersistedPermission(treeUri: String): Boolean {
        return applicationContext.contentResolver.persistedUriPermissions.any { it.uri.toString() == treeUri && it.isWritePermission }
    }

    private fun hasEnoughFreeSpace(): Boolean {
        val statFs = StatFs(applicationContext.cacheDir.absolutePath)
        return statFs.availableBytes > 50L * 1024 * 1024
    }

    private fun isTransientError(t: Throwable): Boolean {
        val text = (t.message ?: "").lowercase()
        return text.contains("timeout") || text.contains("network") || text.contains("tempor")
    }

    private fun logStep(step: String, message: String = step, videoId: String? = null, channelId: String? = null) {
        logger.info(TAG_AUTO_DOWNLOAD, message, context = LogContext(workerId = workerId, videoId = videoId, channelId = channelId, step = step))
    }

    private fun notifyResult(message: String) {
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, AUTO_DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Auto download")
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(AUTO_DOWNLOAD_RESULT_NOTIFICATION_ID, notification)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                AUTO_DOWNLOAD_CHANNEL_ID,
                "Auto download",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    private suspend fun downloadCoverToTemp(url: String, tempDir: File): File? {
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body ?: return@use null
                    val coverFile = File(tempDir, "cover.jpg")
                    coverFile.outputStream().use { output ->
                        body.byteStream().use { input -> input.copyTo(output) }
                    }
                    coverFile
                }
            }.getOrNull()
        }
    }

    private fun buildTaggingCommand(
        inputPath: String,
        outputPath: String,
        title: String,
        artist: String,
        coverPath: String?
    ): String {
        val escapedTitle = title.replace("\"", "\\\"")
        val escapedArtist = artist.replace("\"", "\\\"")
        return if (coverPath != null) {
            "-y -i \"$inputPath\" -i \"$coverPath\" -map 0:a -map 1:v -c:a libmp3lame -b:a 192k -id3v2_version 3 -metadata title=\"$escapedTitle\" -metadata artist=\"$escapedArtist\" -metadata:s:v title=\"Album cover\" -metadata:s:v comment=\"Cover (front)\" \"$outputPath\""
        } else {
            "-y -i \"$inputPath\" -vn -c:a libmp3lame -b:a 192k -id3v2_version 3 -metadata title=\"$escapedTitle\" -metadata artist=\"$escapedArtist\" \"$outputPath\""
        }
    }

    private fun sanitizeForFileName(input: String): String {
        return input.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "audio_${UUID.randomUUID()}" }
    }
}
