package com.kiptoo.smsforwarder.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sms_queue")
data class SmsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val body: String,
    val smsTimestamp: Long,   // millis from the SMS itself (SmsMessage.date) — the dedup key
    val receivedAt: Long,     // millis we received it
    val sent: Boolean = false,
    val sentAt: Long? = null  // millis we successfully forwarded it; null until sent
)