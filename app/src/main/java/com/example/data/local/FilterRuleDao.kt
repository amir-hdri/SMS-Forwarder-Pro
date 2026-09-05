package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.FilterRule
import kotlinx.coroutines.flow.Flow

@Dao
interface FilterRuleDao {
    @Query("SELECT * FROM filter_rules ORDER BY createdAt DESC")
    fun getAllRules(): Flow<List<FilterRule>>

    @Query("SELECT * FROM filter_rules WHERE isEnabled = 1")
    suspend fun getActiveRules(): List<FilterRule>

    @Query("SELECT * FROM filter_rules WHERE id = :id")
    suspend fun getRuleById(id: Long): FilterRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: FilterRule): Long

    @Update
    suspend fun updateRule(rule: FilterRule)

    @Delete
    suspend fun deleteRule(rule: FilterRule)

    @Query("DELETE FROM filter_rules WHERE id = :id")
    suspend fun deleteRuleById(id: Long)

    @Query("SELECT COUNT(*) FROM filter_rules")
    fun getRulesCount(): Flow<Int>
}
