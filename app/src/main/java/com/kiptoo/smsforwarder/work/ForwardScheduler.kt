package com.kiptoo.smsforwarder.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object ForwardScheduler {

    private const val ONE_SHOT = "forward_once"
    private const val PERIODIC = "forward_periodic"

    // Fired right after an SMS lands. Retries with backoff if webhook is down.
    fun enqueueNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<ForwardWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    // Safety net: every ~15 min, flush anything still pending (missed one-shots, reboots).
    fun schedulePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<ForwardWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}