package com.kiptoo.smsforwarder

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.kiptoo.smsforwarder.work.ForwardScheduler

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Safety nets: periodic flush, reconciliation sweep, and heartbeat.
        ForwardScheduler.scheduleAll(this)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SMS Forwarding",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Background SMS forwarding status" }
            mgr.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "sms_forward_channel"
    }
}