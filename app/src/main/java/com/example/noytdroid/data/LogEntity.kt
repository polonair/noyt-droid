package com.example.noytdroid.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "logs",
    indices = [Index(value = ["ts"])]
)
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val level: String,
    val tag: String,
    val message: String,
    val details: String?
)
