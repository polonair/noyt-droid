package com.example.noytdroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
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

    override suspend fun doWork(): Result {
        ensureNotificationChannel()
        setForeground(getForegroundInfo())

        val syncFolderPrefs = SyncFolderPreferences(applicationContext)
        val treeUriString = syncFolderPrefs.getTreeUriString()
        if (treeUriString.isNullOrBlank()) {
            notifyResult("Choose sync folder to enable auto-download")
            logger.warn(TAG_AUTO_DOWNLOAD, "Skip auto-download: folder is not selected")
            return Result.success()
        }

        val db = AppDatabase.getInstance(applicationContext)
        val videoDao = db.videoDao()
        val channelDao = db.channelDao()
        val video = videoDao.getOldestUndownloaded() ?: run {
            logger.info(TAG_AUTO_DOWNLOAD, "No NEW/ERROR videos to download")
            return Result.success()
        }

        val now = System.currentTimeMillis()
        videoDao.markDownloading(video.videoId, now)

        return runCatching {
            val channel = channelDao.getChannel(video.channelId)
                ?: error("Channel not found for ${video.channelId}")

            val tempDir = File(applicationContext.cacheDir, "auto_download").apply { mkdirs() }
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(applicationContext))
            }
            val bridge = Python.getInstance().getModule("bridge")
            val baseName = sanitizeForFileName("${channel.title} - ${video.title}")

            val downloadedPath = withContext(Dispatchers.IO) {
                bridge.callAttr("download_best_audio", video.videoUrl, tempDir.absolutePath, baseName).toString()
            }
            if (downloadedPath.startsWith("ERROR:")) {
                error(downloadedPath.removePrefix("ERROR:").trim())
            }

            val metadata = bridge.callAttr("video_metadata", video.videoUrl)
            val title = metadata.callAttr("get", "title")?.toString()?.takeIf { it != "None" } ?: video.title
            val artist = metadata.callAttr("get", "uploader")?.toString()?.takeIf { it != "None" }
                ?: channel.title
            val thumbnailUrl = metadata.callAttr("get", "thumbnail")?.toString()?.takeIf { it != "None" }
                ?: channel.avatarUrl

            val coverFile = thumbnailUrl?.let { downloadCoverToTemp(it, tempDir) }
            val taggedMp3 = File(tempDir, "$baseName.mp3")
            val ffmpegCommand = buildTaggingCommand(
                inputPath = downloadedPath,
                outputPath = taggedMp3.absolutePath,
                title = title,
                artist = artist,
                coverPath = coverFile?.absolutePath
            )
            val session = withContext(Dispatchers.IO) { FFmpegKit.execute(ffmpegCommand) }
            if (!ReturnCode.isSuccess(session.returnCode)) {
                error(session.failStackTrace ?: session.allLogsAsString ?: "ffmpeg conversion failed")
            }

            val treeUri = android.net.Uri.parse(treeUriString)
            val root = DocumentFile.fromTreeUri(applicationContext, treeUri)
                ?: error("Cannot access selected sync folder")
            val fileName = "$baseName.mp3"
            val targetFile = root.createFile("audio/mpeg", fileName)
                ?: error("Cannot create target file in sync folder")

            withContext(Dispatchers.IO) {
                applicationContext.contentResolver.openOutputStream(targetFile.uri)?.use { output ->
                    taggedMp3.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Cannot open output stream for sync folder")
            }

            videoDao.markDone(video.videoId, targetFile.uri.toString(), System.currentTimeMillis())
            notifyResult("Downloaded 1: ${video.title}")
            logger.info(TAG_AUTO_DOWNLOAD, "Done video=${video.videoId} uri=${targetFile.uri}")
            Result.success()
        }.getOrElse { error ->
            videoDao.markError(video.videoId, error.message ?: "unknown error", System.currentTimeMillis())
            notifyResult("Auto-download failed: ${video.title}")
            logger.error(TAG_AUTO_DOWNLOAD, "Failed video=${video.videoId}: ${error.message}", error)
            Result.success()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureNotificationChannel()
        val notification = NotificationCompat.Builder(applicationContext, AUTO_DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Auto download")
            .setContentText("Downloading one video…")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return ForegroundInfo(AUTO_DOWNLOAD_NOTIFICATION_ID, notification)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            AUTO_DOWNLOAD_CHANNEL_ID,
            "Auto download",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun notifyResult(text: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification: Notification = NotificationCompat.Builder(applicationContext, AUTO_DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("NOYT")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(AUTO_DOWNLOAD_RESULT_NOTIFICATION_ID, notification)
    }

    private fun sanitizeForFileName(name: String): String {
        return name
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(120)
            .ifBlank { "audio" }
    }

    private suspend fun downloadCoverToTemp(url: String, tempDir: File): File? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                val coverFile = File.createTempFile("cover_", ".jpg", tempDir)
                body.byteStream().use { input ->
                    coverFile.outputStream().use { output -> input.copyTo(output) }
                }
                coverFile
            }
        }.getOrNull()
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
        return if (coverPath == null) {
            "-y -i \"$inputPath\" -vn -c:a libmp3lame -b:a 192k -metadata title=\"$escapedTitle\" -metadata artist=\"$escapedArtist\" \"$outputPath\""
        } else {
            "-y -i \"$inputPath\" -i \"$coverPath\" -map 0:a -map 1:v -c:a libmp3lame -b:a 192k -c:v mjpeg -id3v2_version 3 -metadata title=\"$escapedTitle\" -metadata artist=\"$escapedArtist\" -metadata:s:v title=\"Album cover\" -metadata:s:v comment=\"Cover (front)\" \"$outputPath\""
        }
    }
}
