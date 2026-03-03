package com.example.noytdroid.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {
    @Query("SELECT * FROM videos WHERE channelId = :channelId ORDER BY publishedAt DESC")
    fun observeVideos(channelId: String): Flow<List<VideoEntity>>

    @Upsert
    suspend fun upsertVideos(videos: List<VideoEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVideosIgnore(videos: List<VideoEntity>)

    @Query("SELECT videoId FROM videos WHERE channelId = :channelId")
    suspend fun getVideoIdsForChannel(channelId: String): List<String>

    @Query("SELECT * FROM videos WHERE downloadState IN ('NEW', 'ERROR') ORDER BY fetchedAt ASC LIMIT 1")
    suspend fun getOldestUndownloaded(): VideoEntity?

    @Query(
        """
        UPDATE videos
        SET downloadState = :state,
            downloadedAt = :startedAt,
            downloadError = NULL
        WHERE videoId = :videoId
        """
    )
    suspend fun markDownloading(videoId: String, startedAt: Long, state: String = DownloadState.DOWNLOADING)

    @Query(
        """
        UPDATE videos
        SET downloadState = :state,
            downloadedUri = :uri,
            downloadedAt = :doneAt,
            downloadError = NULL
        WHERE videoId = :videoId
        """
    )
    suspend fun markDone(videoId: String, uri: String, doneAt: Long, state: String = DownloadState.DONE)

    @Query(
        """
        UPDATE videos
        SET downloadState = :state,
            downloadError = :error,
            downloadedAt = :ts
        WHERE videoId = :videoId
        """
    )
    suspend fun markError(videoId: String, error: String, ts: Long, state: String = DownloadState.ERROR)

    @Query("DELETE FROM videos WHERE channelId = :channelId")
    suspend fun deleteVideosForChannel(channelId: String)
}
