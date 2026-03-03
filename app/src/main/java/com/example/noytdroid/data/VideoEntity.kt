package com.example.noytdroid.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "videos",
    foreignKeys = [
        ForeignKey(
            entity = ChannelEntity::class,
            parentColumns = ["channelId"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["channelId"])]
)
data class VideoEntity(
    @PrimaryKey val videoId: String,
    val channelId: String,
    val title: String,
    val publishedAt: Long,
    val videoUrl: String,
    val fetchedAt: Long,
    val downloadedAt: Long?
)
