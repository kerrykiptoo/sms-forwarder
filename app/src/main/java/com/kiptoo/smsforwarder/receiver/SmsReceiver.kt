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
        // Punctuation-tolerant match: "M-PESA" normalizes to "mpesa" so it matches "mpesa".
        // Empty whitelist = forward all senders.
        val normalizedSender = sender.lowercase().filter { it.isLetterOrDigit() }
        if (whitelist.isNotEmpty() &&
            whitelist.none { entry ->
                val e = entry.filter { it.isLetterOrDigit() }
                e.isNotEmpty() && normalizedSender.contains(e)
            }
        ) {
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
}