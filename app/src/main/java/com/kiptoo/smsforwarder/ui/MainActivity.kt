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
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.kiptoo.smsforwarder.Prefs
import com.kiptoo.smsforwarder.data.AppDatabase
import com.kiptoo.smsforwarder.databinding.ActivityMainBinding
import com.kiptoo.smsforwarder.work.ForwardScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

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
            // Try flushing immediately in case messages were queued before config.
            ForwardScheduler.enqueueNow(applicationContext)
            refreshStatus()
        }

        binding.btnTest.setOnClickListener { testConnection() }
        binding.btnGrant.setOnClickListener { requestSmsPerms() }
        binding.btnBattery.setOnClickListener { requestBatteryExemption() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
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
                Toast.makeText(this, "Already exempt", Toast.LENGTH_SHORT).show()
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

        binding.tvSmsStatus.text = if (smsOk) "SMS permission: granted" else "SMS permission: NOT granted"
        binding.tvBattStatus.text = if (battOk) "Battery exemption: granted" else "Battery exemption: NOT granted"

        CoroutineScope(Dispatchers.Main).launch {
            val dao = AppDatabase.get(applicationContext).smsDao()
            val count = withContext(Dispatchers.IO) { dao.pendingCount() }
            val lastSent = withContext(Dispatchers.IO) { dao.lastSentAt() }

            binding.tvQueue.text = "Pending in queue: $count"

            val now = System.currentTimeMillis()
            val syncText = if (lastSent == null) "never" else relativeAge(now - lastSent)
            binding.tvHealth.text = "Last sync: $syncText   Queue: $count pending"

            // Dot color: green if recent sync & empty queue; amber if backed up or
            // 30–60 min stale; red if no sync > 60 min.
            val minsSinceSync = if (lastSent == null) Long.MAX_VALUE else (now - lastSent) / 60000L
            val color = when {
                lastSent != null && minsSinceSync < 30 && count == 0 -> GREEN
                minsSinceSync > 60 -> RED
                else -> AMBER
            }
            ImageViewCompat.setImageTintList(binding.ivStatusDot, ColorStateList.valueOf(color))
        }
    }

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
        private val GREEN = Color.parseColor("#2E7D32")
        private val AMBER = Color.parseColor("#F9A825")
        private val RED = Color.parseColor("#C62828")
    }
}