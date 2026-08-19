package dev.promethe.core

import dev.promethe.api.ReasoningEffort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock

enum class ExecutionGraphNode {
    AGENT_LOOP,
    PERSIST_STEP,
}

data class ExecutionGraphRequest(
    val identity: AgentRunIdentity,
    val startingStepIndex: Int = 0,
    val sessionId: String,
    val userInput: String,
    val overrideProvider: String? = null,
    val overrideModel: String? = null,
    val personaOverlay: String? = null,
    val personaSkillNames: List<String> = emptyList(),
    val origin: AgentExecutionOrigin = AgentExecutionOrigin.INTERNAL,
    val toolCallOrigin: ToolCallOrigin = ToolCallOrigin.AGENT,
    val overrideReasoningEffort: ReasoningEffort? = null,
    val externalContext: String? = null,
    val projectId: String? = null,
    val projectContext: String? = null,
    val memoryNamespace: String = "default",
    val workspaceRelativePath: String? = null,
)

sealed interface ExecutionGraphTransition {
    val runId: String
    val node: ExecutionGraphNode
    val stepId: String?

    data class NodeEntered(
        override val runId: String,
        override val node: ExecutionGraphNode,
        override val stepId: String? = null,
    ) : ExecutionGraphTransition

    data class NodeCompleted(
        override val runId: String,
        override val node: ExecutionGraphNode,
        override val stepId: String? = null,
    ) : ExecutionGraphTransition

    data class StepPersisted(
        val index: Int,
        val trajectory: ConversationTrajectory,
        override val runId: String,
        override val stepId: String,
    ) : ExecutionGraphTransition {
        override val node: ExecutionGraphNode = ExecutionGraphNode.PERSIST_STEP
    }

    data class NodeFailed(
        val code: String,
        override val runId: String,
        override val node: ExecutionGraphNode,
        override val stepId: String? = null,
    ) : ExecutionGraphTransition
}

fun interface AgentLoopExecutor {
    fun execute(request: ExecutionGraphRequest): Flow<ConversationTrajectory>
}

interface ExecutionGraph {
    fun execute(request: ExecutionGraphRequest): Flow<ExecutionGraphTransition>
}

/**
 * First durable graph around the existing bounded agent loop.
 *
 * The loop remains behaviorally unchanged, while step persistence becomes an
 * explicit graph node that must complete before a trajectory reaches clients.
 */
class DurableExecutionGraph(
    private val loopExecutor: AgentLoopExecutor,
    private val runLedger: RunLedger,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ExecutionGraph {
    override fun execute(request: ExecutionGraphRequest): Flow<ExecutionGraphTransition> =
        flow {
            emit(
                ExecutionGraphTransition.NodeEntered(
                    runId = request.identity.runId,
                    node = ExecutionGraphNode.AGENT_LOOP,
                ),
            )
            var stepIndex = request.startingStepIndex
            var loopFailure: Throwable? = null
            loopExecutor
                .execute(request)
                .catch { error ->
                    if (error is CancellationException) throw error
                    loopFailure = error
                }.collect { trajectory ->
                    stepIndex++
                    val step = request.identity.step(stepIndex)
                    emit(
                        ExecutionGraphTransition.NodeEntered(
                            runId = step.runId,
                            node = ExecutionGraphNode.PERSIST_STEP,
                            stepId = step.stepId,
                        ),
                    )
                    if (!runLedger.recordStep(step.runId, step.stepId, step.index, now())) {
                        emit(
                            ExecutionGraphTransition.NodeFailed(
                                code = "run_ledger_write_failed",
                                runId = step.runId,
                                node = ExecutionGraphNode.PERSIST_STEP,
                                stepId = step.stepId,
                            ),
                        )
                        throw AgentExecutionException("run_ledger_write_failed", "Failed to persist agent step")
                    }
                    emit(
                        ExecutionGraphTransition.StepPersisted(
                            index = step.index,
                            trajectory = trajectory,
                            runId = step.runId,
                            stepId = step.stepId,
                        ),
                    )
                    emit(
                        ExecutionGraphTransition.NodeCompleted(
                            runId = step.runId,
                            node = ExecutionGraphNode.PERSIST_STEP,
                            stepId = step.stepId,
                        ),
                    )
                }
            loopFailure?.let { error ->
                emit(
                    ExecutionGraphTransition.NodeFailed(
                        code = (error as? AgentExecutionException)?.code ?: "agent_execution_failed",
                        runId = request.identity.runId,
                        node = ExecutionGraphNode.AGENT_LOOP,
                    ),
                )
                throw error
            }
            emit(
                ExecutionGraphTransition.NodeCompleted(
                    runId = request.identity.runId,
                    node = ExecutionGraphNode.AGENT_LOOP,
                ),
            )
        }
}

internal fun AIAgent.asLoopExecutor(): AgentLoopExecutor =
    AgentLoopExecutor { request ->
        executeLoop(
            sessionId = request.sessionId,
            userInput = request.userInput,
            overrideProvider = request.overrideProvider,
            overrideModel = request.overrideModel,
            personaOverlay = request.personaOverlay,
            personaSkillNames = request.personaSkillNames,
            toolCallOrigin = request.toolCallOrigin,
            overrideReasoningEffort = request.overrideReasoningEffort,
            llmRequestContext =
                LlmRequestContext(
                    sessionId = request.sessionId,
                    origin = request.origin,
                    runId = request.identity.runId,
                    parentRunId = request.identity.parentRunId,
                ),
            externalContext = request.externalContext,
            projectId = request.projectId,
            projectContext = request.projectContext,
            memoryNamespace = request.memoryNamespace,
            workspaceRelativePath = request.workspaceRelativePath,
        )
    }
