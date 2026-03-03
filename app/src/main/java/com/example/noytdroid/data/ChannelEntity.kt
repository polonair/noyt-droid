package com.example.noytdroid.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val channelId: String,
    val title: String,
    val avatarUrl: String?,
    val sourceUrl: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastFeedSyncAt: Long? = null
)
