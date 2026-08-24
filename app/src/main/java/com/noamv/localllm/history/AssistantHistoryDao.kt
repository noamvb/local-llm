package com.noamv.localllm.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface AssistantHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateThread(thread: ConversationThreadEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTurn(turn: AssistantTurnEntity): Long

    @Transaction
    suspend fun recordTurnAtomic(
        thread: ConversationThreadEntity,
        turn: AssistantTurnEntity,
    ): Long {
        insertOrUpdateThread(thread)
        return insertTurn(turn)
    }

    @Query(
        """
        SELECT * FROM conversation_threads
        WHERE isAutomaticFeed = 0
        ORDER BY updatedAt DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun getAllThreadsPaged(limit: Int, offset: Int): List<ConversationThreadEntity>

    @Query(
        """
        SELECT * FROM conversation_threads
        WHERE initiatingClient = :clientPackage AND isAutomaticFeed = 0
        ORDER BY updatedAt DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun getThreadsForClientPaged(
        clientPackage: String,
        limit: Int,
        offset: Int,
    ): List<ConversationThreadEntity>

    @Query(
        """
        SELECT * FROM conversation_threads
        WHERE threadId = :threadId
        LIMIT 1
        """,
    )
    suspend fun getThreadById(threadId: String): ConversationThreadEntity?

    @Query(
        """
        SELECT * FROM assistant_turns
        WHERE threadId = :threadId
        ORDER BY timestamp ASC
        """,
    )
    suspend fun getTurnsForThread(threadId: String): List<AssistantTurnEntity>

    @Query(
        """
        SELECT * FROM assistant_turns
        WHERE isAutomaticFeed = 1
        ORDER BY timestamp DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun getAutomaticInsightsPaged(limit: Int, offset: Int): List<AssistantTurnEntity>

    @Query(
        """
        DELETE FROM conversation_threads
        WHERE threadId = :threadId
        """,
    )
    suspend fun deleteThread(threadId: String): Int

    @Query("DELETE FROM conversation_threads WHERE isAutomaticFeed = 0")
    suspend fun clearAllInteractiveHistory(): Int

    @Query("DELETE FROM conversation_threads")
    suspend fun clearAllHistory(): Int

    @Query("SELECT COUNT(*) FROM conversation_threads WHERE isAutomaticFeed = 0")
    suspend fun getThreadCount(): Int
}
