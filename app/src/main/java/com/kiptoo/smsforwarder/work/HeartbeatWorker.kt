package com.kiptoo.smsforwarder.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kiptoo.smsforwarder.BuildConfig
import com.kiptoo.smsforwarder.Prefs
import com.kiptoo.smsforwarder.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Heartbeat ping (docs/04-reliability.md, Defense 2).
 *
 * POSTs to /api/webhooks/heartbeat/<token> every ~15 min so the server's
 * device_health view can surface minutes_since_contact — the merchant sees a
 * dark phone before it becomes a missing-ledger mystery.
 *
 * The heartbeat URL is derived from the webhook URL by swapping the `mpesa`
 * path segment for `heartbeat`, so the merchant only ever pastes one URL.
 */
class HeartbeatWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = Prefs(applicationContext)
        val webhook = prefs.webhookUrl
        if (webhook.isBlank()) return@withContext Result.success()

        val heartbeatUrl = deriveHeartbeatUrl(webhook)
            ?: return@withContext Result.success() // URL not in expected shape; skip quietly

        val queueDepth = AppDatabase.get(applicationContext).smsDao().pendingCount()

        return@withContext try {
            val payload = JSONObject().apply {
                put("device_id", prefs.deviceId)
                put("queue_depth", queueDepth)
                put("app_version", BuildConfig.VERSION_NAME)
            }.toString()

            val conn = (URL(heartbeatUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            // A missed heartbeat is not data loss; retry only on transient/server error.
            if (code in 200..299 || code == 400 || code == 401) Result.success()
            else Result.retry()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        /**
         * Swap the `mpesa` path segment for `heartbeat`, preserving host, token,
         * and query. Returns null if there is no `mpesa` segment to swap.
         *
         *   https://host/api/webhooks/mpesa/<token>
         *   -> https://host/api/webhooks/heartbeat/<token>
         */
        fun deriveHeartbeatUrl(webhookUrl: String): String? {
            val marker = "/mpesa/"
            val idx = webhookUrl.indexOf(marker)
            if (idx < 0) return null
            return webhookUrl.substring(0, idx) + "/heartbeat/" + webhookUrl.substring(idx + marker.length)
        }
    }
}