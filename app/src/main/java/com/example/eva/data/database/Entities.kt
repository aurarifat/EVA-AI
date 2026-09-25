package com.example.eva.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String, // "user", "eva", "tool"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val toolName: String? = null,
    val toolStatus: String? = null // "executing", "success", "error"
)

@Entity(tableName = "command_history")
data class CommandHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val command: String,
    val response: String,
    val toolName: String?,
    val timestamp: Long = System.currentTimeMillis(),
    val isSuccess: Boolean = true
)

@Entity(tableName = "scheduled_tasks")
data class ScheduledTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val details: String = "",
    val timeMillis: Long,
    val isRecurring: Boolean = false,
    val recurringPattern: String = "", // "daily", "weekly"
    val isCompleted: Boolean = false
)

@Entity(tableName = "custom_automations")
data class CustomAutomationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val triggerPhrase: String,
    val name: String,
    val actionsJson: String, // JSON list of tool actions
    val isEnabled: Boolean = true
)

@Entity(tableName = "voice_recordings")
data class VoiceRecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val filePath: String,
    val durationSeconds: Int,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "favorite_apps")
data class FavoriteAppEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val launchCount: Int = 1,
    val isFavorite: Boolean = false
)
