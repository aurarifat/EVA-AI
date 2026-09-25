package com.example.eva.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<ConversationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ConversationEntity): Long

    @Query("DELETE FROM conversations")
    suspend fun clearConversations()
}

@Dao
interface CommandHistoryDao {
    @Query("SELECT * FROM command_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<CommandHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(item: CommandHistoryEntity): Long

    @Query("DELETE FROM command_history WHERE id = :id")
    suspend fun deleteHistory(id: Long)

    @Query("DELETE FROM command_history")
    suspend fun clearHistory()
}

@Dao
interface ScheduledTaskDao {
    @Query("SELECT * FROM scheduled_tasks ORDER BY timeMillis ASC")
    fun getTasks(): Flow<List<ScheduledTaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: ScheduledTaskEntity): Long

    @Update
    suspend fun updateTask(task: ScheduledTaskEntity)

    @Delete
    suspend fun deleteTask(task: ScheduledTaskEntity)
}

@Dao
interface CustomAutomationDao {
    @Query("SELECT * FROM custom_automations")
    fun getAllAutomations(): Flow<List<CustomAutomationEntity>>

    @Query("SELECT * FROM custom_automations WHERE LOWER(triggerPhrase) = LOWER(:phrase) AND isEnabled = 1 LIMIT 1")
    suspend fun findByTrigger(phrase: String): CustomAutomationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAutomation(automation: CustomAutomationEntity): Long

    @Update
    suspend fun updateAutomation(automation: CustomAutomationEntity)

    @Delete
    suspend fun deleteAutomation(automation: CustomAutomationEntity)
}

@Dao
interface VoiceRecordingDao {
    @Query("SELECT * FROM voice_recordings ORDER BY timestamp DESC")
    fun getAllRecordings(): Flow<List<VoiceRecordingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecording(recording: VoiceRecordingEntity): Long

    @Delete
    suspend fun deleteRecording(recording: VoiceRecordingEntity)
}

@Dao
interface FavoriteAppDao {
    @Query("SELECT * FROM favorite_apps ORDER BY launchCount DESC")
    fun getFavoriteApps(): Flow<List<FavoriteAppEntity>>

    @Query("SELECT * FROM favorite_apps WHERE packageName = :pkg LIMIT 1")
    suspend fun getApp(pkg: String): FavoriteAppEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(app: FavoriteAppEntity)
}
