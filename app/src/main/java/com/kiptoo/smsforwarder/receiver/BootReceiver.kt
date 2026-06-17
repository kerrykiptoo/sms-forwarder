package com.kiptoo.smsforwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kiptoo.smsforwarder.work.ForwardScheduler

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Reschedule all periodic jobs (flush, sweep, heartbeat) and drain
            // anything left in the queue.
            ForwardScheduler.scheduleAll(context.applicationContext)
            ForwardScheduler.enqueueNow(context.applicationContext)
        }
    }
}