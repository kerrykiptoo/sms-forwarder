package com.kiptoo.smsforwarder.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.kiptoo.smsforwarder.Prefs
import com.kiptoo.smsforwarder.data.AppDatabase
import com.kiptoo.smsforwarder.data.SmsEntity
import com.kiptoo.smsforwarder.databinding.ActivityMainBinding
import com.kiptoo.smsforwarder.work.ForwardScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val autoRefresh = object : Runnable {
        override fun run() {
            refreshStatus()
            refreshHandler.postDelayed(this, AUTO_REFRESH_MS)
        }
    }

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.etWebhook.setText(prefs.webhookUrl)
        binding.etSenders.setText(prefs.senderWhitelist)
        binding.etLabel.setText(prefs.deviceLabel)
        binding.tvDeviceId.text = "Device ID: ${prefs.deviceId}"

        binding.btnSave.setOnClickListener {
            prefs.webhookUrl = binding.etWebhook.text.toString()
            prefs.senderWhitelist = binding.etSenders.text.toString()
            prefs.deviceLabel = binding.etLabel.text.toString()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            ForwardScheduler.enqueueNow(applicationContext)
            refreshStatus()
        }

        binding.btnTest.setOnClickListener { testConnection() }
        binding.btnRefresh.setOnClickListener { refreshStatus() }
        binding.btnGrant.setOnClickListener { requestSmsPerms() }
        binding.btnBattery.setOnClickListener { requestBatteryExemption() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // Live updates while the screen is open.
        refreshHandler.postDelayed(autoRefresh, AUTO_REFRESH_MS)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(autoRefresh)
    }

    private fun requestSmsPerms() {
        val perms = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPerms.launch(perms.toTypedArray())
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "Already on", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // GET the webhook URL; server returns {"ok":true} when the token is valid.
    private fun testConnection() {
        val url = prefs.webhookUrl
        if (url.isBlank()) {
            binding.tvTestResult.text = "Enter and save a webhook URL first"
            return
        }
        binding.tvTestResult.text = "Testing…"
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 15000
                        readTimeout = 15000
                    }
                    val code = conn.responseCode
                    val text = try {
                        conn.inputStream.bufferedReader().use { it.readText() }
                    } catch (e: Exception) {
                        conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    }
                    conn.disconnect()
                    if (code in 200..299 && text.contains("\"ok\":true")) "Connected ✓"
                    else "Cannot reach server — check URL"
                } catch (e: Exception) {
                    "Cannot reach server — check URL"
                }
            }
            binding.tvTestResult.text = result
        }
    }

    private fun refreshStatus() {
        val smsOk = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED

        val battOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(packageName)
        } else true

        binding.tvSmsStatus.text = if (smsOk) "Reading payment texts: On" else "Reading payment texts: Off"
        binding.tvBattStatus.text = if (battOk) "Always-on: Enabled" else "Always-on: Disabled"

        CoroutineScope(Dispatchers.Main).launch {
            val dao = AppDatabase.get(applicationContext).smsDao()
            val count = withContext(Dispatchers.IO) { dao.pendingCount() }
            val lastSent = withContext(Dispatchers.IO) { dao.lastSentAt() }
            val recent = withContext(Dispatchers.IO) { dao.recentActivity(5) }

            binding.tvQueue.text = "Waiting to send: $count"

            val now = System.currentTimeMillis()
            val syncText = if (lastSent == null) "never" else relativeAge(now - lastSent)
            binding.tvHealth.text = "Last sync: $syncText   ·   $count waiting"

            val minsSinceSync = if (lastSent == null) Long.MAX_VALUE else (now - lastSent) / 60000L
            val color = when {
                lastSent != null && minsSinceSync < 30 && count == 0 -> GREEN
                minsSinceSync > 60 -> RED
                else -> AMBER
            }
            ImageViewCompat.setImageTintList(binding.ivStatusDot, ColorStateList.valueOf(color))

            renderRecent(recent, now)
        }
    }

    private fun renderRecent(rows: List<SmsEntity>, now: Long) {
        val container = binding.recentContainer
        container.removeAllViews()
        if (rows.isEmpty()) {
            container.addView(makeRow("No messages yet", ""))
            return
        }
        for (r in rows) {
            val whenMs = r.sentAt ?: r.receivedAt
            val left = "${r.sender}  ·  ${clock(whenMs)}"
            val right = if (r.sent) "sent" else "pending"
            container.addView(makeRow(left, right, r.sent))
        }
    }

    // A simple two-column row: left text + right status, no adapter needed.
    private fun makeRow(left: String, right: String, sent: Boolean = true): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 8, 0, 8)
        }
        val l = TextView(this).apply {
            text = left
            setTextColor(INK)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val rgt = TextView(this).apply {
            text = right
            gravity = Gravity.END
            if (right == "sent") setTextColor(GREEN)
            else if (right == "pending") setTextColor(AMBER)
        }
        row.addView(l)
        row.addView(rgt)
        return row
    }

    private fun clock(ms: Long): String =
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ms))

    private fun relativeAge(ms: Long): String {
        val mins = ms / 60000L
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 1440 -> "${mins / 60} h ago"
            else -> "${mins / 1440} d ago"
        }
    }

    companion object {
        private const val AUTO_REFRESH_MS = 5000L
        private val INK   = Color.parseColor("#14140F")
        private val GREEN = Color.parseColor("#0E5C4A")
        private val AMBER = Color.parseColor("#B7791F")
        private val RED   = Color.parseColor("#B23B2E")
    }
}