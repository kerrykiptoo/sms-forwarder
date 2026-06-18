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

    // Trim sent history to the most recent [keep] rows. We deliberately KEEP a
    // small tail of sent rows (not purge them all) so the health indicator and
    // the recent-activity list have data to show. Older sent rows are deleted.
    @Query(
        "DELETE FROM sms_queue WHERE sent = 1 AND id NOT IN " +
        "(SELECT id FROM sms_queue WHERE sent = 1 ORDER BY sentAt DESC LIMIT :keep)"
    )
    suspend fun trimSentHistory(keep: Int)

    @Query("SELECT COUNT(*) FROM sms_queue WHERE sent = 0")
    suspend fun pendingCount(): Int

    // --- Health indicators ---

    // Most recent successful forward; null if nothing ever sent.
    @Query("SELECT MAX(sentAt) FROM sms_queue WHERE sent = 1")
    suspend fun lastSentAt(): Long?

    // Recent activity for the main screen: newest first. Includes both sent
    // (sentAt set) and any currently-pending rows so the list tells the story.
    @Query(
        "SELECT * FROM sms_queue ORDER BY COALESCE(sentAt, receivedAt) DESC LIMIT :limit"
    )
    suspend fun recentActivity(limit: Int): List<SmsEntity>

    // --- Reconciliation sweep ---

    // Has this exact message already been captured? The sweep keys on the
    // original (smsTimestamp, body); the server dedupes unparsed messages on
    // sha256(device_id | body | sms_timestamp), so we must NOT re-enqueue a
    // message we already hold, regardless of its sent state.
    @Query("SELECT COUNT(*) FROM sms_queue WHERE smsTimestamp = :smsTimestamp AND body = :body")
    suspend fun existsByTimestampBody(smsTimestamp: Long, body: String): Int
}