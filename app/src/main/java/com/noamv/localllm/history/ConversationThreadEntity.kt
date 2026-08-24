package com.noamv.localllm.history

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a conversation thread initiated by an approved client or LocalLLM Manager.
 *
 * Automatic daily insights feed threads have isAutomaticFeed = true so they are separated
 * from regular interactive user conversation threads.
 */
@Entity(
    tableName = "conversation_threads",
    indices = [
        Index(value = ["initiatingClient"]),
        Index(value = ["updatedAt"]),
        Index(value = ["isAutomaticFeed"]),
    ],
)
data class ConversationThreadEntity(
    @PrimaryKey val threadId: String,
    val initiatingClient: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val turnCount: Int,
    val isAutomaticFeed: Boolean = false,
)
