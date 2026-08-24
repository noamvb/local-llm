package com.noamv.localllm.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationThreadEntity::class,
        AssistantTurnEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AssistantDatabase : RoomDatabase() {
    abstract fun historyDao(): AssistantHistoryDao

    companion object {
        private const val DB_NAME = "assistant_history.db"

        @Volatile
        private var instance: AssistantDatabase? = null

        fun getInstance(context: Context): AssistantDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AssistantDatabase::class.java,
                    DB_NAME,
                ).build().also { instance = it }
            }
    }
}
