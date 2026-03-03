package com.example.noytdroid.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {
    @Query("SELECT * FROM videos WHERE channelId = :channelId ORDER BY publishedAt DESC")
    fun observeVideos(channelId: String): Flow<List<VideoEntity>>

    @Upsert
    suspend fun upsertVideos(videos: List<VideoEntity>)

    @Query("DELETE FROM videos WHERE channelId = :channelId")
    suspend fun deleteVideosForChannel(channelId: String)
}
