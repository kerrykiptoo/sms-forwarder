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

    @Query("UPDATE sms_queue SET sent = 1 WHERE id = :id")
    suspend fun markSent(id: Long)

    @Query("DELETE FROM sms_queue WHERE sent = 1")
    suspend fun purgeSent()

    @Query("SELECT COUNT(*) FROM sms_queue WHERE sent = 0")
    suspend fun pendingCount(): Int
}