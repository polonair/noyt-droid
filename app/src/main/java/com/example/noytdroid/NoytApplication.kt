package com.example.noytdroid

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

private const val FEED_SYNC_WORK_NAME = "feed_sync"
private const val FEED_SYNC_BATCH_SIZE = 15

class NoytApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        scheduleFeedSyncWork()
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
