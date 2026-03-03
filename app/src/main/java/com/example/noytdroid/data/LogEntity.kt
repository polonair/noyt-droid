package com.example.noytdroid.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "logs",
    indices = [
        Index(value = ["ts"]),
        Index(value = ["workerId"]),
        Index(value = ["videoId"]),
        Index(value = ["level"])
    ]
)
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val level: String,
    val tag: String,
    val message: String,
    val details: String?,
    val sessionId: String,
    val workerId: String?,
    val videoId: String?,
    val channelId: String?,
    val step: String?
)
