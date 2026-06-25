package com.kiptoo.smsforwarder.work

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kiptoo.smsforwarder.Prefs
import com.kiptoo.smsforwarder.data.AppDatabase
import com.kiptoo.smsforwarder.data.SmsEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.kiptoo.smsforwarder.SenderMatcher

/**
 * Reconciliation sweep (docs/04-reliability.md, Defense 1).
 *
 * Reads the device SMS inbox directly and re-queues any whitelisted message
 * from the last [LOOKBACK_MS] that the Room queue does not already hold.
 * Closes the dominant failure mode: an SMS the BroadcastReceiver never saw
 * (process killed, Doze, OEM battery reaper) is silently lost forever.
 *
 * CRITICAL invariant — the dedup contract with the server:
 * The server hashes unparseable messages as sha256(device_id | body | sms_timestamp).
 * We MUST re-enqueue with the SMS's own `date` (epoch millis) as smsTimestamp,
 * the same value first capture used (SmsMessage.timestampMillis == inbox `date`).
 * A different timestamp would create a duplicate row server-side.
 *
 * Parseable messages (with an mpesa_code) dedupe on (merchant_id, mpesa_code)
 * server-side, so re-submitting those is always safe regardless of timestamp.
 */
class SweepWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = Prefs(applicationContext)
        // If no whitelist is configured, the realtime receiver forwards everything;
        // a sweep with no sender filter would scan the entire inbox. Require a
        // whitelist to scope the sweep. Empty = nothing to reconcile here.
        val whitelist = prefs.whitelistSet()
        if (whitelist.isEmpty()) return@withContext Result.success()

        val dao = AppDatabase.get(applicationContext).smsDao()
        val since = System.currentTimeMillis() - LOOKBACK_MS

        val recovered = try {
            scanInbox(since).count { msg ->
                if (!SenderMatcher.matches(msg.sender, whitelist)) return@count false
                // Already held (pending or sent)? Skip — re-inserting risks a dupe.
                val already = dao.existsByTimestampBody(msg.smsTimestamp, msg.body)
                if (already > 0) return@count false
                dao.insert(
                    SmsEntity(
                        sender = msg.sender,
                        body = msg.body,
                        smsTimestamp = msg.smsTimestamp,
                        receivedAt = System.currentTimeMillis()
                    )
                )
                true
            }
        } catch (e: SecurityException) {
            // READ_SMS not granted yet — nothing to do; not a failure to retry.
            return@withContext Result.success()
        } catch (e: Exception) {
            return@withContext Result.retry()
        }

        // If we re-queued anything, kick the forwarder to drain it now.
        if (recovered > 0) ForwardScheduler.enqueueNow(applicationContext)
        Result.success()
    }

    private data class InboxSms(val sender: String, val body: String, val smsTimestamp: Long)

    private fun scanInbox(sinceMillis: Long): List<InboxSms> {
        val out = ArrayList<InboxSms>()
        val uri = Uri.parse("content://sms/inbox")
        val projection = arrayOf("address", "body", "date")
        // date is epoch millis; same basis as SmsMessage.timestampMillis used on capture.
        val selection = "date >= ?"
        val args = arrayOf(sinceMillis.toString())

        applicationContext.contentResolver.query(
            uri, projection, selection, args, "date DESC"
        )?.use { c ->
            val iAddr = c.getColumnIndex("address")
            val iBody = c.getColumnIndex("body")
            val iDate = c.getColumnIndex("date")
            if (iAddr < 0 || iBody < 0 || iDate < 0) return out
            while (c.moveToNext()) {
                val addr = c.getString(iAddr) ?: continue
                val body = c.getString(iBody) ?: continue
                val date = c.getLong(iDate)
                out.add(InboxSms(addr, body, date))
            }
        }
        return out
    }

    companion object {
        private const val LOOKBACK_MS = 48L * 60L * 60L * 1000L // 48 hours
    }
}