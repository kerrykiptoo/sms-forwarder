package com.kiptoo.smsforwarder

import android.content.Context
import java.util.UUID

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("smsfwd", Context.MODE_PRIVATE)

    var webhookUrl: String
        get() = sp.getString(KEY_URL, "") ?: ""
        set(v) = sp.edit().putString(KEY_URL, v.trim()).apply()

    // Comma-separated sender whitelist, e.g. "MPESA,Equity,KCB"
    var senderWhitelist: String
        get() = sp.getString(KEY_SENDERS, "MPESA") ?: "MPESA"
        set(v) = sp.edit().putString(KEY_SENDERS, v.trim()).apply()

    // Stable per-device id sent with every payload.
    val deviceId: String
        get() {
            var id = sp.getString(KEY_DEVICE, null)
            if (id == null) {
                id = "device-" + UUID.randomUUID().toString().take(8)
                sp.edit().putString(KEY_DEVICE, id).apply()
            }
            return id
        }

    var deviceLabel: String
        get() = sp.getString(KEY_LABEL, "") ?: ""
        set(v) = sp.edit().putString(KEY_LABEL, v.trim()).apply()

    fun whitelistSet(): Set<String> =
        senderWhitelist.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    companion object {
        private const val KEY_URL = "webhook_url"
        private const val KEY_SENDERS = "sender_whitelist"
        private const val KEY_DEVICE = "device_id"
        private const val KEY_LABEL = "device_label"
    }
}