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

    @Query("DELETE FROM channels WHERE channelId = :channelId")
    suspend fun deleteChannel(channelId: String)
}
