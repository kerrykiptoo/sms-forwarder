package com.kiptoo.smsforwarder.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.kiptoo.smsforwarder.Prefs
import com.kiptoo.smsforwarder.data.AppDatabase
import com.kiptoo.smsforwarder.databinding.ActivityMainBinding
import com.kiptoo.smsforwarder.work.ForwardScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            val count = withContext(Dispatchers.IO) {
                AppDatabase.get(applicationContext).smsDao().pendingCount()
            }
            binding.tvQueue.text = "Pending in queue: $count"
        }
    }
}