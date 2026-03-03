package com.example.noytdroid.data

import com.example.noytdroid.VideoItem
import kotlinx.coroutines.flow.Flow
import java.time.Instant

class FeedRepository(private val db: AppDatabase) {
    fun observeChannels(): Flow<List<ChannelEntity>> = db.channelDao().observeChannels()

    fun observeVideos(channelId: String): Flow<List<VideoEntity>> = db.videoDao().observeVideos(channelId)

    suspend fun upsertChannel(channel: ChannelEntity) {
        db.channelDao().upsertChannel(channel)
    }

    suspend fun upsertVideos(videos: List<VideoEntity>) {
        if (videos.isNotEmpty()) {
            db.videoDao().upsertVideos(videos)
        }
    }
}

fun VideoEntity.toVideoItem(): VideoItem {
    return VideoItem(
        videoId = videoId,
        title = title,
        published = Instant.ofEpochMilli(publishedAt),
        videoUrl = videoUrl
    )
}
