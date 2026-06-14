package com.kiptoo.smsforwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.kiptoo.smsforwarder.Prefs
import com.kiptoo.smsforwarder.data.AppDatabase
import com.kiptoo.smsforwarder.data.SmsEntity
import com.kiptoo.smsforwarder.work.ForwardScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        // Multi-part SMS arrives split; concatenate the parts of one message.
        val sender = messages[0].displayOriginatingAddress ?: messages[0].originatingAddress ?: "unknown"
        val body = messages.joinToString("") { it.messageBody ?: "" }
        val smsTs = messages[0].timestampMillis
        val receivedAt = System.currentTimeMillis()

        val prefs = Prefs(context)
        val whitelist = prefs.whitelistSet()
        // If a whitelist is set, only forward matching senders. Empty = forward all.
        if (whitelist.isNotEmpty() && !whitelist.contains(sender.lowercase())) return

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
}
