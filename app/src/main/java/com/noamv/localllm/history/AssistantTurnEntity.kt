package com.noamv.localllm.history

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An immutable persisted record of an assistant turn (question, citations, evidence, and outcome).
 *
 * Foreign key cascades thread deletion.
 */
@Entity(
    tableName = "assistant_turns",
    foreignKeys = [
        ForeignKey(
            entity = ConversationThreadEntity::class,
            parentColumns = ["threadId"],
            childColumns = ["threadId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["threadId"]),
        Index(value = ["turnId"], unique = true),
        Index(value = ["timestamp"]),
        Index(value = ["isAutomaticFeed"]),
    ],
)
data class AssistantTurnEntity(
    @PrimaryKey(autoGenerate = true) val historyId: Long = 0,
    val threadId: String,
    val turnId: String,
    val initiatingClient: String,
    val timestamp: Long,
    val question: String,
    val terminalStatus: String,
    val resultText: String,
    val citationsJson: String,
    val citedFactsJson: String,
    val sourcesJson: String,
    val period: String?,
    val asOfTime: Long,
    val modelVersion: String?,
    val grammarVersion: Int,
    val validationIssuesJson: String,
    val isAutomaticFeed: Boolean = false,
)
