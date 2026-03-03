package com.example.noytdroid

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.noytdroid.data.AppDatabase
import com.example.noytdroid.data.DownloadState
import com.example.noytdroid.data.VideoEntity
import java.util.UUID

private const val DEFAULT_BATCH_SIZE = 15
private const val KEY_BATCH_SIZE = "batchSize"
private const val KEY_CHANNEL_ID = "channelId"
private const val TAG_FEED_SYNC_WORKER = "FeedSync"
private const val LOG_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
private const val FEED_SYNC_CHANNEL_ID = "feed_sync"
const val AUTO_DOWNLOAD_ONE_WORK_NAME = "auto_download_one"

class FeedSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    private val logger = AppLogger.getInstance(applicationContext)
    private val workerId = id.toString()

    override suspend fun doWork(): Result {
        val db = AppDatabase.getInstance(applicationContext)
        val channelDao = db.channelDao()
        val videoDao = db.videoDao()
        var currentChannelId: String? = null

        logger.info(TAG_FEED_SYNC_WORKER, "start", context = LogContext(workerId = workerId, step = "start"))
        try {
            db.logDao().deleteOlderThan(System.currentTimeMillis() - LOG_RETENTION_MS)
            val batchSize = inputData.getInt(KEY_BATCH_SIZE, DEFAULT_BATCH_SIZE)
            val forcedChannelId = inputData.getString(KEY_CHANNEL_ID)
            val channels = if (forcedChannelId.isNullOrBlank()) {
                channelDao.getOldestForSync(batchSize)
            } else {
                listOfNotNull(channelDao.getChannel(forcedChannelId))
            }
            var totalNewVideos = 0

            channels.forEach { channel ->
                currentChannelId = channel.channelId
                val syncTimestamp = System.currentTimeMillis()
                runCatching {
                    val videos = fetchChannelFeed(channel.channelId, logger, batchSize)
                    val existingVideoIds = videoDao.getVideoIdsForChannel(channel.channelId).toHashSet()
                    val newVideoEntities = videos
                        .filterNot { existingVideoIds.contains(it.videoId) }
                        .map { video ->
                            VideoEntity(
                                videoId = video.videoId,
                                channelId = channel.channelId,
                                title = video.title,
                                publishedAt = video.published?.toEpochMilli() ?: 0L,
                                videoUrl = video.videoUrl,
                                fetchedAt = syncTimestamp,
                                downloadState = DownloadState.NEW,
                                downloadedUri = null,
                                downloadedAt = null,
                                downloadError = null
                            )
                        }
                    totalNewVideos += newVideoEntities.size
                    videoDao.insertVideosIgnore(newVideoEntities)
                    channelDao.updateLastSync(channel.channelId, syncTimestamp)
                }.onFailure { error ->
                    channelDao.markFeedError(channel.channelId, error.message ?: "feed sync failed", System.currentTimeMillis())
                    logger.error(
                        TAG_FEED_SYNC_WORKER,
                        "crash channel=${channel.channelId}",
                        error,
                        LogContext(workerId = workerId, channelId = channel.channelId, step = "sync_channel")
                    )
                }
            }

            logger.info(TAG_FEED_SYNC_WORKER, "end newVideos=$totalNewVideos", context = LogContext(workerId = workerId, step = "end"))
            WorkManager.getInstance(applicationContext)
                .enqueueUniqueWork(
                    AUTO_DOWNLOAD_ONE_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<AutoDownloadOneWorker>()
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build()
                        )
                        .build()
                )
            return Result.success()
        } catch (t: Throwable) {
            currentChannelId?.let { channelDao.markFeedError(it, t.stackTraceToString(), System.currentTimeMillis()) }
            logger.error(TAG_FEED_SYNC_WORKER, "crash", t, LogContext(workerId = workerId, channelId = currentChannelId, step = "crash"))
            notifyBackgroundFailure()
            return Result.retry()
        } finally {
            logger.info(TAG_FEED_SYNC_WORKER, "finally", context = LogContext(workerId = workerId, channelId = currentChannelId, step = "finally"))
        }
    }

    private fun notifyBackgroundFailure() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(FEED_SYNC_CHANNEL_ID, "Background sync", NotificationManager.IMPORTANCE_DEFAULT)
            )
            manager.notify(
                UUID.randomUUID().hashCode(),
                NotificationCompat.Builder(applicationContext, FEED_SYNC_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("Background task failed")
                    .setContentText("Crash in worker. Open app logs for details")
                    .build()
            )
        }
    }
}
