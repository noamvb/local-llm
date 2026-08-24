package com.noamv.localllm.history

import com.noamv.localllm.contract.v2.AppSource
import com.noamv.localllm.contract.v2.AssistantContractV2
import com.noamv.localllm.contract.v2.AssistantTerminalResult
import com.noamv.localllm.contract.v2.AssistantTerminalStatus
import com.noamv.localllm.contract.v2.FactEvidence
import com.noamv.localllm.contract.v2.HistoryQuery
import com.noamv.localllm.contract.v2.SentenceCitation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AssistantHistoryRepositoryTest {

    private lateinit var fakeDao: FakeAssistantHistoryDao
    private lateinit var repository: AssistantHistoryRepository

    @Before
    fun setUp() {
        fakeDao = FakeAssistantHistoryDao()
        repository = AssistantHistoryRepository(fakeDao)
    }

    @Test
    fun testRecordTurnCreatesThreadAndStoresTurn() = runTest {
        val result = AssistantTerminalResult(
            status = AssistantTerminalStatus.VALIDATED,
            finalOrEscapedText = "You spent $45 on OG Kush.",
            citations = listOf(
                SentenceCitation(
                    sentence = "You spent $45 on OG Kush.",
                    citedFactIds = listOf("fact-1"),
                ),
            ),
        )

        val fact = FactEvidence(
            factId = "fact-1",
            sourceApp = AppSource.CANNSHEET,
            sourceContractVersion = 2,
            metricId = "cannsheet.recorded_spend",
            displayLabel = "Spend",
            displayValue = "$45",
            timezone = "America/New_York",
            asOfTime = 1000L,
            sourceRevision = "rev-1",
        )

        val historyId = repository.recordTurn(
            threadId = "thread-100",
            turnId = "turn-200",
            initiatingClient = "com.example.cannsheet",
            question = "How much did I spend on OG Kush?",
            result = result,
            citedFacts = listOf(fact),
            sources = listOf(AppSource.CANNSHEET),
            period = "2026-08-01..2026-08-23",
            asOfTime = 1000L,
            modelVersion = "gemma-4-E2B-it",
        )

        assertEquals(1L, historyId)
        val thread = fakeDao.getThreadById("thread-100")
        assertNotNull(thread)
        assertEquals("How much did I spend on OG Kush?", thread?.title)
        assertEquals("com.example.cannsheet", thread?.initiatingClient)
        assertEquals(1, thread?.turnCount)

        val turns = fakeDao.getTurnsForThread("thread-100")
        assertEquals(1, turns.size)
        assertEquals("turn-200", turns[0].turnId)
        assertEquals("VALIDATED", turns[0].terminalStatus)
        assertEquals("You spent $45 on OG Kush.", turns[0].resultText)
    }

    @Test
    fun testSecondTurnIncrementsTurnCountAndPreservesCreatedAt() = runTest {
        val result1 = AssistantTerminalResult(
            status = AssistantTerminalStatus.VALIDATED,
            finalOrEscapedText = "Answer 1",
        )
        val result2 = AssistantTerminalResult(
            status = AssistantTerminalStatus.VALIDATED,
            finalOrEscapedText = "Answer 2",
        )

        repository.recordTurn(
            threadId = "thread-multi",
            turnId = "turn-1",
            initiatingClient = "com.example.cannsheet",
            question = "Question 1",
            result = result1,
            citedFacts = emptyList(),
            sources = listOf(AppSource.CANNSHEET),
            period = null,
            asOfTime = 1000L,
            modelVersion = "gemma-4-E2B-it",
            timestamp = 1000L,
        )

        repository.recordTurn(
            threadId = "thread-multi",
            turnId = "turn-2",
            initiatingClient = "com.example.cannsheet",
            question = "Question 2",
            result = result2,
            citedFacts = emptyList(),
            sources = listOf(AppSource.CANNSHEET),
            period = null,
            asOfTime = 2000L,
            modelVersion = "gemma-4-E2B-it",
            timestamp = 2000L,
        )

        val thread = fakeDao.getThreadById("thread-multi")
        assertNotNull(thread)
        assertEquals("Question 1", thread?.title)
        assertEquals(2, thread?.turnCount)
        assertEquals(1000L, thread?.createdAt)
        assertEquals(2000L, thread?.updatedAt)

        val turns = fakeDao.getTurnsForThread("thread-multi")
        assertEquals(2, turns.size)
    }

    @Test
    fun testGetHistoryPageWithThreadIdReturnsTurns() = runTest {
        val result = AssistantTerminalResult(
            status = AssistantTerminalStatus.VALIDATED,
            finalOrEscapedText = "Summary text",
        )

        repository.recordTurn(
            threadId = "thread-specific",
            turnId = "turn-specific-1",
            initiatingClient = "com.example.poopschedule",
            question = "Bowel stats?",
            result = result,
            citedFacts = emptyList(),
            sources = listOf(AppSource.POOP_SCHEDULE),
            period = "LAST_30_DAYS",
            asOfTime = 5000L,
            modelVersion = "gemma-4-E2B-it",
        )

        val page = repository.getHistoryPage(HistoryQuery(threadId = "thread-specific"))
        assertEquals(1, page.threads.size)
        assertEquals("thread-specific", page.threads[0].threadId)
        assertEquals(1, page.turns.size)
        assertEquals("Bowel stats?", page.turns[0].question)
        assertFalse(page.hasMore)
    }

    @Test
    fun testGetHistoryPageClientFilteringAndPagination() = runTest {
        val result = AssistantTerminalResult(
            status = AssistantTerminalStatus.VALIDATED,
            finalOrEscapedText = "OK",
        )

        for (i in 1..5) {
            repository.recordTurn(
                threadId = "cannsheet-thread-$i",
                turnId = "turn-c-$i",
                initiatingClient = "com.example.cannsheet",
                question = "Cannsheet Q $i",
                result = result,
                citedFacts = emptyList(),
                sources = listOf(AppSource.CANNSHEET),
                period = null,
                asOfTime = 1000L * i,
                modelVersion = "gemma-4-E2B-it",
                timestamp = 1000L * i,
            )
        }

        for (i in 1..3) {
            repository.recordTurn(
                threadId = "poop-thread-$i",
                turnId = "turn-p-$i",
                initiatingClient = "com.example.poopschedule",
                question = "Poop Q $i",
                result = result,
                citedFacts = emptyList(),
                sources = listOf(AppSource.POOP_SCHEDULE),
                period = null,
                asOfTime = 1000L * (i + 10),
                modelVersion = "gemma-4-E2B-it",
                timestamp = 1000L * (i + 10),
            )
        }

        // Test filtering by Cannsheet with limit 3
        val page1 = repository.getHistoryPage(
            query = HistoryQuery(limit = 3),
            clientFilter = "com.example.cannsheet",
        )
        assertEquals(3, page1.threads.size)
        assertTrue(page1.hasMore)
        assertEquals("3", page1.nextCursor)

        // Test page 2 for Cannsheet
        val page2 = repository.getHistoryPage(
            query = HistoryQuery(cursor = page1.nextCursor, limit = 3),
            clientFilter = "com.example.cannsheet",
        )
        assertEquals(2, page2.threads.size)
        assertFalse(page2.hasMore)

        // Test unfiltered (all threads)
        val allPage = repository.getHistoryPage(
            query = HistoryQuery(limit = 10),
            clientFilter = null,
        )
        assertEquals(8, allPage.threads.size)
    }

    @Test
    fun testDeleteThreadCascadesTurns() = runTest {
        val result = AssistantTerminalResult(
            status = AssistantTerminalStatus.VALIDATED,
            finalOrEscapedText = "OK",
        )

        repository.recordTurn(
            threadId = "thread-to-delete",
            turnId = "turn-del-1",
            initiatingClient = "com.example.cannsheet",
            question = "Q",
            result = result,
            citedFacts = emptyList(),
            sources = listOf(AppSource.CANNSHEET),
            period = null,
            asOfTime = 1000L,
            modelVersion = "gemma-4-E2B-it",
        )

        val deleted = repository.deleteThread("thread-to-delete")
        assertTrue(deleted)
        assertEquals(null, fakeDao.getThreadById("thread-to-delete"))
        assertEquals(0, fakeDao.getTurnsForThread("thread-to-delete").size)
    }

    @Test
    fun testDeriveThreadTitleSanitization() {
        assertEquals("Short question", AssistantHistoryRepository.deriveThreadTitle("Short question"))
        assertEquals("Question with extra spaces", AssistantHistoryRepository.deriveThreadTitle("   Question   with    extra    spaces   "))

        val veryLong = "This is an extremely long question about how much cannabis was consumed across multiple years in the summer"
        val derived = AssistantHistoryRepository.deriveThreadTitle(veryLong)
        assertTrue(derived.length <= 50)
        assertTrue(derived.endsWith("..."))
    }
}

