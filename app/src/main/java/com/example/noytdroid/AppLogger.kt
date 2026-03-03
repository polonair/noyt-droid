package com.example.noytdroid

import android.content.Context
import android.util.Log
import com.example.noytdroid.data.AppDatabase
import com.example.noytdroid.data.LogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.io.StringWriter

class AppLogger private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun info(tag: String, msg: String, details: String? = null) {
        Log.i(tag, msg)
        persist("INFO", tag, msg, details)
    }

    fun warn(tag: String, msg: String, details: String? = null) {
        Log.w(tag, msg)
        persist("WARN", tag, msg, details)
    }

    fun error(tag: String, msg: String, throwable: Throwable? = null) {
        Log.e(tag, msg, throwable)
        persist("ERROR", tag, msg, throwable?.toStackTraceString())
    }

    private fun persist(level: String, tag: String, message: String, details: String?) {
        scope.launch {
            runCatching {
                AppDatabase.getInstance(appContext).logDao().insert(
                    LogEntity(
                        ts = System.currentTimeMillis(),
                        level = level,
                        tag = tag,
                        message = message,
                        details = details
                    )
                )
            }.onFailure { error ->
                Log.w("AppLogger", "Failed to persist log: ${error.message}")
            }
        }
    }

    private fun Throwable.toStackTraceString(): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        printStackTrace(pw)
        return sw.toString()
    }

    companion object {
        @Volatile
        private var instance: AppLogger? = null

        fun getInstance(context: Context): AppLogger {
            return instance ?: synchronized(this) {
                instance ?: AppLogger(context).also { instance = it }
            }
        }
    }
}
