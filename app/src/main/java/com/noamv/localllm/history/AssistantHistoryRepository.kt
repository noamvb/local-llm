package com.noamv.localllm.history

import com.noamv.localllm.contract.v2.AppSource
import com.noamv.localllm.contract.v2.AssistantContractV2
import com.noamv.localllm.contract.v2.AssistantTerminalResult
import com.noamv.localllm.contract.v2.AssistantTerminalStatus
import com.noamv.localllm.contract.v2.FactEvidence
import com.noamv.localllm.contract.v2.HistoryPage
import com.noamv.localllm.contract.v2.HistoryQuery
import com.noamv.localllm.contract.v2.HistoryThreadSummary
import com.noamv.localllm.contract.v2.HistoryTurnRecord
import com.noamv.localllm.contract.v2.SentenceCitation
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

class AssistantHistoryRepository(
    private val dao: AssistantHistoryDao,
) {

    suspend fun recordTurn(
        threadId: String,
        turnId: String,
        initiatingClient: String,
        question: String,
        result: AssistantTerminalResult,
        citedFacts: List<FactEvidence>,
        sources: List<AppSource>,
        period: String?,
        asOfTime: Long,
        modelVersion: String?,
        grammarVersion: Int = AssistantContractV2.GRAMMAR_VERSION,
        isAutomaticFeed: Boolean = false,
        timestamp: Long = System.currentTimeMillis(),
    ): Long {
        val existingThread = dao.getThreadById(threadId)
        val newTurnCount = (existingThread?.turnCount ?: 0) + 1
        val threadTitle = existingThread?.title ?: deriveThreadTitle(question)
        val threadCreatedAt = existingThread?.createdAt ?: timestamp

        val threadEntity = ConversationThreadEntity(
            threadId = threadId,
            initiatingClient = initiatingClient,
            title = threadTitle,
            createdAt = threadCreatedAt,
            updatedAt = timestamp,
            turnCount = newTurnCount,
            isAutomaticFeed = isAutomaticFeed,
        )

        val citationsJson = AssistantContractV2.json.encodeToString(
            ListSerializer(SentenceCitation.serializer()),
            result.citations,
        )
        val citedFactsJson = AssistantContractV2.json.encodeToString(
            ListSerializer(FactEvidence.serializer()),
            citedFacts,
        )
        val sourcesJson = AssistantContractV2.json.encodeToString(
            ListSerializer(AppSource.serializer()),
            sources,
        )
        val validationIssuesJson = AssistantContractV2.json.encodeToString(
            ListSerializer(String.serializer()),
            result.validationIssues,
        )

        val turnEntity = AssistantTurnEntity(
            threadId = threadId,
            turnId = turnId,
            initiatingClient = initiatingClient,
            timestamp = timestamp,
            question = question,
            terminalStatus = result.status.name,
            resultText = result.finalOrEscapedText,
            citationsJson = citationsJson,
            citedFactsJson = citedFactsJson,
            sourcesJson = sourcesJson,
            period = period,
            asOfTime = asOfTime,
            modelVersion = modelVersion,
            grammarVersion = grammarVersion,
            validationIssuesJson = validationIssuesJson,
            isAutomaticFeed = isAutomaticFeed,
        )

        return dao.recordTurnAtomic(threadEntity, turnEntity)
    }

    suspend fun getHistoryPage(
        query: HistoryQuery,
        clientFilter: String? = null,
    ): HistoryPage {
        val offset = query.cursor?.toIntOrNull() ?: 0
        val limit = query.limit.coerceIn(1, 100)

        if (query.threadId != null) {
            val thread = dao.getThreadById(query.threadId) ?: return HistoryPage(emptyList(), emptyList(), null, false)
            if (clientFilter != null && thread.initiatingClient != clientFilter) {
                return HistoryPage(emptyList(), emptyList(), null, false)
            }
            val turns = dao.getTurnsForThread(query.threadId)
            val turnRecords = turns.map { it.toTurnRecord() }
            val threadSummary = thread.toThreadSummary()
            return HistoryPage(
                threads = listOf(threadSummary),
                turns = turnRecords,
                nextCursor = null,
                hasMore = false,
            )
        }

        val threadEntities = if (clientFilter != null) {
            dao.getThreadsForClientPaged(clientFilter, limit = limit + 1, offset = offset)
        } else {
            dao.getAllThreadsPaged(limit = limit + 1, offset = offset)
        }

        val hasMore = threadEntities.size > limit
        val resultThreads = threadEntities.take(limit).map { it.toThreadSummary() }
        val nextCursor = if (hasMore) (offset + limit).toString() else null

        return HistoryPage(
            threads = resultThreads,
            turns = emptyList(),
            nextCursor = nextCursor,
            hasMore = hasMore,
        )
    }

    suspend fun getAutomaticInsightsFeed(
        limit: Int = 20,
        offset: Int = 0,
    ): List<HistoryTurnRecord> {
        val entities = dao.getAutomaticInsightsPaged(limit.coerceIn(1, 100), offset)
        return entities.map { it.toTurnRecord() }
    }

    suspend fun deleteThread(threadId: String): Boolean =
        dao.deleteThread(threadId) > 0

    suspend fun clearAllHistory(): Int =
        dao.clearAllHistory()

    suspend fun clearAllInteractiveHistory(): Int =
        dao.clearAllInteractiveHistory()

    companion object {
        fun deriveThreadTitle(question: String): String {
            val sanitized = question.trim().replace(Regex("\\s+"), " ")
            return if (sanitized.length <= 50) {
                sanitized
            } else {
                sanitized.take(47) + "..."
            }
        }
    }
}

private fun ConversationThreadEntity.toThreadSummary(): HistoryThreadSummary =
    HistoryThreadSummary(
        threadId = threadId,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        turnCount = turnCount,
    )

private fun AssistantTurnEntity.toTurnRecord(): HistoryTurnRecord {
    val statusEnum = try {
        AssistantTerminalStatus.valueOf(terminalStatus)
    } catch (_: IllegalArgumentException) {
        AssistantTerminalStatus.UNKNOWN
    }
    val citations = try {
        AssistantContractV2.json.decodeFromString(
            ListSerializer(SentenceCitation.serializer()),
            citationsJson,
        )
    } catch (_: Exception) {
        emptyList()
    }
    val citedFacts = try {
        AssistantContractV2.json.decodeFromString(
            ListSerializer(FactEvidence.serializer()),
            citedFactsJson,
        )
    } catch (_: Exception) {
        emptyList()
    }
    val sources = try {
        AssistantContractV2.json.decodeFromString(
            ListSerializer(AppSource.serializer()),
            sourcesJson,
        )
    } catch (_: Exception) {
        emptyList()
    }
    val validationIssues = try {
        AssistantContractV2.json.decodeFromString(
            ListSerializer(String.serializer()),
            validationIssuesJson,
        )
    } catch (_: Exception) {
        emptyList()
    }

    return HistoryTurnRecord(
        historyId = historyId,
        threadId = threadId,
        timestamp = timestamp,
        question = question,
        status = statusEnum,
        resultText = resultText,
        citations = citations,
        citedFacts = citedFacts,
        sources = sources,
        period = period,
        asOfTime = asOfTime,
        modelVersion = modelVersion,
        validationIssues = validationIssues,
    )
}
