package com.example.noytdroid

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val CRASH_PREFS = "crash_store"
private const val KEY_LAST_CRASH_TS = "last_crash_ts"
private const val KEY_LAST_CRASH_THREAD = "last_crash_thread"
private const val KEY_LAST_CRASH_STACK = "last_crash_stack"

data class LastCrash(
    val timestamp: Long,
    val threadName: String,
    val stacktrace: String
)

class CrashStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)

    fun saveLastCrash(timestamp: Long, threadName: String, stacktrace: String) {
        prefs.edit()
            .putLong(KEY_LAST_CRASH_TS, timestamp)
            .putString(KEY_LAST_CRASH_THREAD, threadName)
            .putString(KEY_LAST_CRASH_STACK, stacktrace)
            .apply()
    }

    fun getLastCrash(): LastCrash? {
        val timestamp = prefs.getLong(KEY_LAST_CRASH_TS, 0L)
        val threadName = prefs.getString(KEY_LAST_CRASH_THREAD, null)
        val stacktrace = prefs.getString(KEY_LAST_CRASH_STACK, null)
        if (timestamp <= 0L || threadName.isNullOrBlank() || stacktrace.isNullOrBlank()) return null
        return LastCrash(timestamp, threadName, stacktrace)
    }

    fun formattedTime(lastCrash: LastCrash): String {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(lastCrash.timestamp))
    }

    companion object {
        fun getLogFile(context: Context): File {
            val logsDir = File(context.filesDir, "logs").apply { mkdirs() }
            return File(logsDir, "app.log")
        }
    }
}
