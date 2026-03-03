package com.example.noytdroid

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.noytdroid.data.AppDatabase
import com.example.noytdroid.data.DownloadState
import com.example.noytdroid.data.VideoEntity

private const val DEFAULT_BATCH_SIZE = 15
private const val KEY_BATCH_SIZE = "batchSize"
private const val TAG_FEED_SYNC_WORKER = "FeedSync"
private const val LOG_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
const val AUTO_DOWNLOAD_ONE_WORK_NAME = "auto_download_one"

class FeedSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.getInstance(applicationContext)
        val channelDao = db.channelDao()
        val videoDao = db.videoDao()
        val logger = AppLogger.getInstance(applicationContext)

        val now = System.currentTimeMillis()
        db.logDao().deleteOlderThan(now - LOG_RETENTION_MS)

        val batchSize = inputData.getInt(KEY_BATCH_SIZE, DEFAULT_BATCH_SIZE)
        val channels = channelDao.getOldestForSync(batchSize)

        logger.info(TAG_FEED_SYNC_WORKER, "Start sync. batchSize=$batchSize channels=${channels.size}")

        var totalNewVideos = 0

        channels.forEach { channel ->
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
                logger.info(
                    TAG_FEED_SYNC_WORKER,
                    "Channel ${channel.channelId} synced. fetched=${videos.size} new=${newVideoEntities.size}"
                )
            }.onFailure { error ->
                logger.error(
                    TAG_FEED_SYNC_WORKER,
                    "Failed channel ${channel.channelId}: ${error.message}",
                    error
                )
            }

            runCatching {
                channelDao.updateLastSync(channel.channelId, syncTimestamp)
            }.onFailure { error ->
                logger.warn(
                    TAG_FEED_SYNC_WORKER,
                    "Failed to update last sync for ${channel.channelId}: ${error.message}",
                    error.message
                )
            }
        }

        logger.info(
            TAG_FEED_SYNC_WORKER,
            "Sync finished. channels=${channels.size} newVideos=$totalNewVideos"
        )

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
    }
}
