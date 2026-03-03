package com.example.noytdroid.data

import com.example.noytdroid.VideoItem
import kotlinx.coroutines.flow.Flow
import java.time.Instant

class FeedRepository(private val db: AppDatabase) {
    fun observeChannels(): Flow<List<ChannelEntity>> = db.channelDao().observeChannels()

    suspend fun getChannel(channelId: String): ChannelEntity? = db.channelDao().getChannel(channelId)

    fun observeVideos(channelId: String): Flow<List<VideoEntity>> = db.videoDao().observeVideos(channelId)

    fun observeLatestLogs(limit: Int, workerId: String?, videoId: String?, level: String?): Flow<List<LogEntity>> =
        db.logDao().observeLatest(limit, workerId, videoId, level)

    suspend fun getLatestLogs(limit: Int): List<LogEntity> = db.logDao().getLatest(limit)

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

    suspend fun countVideos(channelId: String): Int = db.videoDao().countVideos(channelId)

    suspend fun countVideosByState(channelId: String, state: String): Int = db.videoDao().countVideosByState(channelId, state)

    suspend fun getLatestFetchedAt(channelId: String): Long? = db.videoDao().getLatestFetchedAt(channelId)

    suspend fun getVideo(videoId: String): VideoEntity? = db.videoDao().getVideo(videoId)

    suspend fun markVideoState(videoId: String, state: String): Int = db.videoDao().markState(videoId, state)

    suspend fun markVideoError(videoId: String, error: String, ts: Long) {
        db.videoDao().markError(videoId, error, ts)
    }

    suspend fun deleteVideo(videoId: String): Int = db.videoDao().deleteVideo(videoId)

    suspend fun deleteVideosForChannel(channelId: String) {
        db.videoDao().deleteVideosForChannel(channelId)
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
