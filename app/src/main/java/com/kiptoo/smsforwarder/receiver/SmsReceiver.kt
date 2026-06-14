package com.kiptoo.smsforwarder.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import com.kiptoo.smsforwarder.App
import com.kiptoo.smsforwarder.Prefs
import com.kiptoo.smsforwarder.data.AppDatabase
import com.kiptoo.smsforwarder.data.SmsEntity
import com.kiptoo.smsforwarder.work.ForwardScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // DEBUG: prove the receiver fired at all, before any other logic.
        notify(context, "SMS received", "Receiver fired: ${intent.action}")

        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages == null || messages.isEmpty()) {
            notify(context, "SMS parse failed", "getMessagesFromIntent returned empty")
            return
        }

        val sender = messages[0].displayOriginatingAddress ?: messages[0].originatingAddress ?: "unknown"
        val body = messages.joinToString("") { it.messageBody ?: "" }
        val smsTs = messages[0].timestampMillis
        val receivedAt = System.currentTimeMillis()

        // DEBUG: show what we actually parsed.
        notify(context, "Parsed from $sender", body.take(40))

        val prefs = Prefs(context)
        val whitelist = prefs.whitelistSet()
        // Punctuation-tolerant match: "M-PESA" -> "mpesa" so it matches "mpesa".
        val normalizedSender = sender.lowercase().filter { it.isLetterOrDigit() }
        if (whitelist.isNotEmpty() &&
            whitelist.none { normalizedSender.contains(it.filter { c -> c.isLetterOrDigit() }) }
        ) {
            notify(context, "Filtered out", "sender=$sender not in whitelist")
            return
        }

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppDatabase.get(appContext).smsDao().insert(
                    SmsEntity(
                        sender = sender,
                        body = body,
                        smsTimestamp = smsTs,
                        receivedAt = receivedAt
                    )
                )
                ForwardScheduler.enqueueNow(appContext)
            } finally {
                pending.finish()
            }
        }
    }

    private fun notify(context: Context, title: String, text: String) {
        try {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val n = NotificationCompat.Builder(context, App.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            mgr.notify((System.currentTimeMillis() % 100000).toInt(), n)
        } catch (_: Exception) {
        }
    }
}