package com.kiptoo.smsforwarder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SmsDao {
    @Insert
    suspend fun insert(sms: SmsEntity): Long

    @Query("SELECT * FROM sms_queue WHERE sent = 0 ORDER BY id ASC LIMIT 50")
    suspend fun pending(): List<SmsEntity>

    @Query("UPDATE sms_queue SET sent = 1, sentAt = :sentAt WHERE id = :id")
    suspend fun markSent(id: Long, sentAt: Long)

    @Query("DELETE FROM sms_queue WHERE sent = 1")
    suspend fun purgeSent()

    @Query("SELECT COUNT(*) FROM sms_queue WHERE sent = 0")
    suspend fun pendingCount(): Int

    // --- Health indicators (Priority 3) ---

    // Most recent successful forward; null if nothing ever sent.
    @Query("SELECT MAX(sentAt) FROM sms_queue WHERE sent = 1")
    suspend fun lastSentAt(): Long?

    // --- Reconciliation sweep (Priority 1) ---

    // Has this exact message already been captured? The sweep keys on the
    // original (smsTimestamp, body); the server dedupes unparsed messages on
    // sha256(device_id | body | sms_timestamp), so we must NOT re-enqueue a
    // message we already hold, regardless of its sent state. If it's pending,
    // ForwardWorker will send it; if it's sent, it already landed. Either way,
    // re-inserting would risk a duplicate row on the server.
    @Query("SELECT COUNT(*) FROM sms_queue WHERE smsTimestamp = :smsTimestamp AND body = :body")
    suspend fun existsByTimestampBody(smsTimestamp: Long, body: String): Int
}