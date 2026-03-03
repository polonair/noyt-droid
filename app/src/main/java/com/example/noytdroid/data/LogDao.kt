package com.example.noytdroid.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {
    @Query(
        """
        SELECT * FROM logs
        WHERE (:workerId IS NULL OR workerId = :workerId)
          AND (:videoId IS NULL OR videoId = :videoId)
          AND (:level IS NULL OR level = :level)
        ORDER BY ts DESC
        LIMIT :limit
        """
    )
    fun observeLatest(limit: Int, workerId: String?, videoId: String?, level: String?): Flow<List<LogEntity>>

    @Insert
    suspend fun insert(log: LogEntity)

    @Query("DELETE FROM logs WHERE ts < :ts")
    suspend fun deleteOlderThan(ts: Long)

    @Query("DELETE FROM logs")
    suspend fun clearAll()

    @Query("SELECT * FROM logs ORDER BY ts DESC LIMIT :limit")
    suspend fun getLatest(limit: Int): List<LogEntity>
}