/** In-memory fake DAO for repository unit testing. */
class FakeAssistantHistoryDao : AssistantHistoryDao {
    private val threads = mutableMapOf<String, ConversationThreadEntity>()
    private val turns = mutableListOf<AssistantTurnEntity>()
    private var nextHistoryId = 1L

    override suspend fun insertOrUpdateThread(thread: ConversationThreadEntity) {
        threads[thread.threadId] = thread
    }

    override suspend fun insertTurn(turn: AssistantTurnEntity): Long {
        val id = nextHistoryId++
        turns.add(turn.copy(historyId = id))
        return id
    }

    override suspend fun getAllThreadsPaged(limit: Int, offset: Int): List<ConversationThreadEntity> =
        threads.values
            .filter { !it.isAutomaticFeed }
            .sortedByDescending { it.updatedAt }
            .drop(offset)
            .take(limit)

    override suspend fun getThreadsForClientPaged(
        clientPackage: String,
        limit: Int,
        offset: Int,
    ): List<ConversationThreadEntity> =
        threads.values
            .filter { it.initiatingClient == clientPackage && !it.isAutomaticFeed }
            .sortedByDescending { it.updatedAt }
            .drop(offset)
            .take(limit)

    override suspend fun getThreadById(threadId: String): ConversationThreadEntity? =
        threads[threadId]

    override suspend fun getTurnsForThread(threadId: String): List<AssistantTurnEntity> =
        turns.filter { it.threadId == threadId }.sortedBy { it.timestamp }

    override suspend fun getAutomaticInsightsPaged(limit: Int, offset: Int): List<AssistantTurnEntity> =
        turns.filter { it.isAutomaticFeed }.sortedByDescending { it.timestamp }.drop(offset).take(limit)

    override suspend fun deleteThread(threadId: String): Int {
        val removed = threads.remove(threadId)
        turns.removeAll { it.threadId == threadId }
        return if (removed != null) 1 else 0
    }

    override suspend fun clearAllInteractiveHistory(): Int {
        val count = threads.values.count { !it.isAutomaticFeed }
        val threadIdsToRemove = threads.values.filter { !it.isAutomaticFeed }.map { it.threadId }.toSet()
        threads.keys.removeAll(threadIdsToRemove)
        turns.removeAll { it.threadId in threadIdsToRemove }
        return count
    }

    override suspend fun clearAllHistory(): Int {
        val count = threads.size
        threads.clear()
        turns.clear()
        return count
    }

    override suspend fun getThreadCount(): Int =
        threads.values.count { !it.isAutomaticFeed }
}
