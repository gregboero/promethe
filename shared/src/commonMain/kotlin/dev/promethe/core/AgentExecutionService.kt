package dev.promethe.core

import dev.promethe.api.ACTIVE_PROJECT_SETTING_KEY
import dev.promethe.api.AgentRunRecord
import dev.promethe.api.AgentRunStatus
import dev.promethe.db.PrometheDatabaseApi
import dev.promethe.db.ProjectRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock

enum class AgentExecutionOrigin {
    A2A,
    ACP,
    OPENAI_COMPAT,
    CHANNEL,
    WEBHOOK,
    SCHEDULER,
    GOAL,
    VOICE,
    INTERNAL,
}

data class AgentHistoryMessage(
    val role: String,
    val content: String,
)

data class AgentExecutionRequest(
    val sessionId: String,
    val text: String,
    val history: List<AgentHistoryMessage> = emptyList(),
    val profileId: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val origin: AgentExecutionOrigin = AgentExecutionOrigin.INTERNAL,
    val channelHint: String = "internal",
    val externalContext: String? = null,
    val projectId: String? = null,
    val runId: String? = null,
    val parentRunId: String? = null,
)

sealed interface AgentExecutionEvent {
    val runId: String

    data class Step(
        val index: Int,
        val trajectory: ConversationTrajectory,
        override val runId: String,
        val stepId: String,
    ) : AgentExecutionEvent

    data class Completed(
        val response: String,
        val steps: Int,
        override val runId: String,
    ) : AgentExecutionEvent

    data class Failed(
        val code: String,
        val message: String,
        val steps: Int,
        override val runId: String,
        val stepId: String? = null,
    ) : AgentExecutionEvent
}

class AgentExecutionException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * The single application service exposed to network protocols and background jobs.
 * The execution graph is the only component allowed to enter [AIAgent.executeLoop].
 */
