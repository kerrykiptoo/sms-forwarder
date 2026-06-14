package com.kiptoo.smsforwarder.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kiptoo.smsforwarder.Prefs
import com.kiptoo.smsforwarder.data.AppDatabase
import com.kiptoo.smsforwarder.data.SmsEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ForwardWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = Prefs(applicationContext)
        val url = prefs.webhookUrl
        if (url.isBlank()) {
            // Nothing configured yet; don't burn retries.
            return@withContext Result.success()
        }

        val dao = AppDatabase.get(applicationContext).smsDao()
        val pending = dao.pending()
        if (pending.isEmpty()) return@withContext Result.success()

        var anyFailed = false
        for (sms in pending) {
            val ok = post(url, prefs.deviceId, sms)
            if (ok) dao.markSent(sms.id) else anyFailed = true
        }
        dao.purgeSent()

        // If anything failed, ask WorkManager to retry with backoff.
        if (anyFailed) Result.retry() else Result.success()
    }

    private fun post(url: String, deviceId: String, sms: SmsEntity): Boolean {
        return try {
            val payload = JSONObject().apply {
                put("device_id", deviceId)
                put("sender", sms.sender)
                put("body", sms.body)
                put("received_at", iso8601(sms.receivedAt))
                put("sms_timestamp", sms.smsTimestamp)
            }.toString()

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            false
        }
    }

    private fun iso8601(millis: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        return fmt.format(Date(millis))
    }
}