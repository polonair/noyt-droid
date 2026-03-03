package com.example.noytdroid.data

import com.example.noytdroid.VideoItem
import kotlinx.coroutines.flow.Flow
import java.time.Instant

class FeedRepository(private val db: AppDatabase) {
    fun observeChannels(): Flow<List<ChannelEntity>> = db.channelDao().observeChannels()

    fun observeVideos(channelId: String): Flow<List<VideoEntity>> = db.videoDao().observeVideos(channelId)

    fun observeLatestLogs(limit: Int): Flow<List<LogEntity>> = db.logDao().observeLatest(limit)

    suspend fun upsertChannel(channel: ChannelEntity) {
        db.channelDao().upsertChannel(channel)
    }

    suspend fun upsertVideos(videos: List<VideoEntity>) {
        if (videos.isNotEmpty()) {
            db.videoDao().insertVideosIgnore(videos)
        }
    }

    suspend fun clearLogs() {
        db.logDao().clearAll()
    }

    suspend fun deleteLogsOlderThan(ts: Long) {
        db.logDao().deleteOlderThan(ts)
    }
}

fun VideoEntity.toVideoItem(): VideoItem {
    return VideoItem(
        videoId = videoId,
        title = title,
        published = if (publishedAt > 0) Instant.ofEpochMilli(publishedAt) else null,
        videoUrl = videoUrl
    )
}
