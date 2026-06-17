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
    private const val SWEEP = "sweep_periodic"
    private const val HEARTBEAT = "heartbeat_periodic"

    private val connected = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    // Fired right after an SMS lands. Retries with backoff if webhook is down.
    fun enqueueNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<ForwardWorker>()
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    // Safety net: every ~15 min, flush anything still pending (missed one-shots, reboots).
    fun schedulePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<ForwardWorker>(15, TimeUnit.MINUTES)
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    // Reconciliation sweep: every ~30 min, re-queue any inbox SMS the receiver missed.
    // No network constraint — reading the inbox is local; ForwardWorker handles upload.
    fun scheduleSweep(context: Context) {
        val req = PeriodicWorkRequestBuilder<SweepWorker>(30, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(SWEEP, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    // Heartbeat: every ~15 min, ping the server so it knows the device is alive.
    fun scheduleHeartbeat(context: Context) {
        val req = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(HEARTBEAT, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    // Convenience: schedule all periodic jobs (called from App.onCreate and BootReceiver).
    fun scheduleAll(context: Context) {
        schedulePeriodic(context)
        scheduleSweep(context)
        scheduleHeartbeat(context)
    }
}