package com.example.noytdroid.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels ORDER BY LOWER(title)")
    fun observeChannels(): Flow<List<ChannelEntity>>

    @Upsert
    suspend fun upsertChannel(channel: ChannelEntity)

    @Query(
        """
        SELECT * FROM channels
        ORDER BY lastFeedSyncAt IS NOT NULL, lastFeedSyncAt ASC
        LIMIT :limit
        """
    )
    suspend fun getOldestForSync(limit: Int): List<ChannelEntity>


    @Query("SELECT * FROM channels WHERE channelId = :channelId LIMIT 1")
    suspend fun getChannel(channelId: String): ChannelEntity?

    @Query("UPDATE channels SET lastFeedSyncAt = :timestamp, feedError = NULL, feedErrorAt = NULL WHERE channelId = :channelId")
    suspend fun updateLastSync(channelId: String, timestamp: Long)

    @Query("UPDATE channels SET feedError = :error, feedErrorAt = :ts WHERE channelId = :channelId")
    suspend fun markFeedError(channelId: String, error: String, ts: Long)

    @Query("DELETE FROM channels WHERE channelId = :channelId")
    suspend fun deleteChannel(channelId: String)
}
