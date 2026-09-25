package com.example.eva.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationEntity::class,
        CommandHistoryEntity::class,
        ScheduledTaskEntity::class,
        CustomAutomationEntity::class,
        VoiceRecordingEntity::class,
        FavoriteAppEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class EvaDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun commandHistoryDao(): CommandHistoryDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao
    abstract fun customAutomationDao(): CustomAutomationDao
    abstract fun voiceRecordingDao(): VoiceRecordingDao
    abstract fun favoriteAppDao(): FavoriteAppDao

    companion object {
        @Volatile
        private var INSTANCE: EvaDatabase? = null

        fun getInstance(context: Context): EvaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    EvaDatabase::class.java,
                    "eva_database.db"
                ).fallbackToDestructiveMigration(false)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
