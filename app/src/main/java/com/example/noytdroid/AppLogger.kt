package com.example.noytdroid

import android.content.Context
import android.util.Log
import com.example.noytdroid.data.AppDatabase
import com.example.noytdroid.data.LogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID

data class LogContext(
    val workerId: String? = null,
    val videoId: String? = null,
    val channelId: String? = null,
    val step: String? = null
)

class AppLogger private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionId = UUID.randomUUID().toString()
    private val logFile: File = CrashStore.getLogFile(appContext)

    fun info(tag: String, msg: String, details: String? = null, context: LogContext = LogContext()) {
        Log.i(tag, msg)
        persist("INFO", tag, msg, details, context)
    }

    fun warn(tag: String, msg: String, details: String? = null, context: LogContext = LogContext()) {
        Log.w(tag, msg)
        persist("WARN", tag, msg, details, context)
    }

    fun error(tag: String, msg: String, throwable: Throwable? = null, context: LogContext = LogContext()) {
        Log.e(tag, msg, throwable)
        persist("ERROR", tag, msg, throwable?.toStackTraceString(), context)
    }

    fun fatal(tag: String, msg: String, throwable: Throwable, threadName: String) {
        val details = throwable.toStackTraceString()
        appendToFile("FATAL", tag, "$msg thread=$threadName", details)
        runBlocking(Dispatchers.IO) {
            runCatching {
                AppDatabase.getInstance(appContext).logDao().insert(
                    LogEntity(
                        ts = System.currentTimeMillis(),
                        level = "FATAL",
                        tag = tag,
                        message = msg,
                        details = details,
                        sessionId = sessionId,
                        workerId = null,
                        videoId = null,
                        channelId = null,
                        step = "uncaught"
                    )
                )
            }
        }
    }

    fun getSessionId(): String = sessionId

    private fun persist(level: String, tag: String, message: String, details: String?, context: LogContext) {
        appendToFile(level, tag, message, details, context)
        scope.launch {
            runCatching {
                AppDatabase.getInstance(appContext).logDao().insert(
                    LogEntity(
                        ts = System.currentTimeMillis(),
                        level = level,
                        tag = tag,
                        message = message,
                        details = details,
                        sessionId = sessionId,
                        workerId = context.workerId,
                        videoId = context.videoId,
                        channelId = context.channelId,
                        step = context.step
                    )
                )
            }.onFailure { error ->
                Log.w("AppLogger", "Failed to persist log: ${error.message}")
            }
        }
    }

    private fun appendToFile(level: String, tag: String, message: String, details: String?, context: LogContext = LogContext()) {
        runCatching {
            val row = buildString {
                append(System.currentTimeMillis())
                append(" | ")
                append(level)
                append(" | ")
                append(tag)
                append(" | session=")
                append(sessionId)
                if (!context.workerId.isNullOrBlank()) append(" worker=${context.workerId}")
                if (!context.videoId.isNullOrBlank()) append(" video=${context.videoId}")
                if (!context.channelId.isNullOrBlank()) append(" channel=${context.channelId}")
                if (!context.step.isNullOrBlank()) append(" step=${context.step}")
                append(" | ")
                append(message)
                if (!details.isNullOrBlank()) {
                    append("\n")
                    append(details)
                }
                append("\n")
            }
            synchronized(logFile) {
                logFile.appendText(row)
            }
        }.onFailure { error ->
            Log.w("AppLogger", "Failed to write file log: ${error.message}")
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
