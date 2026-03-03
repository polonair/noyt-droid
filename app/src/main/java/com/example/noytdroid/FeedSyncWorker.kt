package com.example.noytdroid

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.noytdroid.data.AppDatabase
import com.example.noytdroid.data.VideoEntity

private const val DEFAULT_BATCH_SIZE = 15
private const val KEY_BATCH_SIZE = "batchSize"
private const val TAG_FEED_SYNC_WORKER = "FeedSync"
private const val LOG_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

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
                val videos = fetchChannelFeed(channel.channelId, logger)
                val videoEntities = videos.map { video ->
                    VideoEntity(
                        videoId = video.videoId,
                        channelId = channel.channelId,
                        title = video.title,
                        publishedAt = video.published.toEpochMilli(),
                        videoUrl = video.videoUrl,
                        fetchedAt = syncTimestamp,
                        downloadedAt = null
                    )
                }

                val existingVideoIds = videoDao.getVideoIdsForChannel(channel.channelId).toHashSet()
                val newVideosCount = videoEntities.count { !existingVideoIds.contains(it.videoId) }
                totalNewVideos += newVideosCount

                videoDao.upsertVideos(videoEntities)
                logger.info(
                    TAG_FEED_SYNC_WORKER,
                    "Channel ${channel.channelId} synced. fetched=${videoEntities.size} new=$newVideosCount"
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

        return Result.success()
    }
}
