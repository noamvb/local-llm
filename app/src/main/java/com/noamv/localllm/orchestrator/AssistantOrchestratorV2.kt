package com.noamv.localllm.orchestrator

import com.noamv.localllm.contract.InsightRequest
import com.noamv.localllm.contract.InsightTask
import com.noamv.localllm.contract.Period
import com.noamv.localllm.contract.v2.AggregateQuery
import com.noamv.localllm.contract.v2.AppSource
import com.noamv.localllm.contract.v2.AssistantContractV2
import com.noamv.localllm.contract.v2.AssistantEvent
import com.noamv.localllm.contract.v2.AssistantEventType
import com.noamv.localllm.contract.v2.AssistantTerminalResult
import com.noamv.localllm.contract.v2.AssistantTerminalStatus
import com.noamv.localllm.contract.v2.AssistantTurnRequest
import com.noamv.localllm.contract.v2.FactEvidence
import com.noamv.localllm.contract.v2.HistoryQuery
import com.noamv.localllm.contract.v2.ProviderFactsResult
import com.noamv.localllm.contract.v2.RouterDecision
import com.noamv.localllm.engine.GenerationOutputPolicy
import com.noamv.localllm.engine.LlmEngine
import com.noamv.localllm.engine.ModelResidencyCoordinator
import com.noamv.localllm.history.AssistantHistoryRepository
import com.noamv.localllm.privacy.AssistantAccessPolicy
import com.noamv.localllm.router.DeterministicQueryRouter
import com.noamv.localllm.validation.SentenceCitationValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID

