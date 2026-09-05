package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.ForwardLog
import com.example.data.model.ForwardStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ForwardLogDao {
    @Query("SELECT * FROM forward_logs ORDER BY receivedTimestamp DESC")
    fun getAllLogs(): Flow<List<ForwardLog>>

    @Query("SELECT * FROM forward_logs WHERE status = :status ORDER BY receivedTimestamp DESC")
    fun getLogsByStatus(status: ForwardStatus): Flow<List<ForwardLog>>

    @Query("SELECT * FROM forward_logs WHERE id = :id")
    suspend fun getLogById(id: Long): ForwardLog?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ForwardLog): Long

    @Update
    suspend fun updateLog(log: ForwardLog)

    @Delete
    suspend fun deleteLog(log: ForwardLog)

    @Query("DELETE FROM forward_logs")
    suspend fun clearAllLogs()

    @Query("SELECT * FROM forward_logs WHERE (sender LIKE '%' || :senderQuery || '%' OR matchedRuleLabel LIKE '%' || :senderQuery || '%') AND receivedTimestamp >= :minTimestamp ORDER BY receivedTimestamp DESC")
    suspend fun getLogsForSenderSince(senderQuery: String, minTimestamp: Long): List<ForwardLog>

    @Query("SELECT * FROM forward_logs WHERE (sender LIKE '%' || :senderQuery || '%' OR matchedRuleLabel LIKE '%' || :senderQuery || '%') ORDER BY ABS(receivedTimestamp - :targetTimestamp) ASC LIMIT 1")
    suspend fun getClosestLogForSender(senderQuery: String, targetTimestamp: Long): ForwardLog?

    @Query("SELECT * FROM forward_logs ORDER BY receivedTimestamp DESC LIMIT 1")
    suspend fun getLatestLog(): ForwardLog?

    @Query("SELECT COUNT(*) FROM forward_logs")
    fun getTotalLogsCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM forward_logs WHERE status = 'SUCCESS'")
    fun getSuccessCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM forward_logs WHERE status = 'FAILED'")
    fun getFailedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM forward_logs WHERE status = 'SKIPPED'")
    fun getSkippedCount(): Flow<Int>

    @Query("SELECT * FROM forward_logs WHERE status = 'FAILED' ORDER BY receivedTimestamp ASC LIMIT :limit")
    suspend fun getFailedLogs(limit: Int = 20): List<ForwardLog>

    @Query("SELECT COUNT(*) FROM forward_logs WHERE status = 'FAILED'")
    suspend fun getFailedLogsCountDirect(): Int
}
