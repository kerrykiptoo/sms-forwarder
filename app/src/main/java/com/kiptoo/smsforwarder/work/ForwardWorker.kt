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

        var anyRetryable = false
        for (sms in pending) {
            when (post(url, prefs.deviceId, sms)) {
                // 2xx: inserted or duplicate — it landed. Mark sent.
                PostResult.LANDED -> dao.markSent(sms.id, System.currentTimeMillis())
                // 400: malformed — retrying forever won't help. Dead-letter by
                // marking sent so it leaves the queue (logged below). It will not
                // re-enter via the sweep because the sweep skips messages we hold.
                PostResult.DEAD_LETTER -> dao.markSent(sms.id, System.currentTimeMillis())
                // 401: bad/inactive token — pause; merchant must fix the URL.
                // Don't mark sent; let the queue hold until config is corrected.
                PostResult.AUTH_FAIL -> anyRetryable = true
                // 5xx / network: transient — retry with backoff.
                PostResult.TRANSIENT -> anyRetryable = true
            }
        }
        dao.purgeSent()

        if (anyRetryable) Result.retry() else Result.success()
    }

    private enum class PostResult { LANDED, DEAD_LETTER, AUTH_FAIL, TRANSIENT }

    private fun post(url: String, deviceId: String, sms: SmsEntity): PostResult {
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
            when {
                code in 200..299 -> PostResult.LANDED
                code == 400 -> PostResult.DEAD_LETTER
                code == 401 -> PostResult.AUTH_FAIL
                else -> PostResult.TRANSIENT   // includes 5xx and anything unexpected
            }
        } catch (e: Exception) {
            PostResult.TRANSIENT
        }
    }

    private fun iso8601(millis: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        return fmt.format(Date(millis))
    }
}