internal class AssistantOrchestratorV2(
    private val engine: LlmEngine,
    private val historyRepository: AssistantHistoryRepository,
    private val accessPolicy: AssistantAccessPolicy,
    private val residencyCoordinator: ModelResidencyCoordinator,
    private val factProviderQuery: suspend (AppSource, AggregateQuery) -> ProviderFactsResult,
) {

    suspend fun executeTurn(
        request: AssistantTurnRequest,
        onEvent: suspend (AssistantEvent) -> Unit,
    ): AssistantTerminalResult {
        // 1. Access policy check
        if (!accessPolicy.isClientAccessAllowed(request.initiatingClient)) {
            val result = AssistantTerminalResult(
                status = AssistantTerminalStatus.ERROR,
                finalOrEscapedText = "Access disabled by LocalLLM host owner.",
                validationIssues = listOf("Client package not permitted by host privacy policy"),
            )
            onEvent(AssistantEvent(AssistantEventType.FAILURE, detail = "Access disabled"))
            return result
        }

        // 2. Routing
        onEvent(AssistantEvent(AssistantEventType.ROUTING, stage = "Determining query intent"))
        val decision = DeterministicQueryRouter.route(
            question = request.question,
            defaultSource = request.defaultSource,
            allowCrossApp = request.allowCrossApp,
        )

        return when (decision) {
            is RouterDecision.Clarify -> {
                onEvent(
                    AssistantEvent(
                        eventType = AssistantEventType.CLARIFICATION_REQUIRED,
                        stage = decision.clarificationId.name,
                    ),
                )
                val clarifyResult = AssistantTerminalResult(
                    status = AssistantTerminalStatus.UNSUPPORTED,
                    finalOrEscapedText = "Clarification required: ${decision.clarificationId.name}",
                    limitations = listOf(decision.clarificationId.name),
                )
                val historyId = historyRepository.recordTurn(
                    threadId = request.threadId,
                    turnId = request.requestId,
                    initiatingClient = request.initiatingClient,
                    question = request.question,
                    result = clarifyResult,
                    citedFacts = emptyList(),
                    sources = listOf(request.defaultSource),
                    period = null,
                    asOfTime = System.currentTimeMillis(),
                    modelVersion = null,
                )
                clarifyResult.copy(historyId = historyId)
            }

            is RouterDecision.Unsupported -> {
                onEvent(
                    AssistantEvent(
                        eventType = AssistantEventType.FAILURE,
                        stage = decision.limitationId.name,
                    ),
                )
                val unsupportedResult = AssistantTerminalResult(
                    status = AssistantTerminalStatus.UNSUPPORTED,
                    finalOrEscapedText = "Request unsupported: ${decision.limitationId.name}",
                    limitations = listOf(decision.limitationId.name),
                )
                val historyId = historyRepository.recordTurn(
                    threadId = request.threadId,
                    turnId = request.requestId,
                    initiatingClient = request.initiatingClient,
                    question = request.question,
                    result = unsupportedResult,
                    citedFacts = emptyList(),
                    sources = listOf(request.defaultSource),
                    period = null,
                    asOfTime = System.currentTimeMillis(),
                    modelVersion = null,
                )
                unsupportedResult.copy(historyId = historyId)
            }

            is RouterDecision.Query -> {
                val query = decision.query
                onEvent(
                    AssistantEvent(
                        eventType = AssistantEventType.PROVIDER_STATUS,
                        stage = "Querying data providers",
                    ),
                )

                val allFacts = mutableListOf<FactEvidence>()
                var latestAsOfTime = System.currentTimeMillis()

                for (source in query.sources) {
                    if (accessPolicy.isSourceQueryAllowed(source.name)) {
                        val providerResult = factProviderQuery(source, query)
                        allFacts.addAll(providerResult.facts)
                        if (providerResult.asOfTime > 0) {
                            latestAsOfTime = providerResult.asOfTime
                        }
                    }
                }

                // If no facts returned
                if (allFacts.isEmpty()) {
                    val noFactsResult = AssistantTerminalResult(
                        status = AssistantTerminalStatus.PARTIAL_SOURCE,
                        finalOrEscapedText = "No data records found for the requested period.",
                    )
                    val historyId = historyRepository.recordTurn(
                        threadId = request.threadId,
                        turnId = request.requestId,
                        initiatingClient = request.initiatingClient,
                        question = request.question,
                        result = noFactsResult,
                        citedFacts = emptyList(),
                        sources = query.sources,
                        period = query.period.toString(),
                        asOfTime = latestAsOfTime,
                        modelVersion = null,
                    )
                    return noFactsResult.copy(historyId = historyId)
                }

                // Read conversation history if thread has prior turns
                val priorHistoryPage = historyRepository.getHistoryPage(HistoryQuery(threadId = request.threadId))
                val priorTurns = priorHistoryPage.turns.filter { it.status == AssistantTerminalStatus.VALIDATED }

                // Build Prompt & Warm Model
                onEvent(AssistantEvent(AssistantEventType.MODEL_LOADING, stage = "Warming model"))
                engine.prepare { _, _ -> }

                val writerPrompt = buildWriterPrompt(request.question, allFacts, priorTurns)

                // Generate and stream drafts
                val rawOutput = StringBuilder()
                val legacyRequest = InsightRequest(
                    clientId = request.initiatingClient,
                    task = InsightTask.PERIOD_SUMMARY,
                    subject = "personal assistant aggregate data",
                    period = Period(
                        label = query.period.toString(),
                        start = null,
                        end = null,
                    ),
                    facts = allFacts.map { fact ->
                        com.noamv.localllm.contract.Fact(
                            label = fact.displayLabel,
                            value = fact.displayValue,
                            note = fact.qualifier,
                        )
                    },
                    stream = true,
                )

                engine.generate(legacyRequest).collect { chunk ->
                    rawOutput.append(chunk)
                    onEvent(
                        AssistantEvent(
                            eventType = AssistantEventType.DRAFT,
                            draftText = chunk,
                        ),
                    )
                }

                val generatedText = rawOutput.toString().trim()

                // Validate citations and groundedness
                val validationResult = SentenceCitationValidator.validate(
                    generatedText = generatedText,
                    facts = allFacts,
                )

                // Record turn in history repository atomically
                val historyId = historyRepository.recordTurn(
                    threadId = request.threadId,
                    turnId = request.requestId,
                    initiatingClient = request.initiatingClient,
                    question = request.question,
                    result = validationResult,
                    citedFacts = allFacts,
                    sources = query.sources,
                    period = query.period.toString(),
                    asOfTime = latestAsOfTime,
                    modelVersion = "gemma-4-E2B-it",
                )

                onEvent(AssistantEvent(AssistantEventType.COMPLETE))
                validationResult.copy(historyId = historyId)
            }
        }
    }

    companion object {
        fun buildWriterPrompt(
            question: String,
            facts: List<FactEvidence>,
            priorTurns: List<com.noamv.localllm.contract.v2.HistoryTurnRecord>,
        ): String {
            val sb = StringBuilder()
            if (priorTurns.isNotEmpty()) {
                sb.append("Previous Conversation Context:\n")
                priorTurns.takeLast(3).forEach { turn ->
                    sb.append("User: ").append(turn.question).append("\n")
                    sb.append("Assistant: ").append(turn.resultText).append("\n")
                }
                sb.append("\n")
            }
            sb.append("Current Question: ").append(question).append("\n\n")
            sb.append("Available Grounded Facts:\n")
            facts.forEach { fact ->
                sb.append("- [").append(fact.factId).append("] ")
                    .append(fact.displayLabel).append(": ").append(fact.displayValue)
                if (fact.qualifier != null) sb.append(" (").append(fact.qualifier).append(")")
                sb.append("\n")
            }
            return sb.toString()
        }
    }
}