class AgentExecutionService(
    agent: AIAgent,
    private val database: PrometheDatabaseApi,
    private val executionIdGenerator: ExecutionIdGenerator = DefaultExecutionIdGenerator,
    private val runLedger: RunLedger = PersistentRunLedger(database),
    private val executionGraph: ExecutionGraph = DurableExecutionGraph(agent.asLoopExecutor(), runLedger),
) : AgentExecutionPort {
    private val logger = Log.create("AgentExecutionService")

    override fun execute(request: AgentExecutionRequest): Flow<AgentExecutionEvent> {
        var stepCount = 0
        var lastStepId: String? = null
        var ledgerStarted = false
        var terminalRecorded = false
        val generatedRunId = executionIdGenerator.nextId("run")
        val effectiveRunId = request.runId ?: generatedRunId
        return channelFlow {
            val identityIsValid =
                isValidExecutionId(effectiveRunId) &&
                    (request.parentRunId == null || isValidExecutionId(request.parentRunId))
            val ledgerRunId = effectiveRunId.takeIf(::isValidExecutionId) ?: generatedRunId
            val createdAt = Clock.System.now().toEpochMilliseconds()
            try {
                ledgerStarted =
                    runLedger.begin(
                        AgentRunRecord(
                            runId = ledgerRunId,
                            parentRunId = request.parentRunId?.takeIf(::isValidExecutionId),
                            sessionId = request.sessionId,
                            origin = request.origin.name,
                            projectId = request.projectId,
                            status = AgentRunStatus.PENDING,
                            createdAt = createdAt,
                            updatedAt = createdAt,
                        ),
                    )
            } catch (error: Exception) {
                logger.error(error) { "Run ledger initialization failed for $ledgerRunId" }
                send(
                    AgentExecutionEvent.Failed(
                        code = "run_ledger_unavailable",
                        message = "The durable run ledger is unavailable",
                        steps = 0,
                        runId = ledgerRunId,
                    ),
                )
                return@channelFlow
            }
            if (!ledgerStarted) {
                send(
                    AgentExecutionEvent.Failed(
                        code = "duplicate_run_id",
                        message = "The run identifier already exists",
                        steps = 0,
                        runId = ledgerRunId,
                    ),
                )
                return@channelFlow
            }
            if (!identityIsValid) {
                terminalRecorded = runLedger.fail(ledgerRunId, 0, null, "invalid_run_identity", createdAt)
                send(
                    AgentExecutionEvent.Failed(
                        code = "invalid_run_identity",
                        message = "Run identifiers must contain only letters, digits, '.', '_', ':', or '-'",
                        steps = 0,
                        runId = ledgerRunId,
                    ),
                )
                return@channelFlow
            }
            val runIdentity = AgentRunIdentity(effectiveRunId, request.parentRunId)
            val input = request.text.trim()
            if (input.isBlank()) {
                terminalRecorded = runLedger.fail(runIdentity.runId, 0, null, "empty_input", createdAt)
                send(AgentExecutionEvent.Failed("empty_input", "Agent input must contain text", 0, runIdentity.runId))
                return@channelFlow
            }

            val resolved = resolveProfile(request)
            val project = resolveProject(request)
            persistHistory(request)

            var finalResponse: String? = null
            Tracing.span(
                name = "agent.run",
                attributes =
                    buildMap {
                        put("promethe.run.id", runIdentity.runId)
                        put("agent.origin", request.origin.name)
                        runIdentity.parentRunId?.let { put("promethe.run.parent_id", it) }
                    },
            ) {
                executionGraph
                    .execute(
                        ExecutionGraphRequest(
                            identity = runIdentity,
                            sessionId = request.sessionId,
                            userInput = input,
                            overrideProvider = resolved.provider,
                            overrideModel = resolved.model,
                            personaOverlay = resolved.persona,
                            personaSkillNames = resolved.skills,
                            origin = request.origin,
                            toolCallOrigin = request.origin.toToolCallOrigin(),
                            overrideReasoningEffort = resolved.reasoningEffort,
                            externalContext = request.externalContext,
                            projectId = project?.id,
                            projectContext = project?.toPromptContext(),
                            memoryNamespace = project?.memoryNamespace ?: "default",
                            workspaceRelativePath = project?.workspacePath,
                        ),
                    ).collect { transition ->
                        if (transition is ExecutionGraphTransition.StepPersisted) {
                            stepCount = transition.index
                            lastStepId = transition.stepId
                            send(
                                AgentExecutionEvent.Step(
                                    index = transition.index,
                                    trajectory = transition.trajectory,
                                    runId = transition.runId,
                                    stepId = transition.stepId,
                                ),
                            )
                            transition.trajectory.outputs["response"]
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                                ?.let { finalResponse = it }
                        }
                    }
            }

            val response = finalResponse
            if (response == null) {
                logger.warn {
                    "Agent execution ended without a final response: " +
                        "session=${request.sessionId}, origin=${request.origin}, steps=$stepCount"
                }
                terminalRecorded =
                    runLedger.fail(
                        runIdentity.runId,
                        stepCount,
                        lastStepId,
                        "missing_final_response",
                        Clock.System.now().toEpochMilliseconds(),
                    )
                send(
                    AgentExecutionEvent.Failed(
                        code = "missing_final_response",
                        message = "The agent completed without producing a final response",
                        steps = stepCount,
                        runId = runIdentity.runId,
                        stepId = stepCount.takeIf { it > 0 }?.let(runIdentity::step)?.stepId,
                    ),
                )
            } else {
                terminalRecorded =
                    runLedger.complete(
                        runIdentity.runId,
                        stepCount,
                        lastStepId,
                        Clock.System.now().toEpochMilliseconds(),
                    )
                if (!terminalRecorded) {
                    throw AgentExecutionException("run_ledger_write_failed", "Failed to complete agent run")
                }
                send(AgentExecutionEvent.Completed(response, stepCount, runIdentity.runId))
            }
        }.catch { e ->
            logger.error(e) { "Agent execution failed for session ${request.sessionId}" }
            if (ledgerStarted && !terminalRecorded) {
                terminalRecorded =
                    try {
                        runLedger.fail(
                            effectiveRunId.takeIf(::isValidExecutionId) ?: generatedRunId,
                            stepCount,
                            lastStepId,
                            (e as? AgentExecutionException)?.code ?: "agent_execution_failed",
                            Clock.System.now().toEpochMilliseconds(),
                        )
                    } catch (ledgerError: Exception) {
                        logger.error(ledgerError) { "Failed to persist terminal run failure" }
                        false
                    }
            }
            emit(
                AgentExecutionEvent.Failed(
                    code = (e as? AgentExecutionException)?.code ?: "agent_execution_failed",
                    message = e.message ?: "Agent execution failed",
                    steps = stepCount,
                    runId = effectiveRunId.takeIf(::isValidExecutionId) ?: generatedRunId,
                    stepId =
                    lastStepId,
                ),
            )
        }.onCompletion { cause ->
            if (cause is CancellationException && ledgerStarted && !terminalRecorded) {
                withContext(NonCancellable) {
                    terminalRecorded =
                        runLedger.cancel(
                            effectiveRunId.takeIf(::isValidExecutionId) ?: generatedRunId,
                            stepCount,
                            lastStepId,
                            Clock.System.now().toEpochMilliseconds(),
                        )
                }
            }
        }
    }

    override suspend fun executeToCompletion(request: AgentExecutionRequest): String {
        var response: String? = null
        var failure: AgentExecutionEvent.Failed? = null
        execute(request).collect { event ->
            when (event) {
                is AgentExecutionEvent.Completed -> response = event.response
                is AgentExecutionEvent.Failed -> failure = event
                is AgentExecutionEvent.Step -> Unit
            }
        }
        failure?.let { throw AgentExecutionException(it.code, it.message) }
        return response ?: throw AgentExecutionException(
            "missing_final_response",
            "The agent completed without producing a final response",
        )
    }

    private suspend fun persistHistory(request: AgentExecutionRequest) {
        if (request.history.isEmpty()) return
        val now = Clock.System.now().toEpochMilliseconds()
        database.insertSessionOrIgnore(request.sessionId, now, "{}")
        request.history
            .filter { it.content.isNotBlank() }
            .forEachIndexed { index, message ->
                database.insertMessage(
                    sessionId = request.sessionId,
                    role = message.role,
                    content = message.content,
                    timestamp = now + index,
                )
            }
    }

    private suspend fun resolveProject(request: AgentExecutionRequest): ProjectRow? {
        val existingSession = database.getSession(request.sessionId)
        val isNewSession = existingSession == null
        if (isNewSession) {
            database.insertSessionOrIgnore(
                request.sessionId,
                Clock.System.now().toEpochMilliseconds(),
                "{}",
            )
        }

        val projectId =
            request.projectId
                ?: existingSession?.projectId
                ?: (if (isNewSession) database.getSetting(ACTIVE_PROJECT_SETTING_KEY) else null)
                ?: return null
        val project = database.getProject(projectId)
        if (project == null || project.archived) {
            if (request.projectId != null) {
                throw AgentExecutionException("invalid_project", "Project '$projectId' does not exist or is archived")
            }
            if (database.getSetting(ACTIVE_PROJECT_SETTING_KEY) == projectId) {
                database.deleteSetting(ACTIVE_PROJECT_SETTING_KEY)
            }
            return null
        }
        if (existingSession?.projectId != project.id) {
            database.assignSessionToProject(request.sessionId, project.id)
        }
        return project
    }

    private suspend fun resolveProfile(request: AgentExecutionRequest): ResolvedExecution {
        var provider = request.provider
        var model = request.model
        var persona: String? = null
        var skills = emptyList<String>()
        var reasoningEffort: dev.promethe.api.ReasoningEffort? = null

        val profileId = request.profileId
        if (!profileId.isNullOrBlank()) {
            val profile = database.getAgentProfile(profileId)
            if (profile != null) {
                if (profile.provider.isNotBlank()) provider = profile.provider
                if (profile.model.isNotBlank()) model = profile.model
                persona = profile.systemPrompt.takeIf { it.isNotBlank() }
                skills = parseSkills(profile.skills)
                reasoningEffort = profile.reasoningEffort
            } else {
                logger.warn { "Agent profile '$profileId' was not found; using request defaults" }
            }
        }

        return ResolvedExecution(provider, model, persona, skills, reasoningEffort)
    }

    private fun parseSkills(value: String): List<String> =
        try {
            (PrometheJson.parseToJsonElement(value) as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

    private fun AgentExecutionOrigin.toToolCallOrigin(): ToolCallOrigin =
        when (this) {
            AgentExecutionOrigin.A2A -> ToolCallOrigin.A2A

            AgentExecutionOrigin.ACP -> ToolCallOrigin.ACP

            AgentExecutionOrigin.OPENAI_COMPAT -> ToolCallOrigin.OPENAI_COMPAT

            AgentExecutionOrigin.CHANNEL,
            AgentExecutionOrigin.WEBHOOK,
            -> ToolCallOrigin.CHANNEL

            AgentExecutionOrigin.SCHEDULER -> ToolCallOrigin.SCHEDULER

            AgentExecutionOrigin.GOAL -> ToolCallOrigin.AUTONOMY

            AgentExecutionOrigin.VOICE -> ToolCallOrigin.VOICE

            AgentExecutionOrigin.INTERNAL -> ToolCallOrigin.AGENT
        }

    private data class ResolvedExecution(
        val provider: String?,
        val model: String?,
        val persona: String?,
        val skills: List<String>,
        val reasoningEffort: dev.promethe.api.ReasoningEffort?,
    )

    private fun ProjectRow.toPromptContext(): String =
        buildString {
            appendLine("Active project: $name")
            appendLine("Project id: $id")
            appendLine("Workspace: $workspacePath")
            if (description.isNotBlank()) appendLine("Description: $description")
            if (instructions.isNotBlank()) {
                appendLine("Project instructions:")
                appendLine(instructions)
            }
            append("Relative file and process paths must use this project workspace.")
        }
}
