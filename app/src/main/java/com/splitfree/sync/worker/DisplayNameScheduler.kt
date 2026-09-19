package com.splitfree.sync.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject

interface DisplayNameScheduler {
    fun requestDrain()
    fun scheduleRecovery()
}

class WorkManagerDisplayNameScheduler @Inject constructor(@ApplicationContext private val context: Context) :
    DisplayNameScheduler {
    override fun requestDrain() {
        val request = OneTimeWorkRequestBuilder<DisplayNameWorker>()
            .setInitialDelay(Duration.ofMillis(800))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "splitfree_display_name",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    override fun scheduleRecovery() {
        val request = PeriodicWorkRequestBuilder<DisplayNameWorker>(Duration.ofMinutes(15)).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "splitfree_display_name_recovery",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
