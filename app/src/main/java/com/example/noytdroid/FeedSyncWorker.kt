package com.example.noytdroid

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.noytdroid.data.AppDatabase
import com.example.noytdroid.data.VideoEntity

private const val DEFAULT_BATCH_SIZE = 15
private const val KEY_BATCH_SIZE = "batchSize"
private const val TAG_FEED_SYNC_WORKER = "FeedSyncWorker"

class FeedSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.getInstance(applicationContext)
        val channelDao = db.channelDao()
        val videoDao = db.videoDao()

        val batchSize = inputData.getInt(KEY_BATCH_SIZE, DEFAULT_BATCH_SIZE)
        val channels = channelDao.getOldestForSync(batchSize)

        channels.forEach { channel ->
            val syncTimestamp = System.currentTimeMillis()
            runCatching {
                val videos = fetchChannelFeed(channel.channelId)
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
                videoDao.upsertVideos(videoEntities)
            }.onFailure { error ->
                Log.w(
                    TAG_FEED_SYNC_WORKER,
                    "Failed to sync feed for ${channel.channelId}: ${error.message}",
                    error
                )
            }

            runCatching {
                channelDao.updateLastSync(channel.channelId, syncTimestamp)
            }.onFailure { error ->
                Log.w(
                    TAG_FEED_SYNC_WORKER,
                    "Failed to update last sync for ${channel.channelId}: ${error.message}",
                    error
                )
            }
        }

        return Result.success()
    }
}
