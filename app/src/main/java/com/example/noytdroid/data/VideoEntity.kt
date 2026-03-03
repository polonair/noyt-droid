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
    indices = [Index(value = ["channelId"]), Index(value = ["downloadState", "fetchedAt"])]
)
data class VideoEntity(
    @PrimaryKey val videoId: String,
    val channelId: String,
    val title: String,
    val publishedAt: Long,
    val videoUrl: String,
    val fetchedAt: Long,
    val downloadState: String,
    val downloadedUri: String?,
    val downloadedAt: Long?,
    val downloadError: String?
)

object DownloadState {
    const val NEW = "NEW"
    const val DOWNLOADING = "DOWNLOADING"
    const val DONE = "DONE"
    const val ERROR = "ERROR"
}
