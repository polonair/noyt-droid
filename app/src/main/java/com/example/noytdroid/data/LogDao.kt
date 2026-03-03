package com.example.noytdroid.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {
    @Query("SELECT * FROM logs ORDER BY ts DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<LogEntity>>

    @Insert
    suspend fun insert(log: LogEntity)

    @Query("DELETE FROM logs WHERE ts < :ts")
    suspend fun deleteOlderThan(ts: Long)

    @Query("DELETE FROM logs")
    suspend fun clearAll()
}
