package com.example.noytdroid

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.TimeUnit

private const val FEED_SYNC_WORK_NAME = "feed_sync_periodic"
private const val FEED_SYNC_BATCH_SIZE = 15

class NoytApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        scheduleFeedSyncWork()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val logger = AppLogger.getInstance(this)
        val crashStore = CrashStore(this)

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val stack = throwable.stackTraceToStringSafe()
                val now = System.currentTimeMillis()
                crashStore.saveLastCrash(now, thread.name, stack)
                logger.fatal("CrashHandler", "Uncaught exception", throwable, thread.name)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun Throwable.stackTraceToStringSafe(): String {
        val writer = StringWriter()
        val printWriter = PrintWriter(writer)
        printStackTrace(printWriter)
        return writer.toString()
    }

    private fun scheduleFeedSyncWork() {
        val request = PeriodicWorkRequestBuilder<FeedSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(workDataOf("batchSize" to FEED_SYNC_BATCH_SIZE))
            .build()

        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork(
                FEED_SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
    }
}
