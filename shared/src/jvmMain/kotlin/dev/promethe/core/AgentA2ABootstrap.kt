package dev.promethe.core

import ai.koog.a2a.model.*
import ai.koog.a2a.server.agent.AgentExecutor
import ai.koog.a2a.server.session.RequestContext
import ai.koog.a2a.server.session.SessionEventProcessor
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * AgentA2ABootstrap — registers all agents in the A2A registry at startup.
 *
 * At boot time, each AIAgent becomes an A2A Server with its own AgentCard.
 * The AgentCard describes the agent's skills, capabilities, and identity.
 *
 * When AgentProfiles are loaded from the database, each profile becomes
 * a separate A2A agent in the registry, addressable by profile ID.
 */
class AgentA2ABootstrap(
    private val registry: AgentA2ARegistry,
    private val executionService: AgentExecutionPort,
    private val database: dev.promethe.db.PrometheDatabaseApi,
    private val toolRegistry: ToolRegistry,
) {
    /**
     * Register the main agent with default capabilities.
     * AgentCard/AgentSkill params match the gateway's existing working pattern.
     */
    suspend fun registerMainAgent() {
        val card =
            AgentCard(
                name = "Prométhé Main Agent",
                description = "Primary AI agent — orchestrates tasks, delegates to sub-agents.",
                url = "loopback://main",
                version = "1.0.0",
                skills =
                    listOf(
                        AgentSkill(
                            id = "chat",
                            name = "Chat",
                            description = "General-purpose conversation and task execution.",
                            tags = listOf("chat", "conversation"),
                            inputModes = listOf("text/plain"),
                            outputModes = listOf("text/plain"),
                        ),
                        AgentSkill(
                            id = "code",
                            name = "Code",
                            description = "Code generation, review, and refactoring.",
                            tags = listOf("code", "programming"),
                            inputModes = listOf("text/plain"),
                            outputModes = listOf("text/plain"),
                        ),
                        AgentSkill(
                            id = "research",
                            name = "Research",
                            description = "Web search, document analysis, information synthesis.",
                            tags = listOf("research", "search"),
                            inputModes = listOf("text/plain"),
                            outputModes = listOf("text/plain"),
                        ),
                        AgentSkill(
                            id = "delegate",
                            name = "Delegate",
                            description = "Task delegation to specialized sub-agents.",
                            tags = listOf("delegate", "multi-agent"),
                            inputModes = listOf("text/plain"),
                            outputModes = listOf("text/plain"),
                        ),
                    ),
                capabilities =
                    AgentCapabilities(
                        streaming = true,
                        pushNotifications = false,
                        stateTransitionHistory = true,
                    ),
                defaultInputModes = listOf("text/plain"),
                defaultOutputModes = listOf("text/plain"),
            )

        // Wrap the AIAgent as an AgentExecutor for A2A
        val executor = AIAgentA2AExecutor(executionService)
        registry.registerLocal("main", card, executor)
    }

    /**
     * Register all agent profiles from the database as separate A2A agents.
     * Each profile gets its own AgentCard with appropriate skills.
     */
    suspend fun registerProfileAgents() {
        val profiles = database.getAllAgentProfiles()
        for (profile in profiles) {
            registerSingleAgent(profile)
        }
    }

    /**
     * Hot-register a single agent profile into the A2A registry.
     * Called at runtime when an agent is dynamically created via create_agent tool.
     * No restart required — the agent is immediately addressable via delegate_task.
     */
    suspend fun registerSingleAgent(profile: dev.promethe.db.AgentProfileRow) {
        val toolNames = profile.tools.split(",").filter { it.isNotBlank() }
        val card =
            AgentCard(
                name = profile.name,
                description = profile.systemPrompt.take(200),
                url = "loopback://${profile.id}",
                version = "1.0.0",
                skills =
                    toolNames.map { toolName ->
                        AgentSkill(
                            id = "tool-${toolName.trim()}",
                            name = toolName.trim().replaceFirstChar { it.uppercase() },
                            description = "Tool: ${toolName.trim()}",
                            tags = listOf("tool", toolName.trim()),
                            inputModes = listOf("text/plain"),
                            outputModes = listOf("text/plain"),
                        )
                    },
                capabilities =
                    AgentCapabilities(
                        streaming = true,
                        pushNotifications = false,
                        stateTransitionHistory = false,
                    ),
                defaultInputModes = listOf("text/plain"),
                defaultOutputModes = listOf("text/plain"),
            )

        val executor =
            ProfileAgentA2AExecutor(
                profile = profile,
                executionService = executionService,
            )
        registry.registerLocal(profile.id, card, executor)
    }
}

/**
 * Wraps AIAgent as an A2A AgentExecutor.
 * Bridges the Koog agent loop with the A2A protocol.
 */
class AIAgentA2AExecutor(
    private val executionService: AgentExecutionPort,
) : AgentExecutor {
    override suspend fun execute(
        context: RequestContext<MessageSendParams>,
        eventProcessor: SessionEventProcessor,
    ) {
        // Extract text from the incoming A2A message
        val inputText =
            context.params.message.parts
                .filterIsInstance<TextPart>()
                .joinToString("\n") { it.text }

        // CRITICAL: use context.contextId for all A2A events (SDK validation)
        val a2aContextId = context.contextId
        val sessionId = context.taskId

        // Initialize task in SDK
        val task = Task(
            id = context.taskId,
            contextId = a2aContextId,
            status = TaskStatus(
                state = TaskState.Working,
                message = context.params.message,
            ),
        )
        eventProcessor.sendTaskEvent(task)

        // Run the agent with streaming
        var lastResponse = ""
        var stepCount = 0
        val flow = executionService.execute(
            AgentExecutionRequest(
                sessionId = sessionId,
                text = inputText,
                origin = AgentExecutionOrigin.A2A,
                channelHint = "agent-registry",
            ),
        )
        flow.collect { event ->
            if (event is AgentExecutionEvent.Completed) {
                lastResponse = event.response
                stepCount = event.steps
                return@collect
            }
            if (event is AgentExecutionEvent.Failed) {
                throw AgentExecutionException(event.code, event.message)
            }
            val step = event as AgentExecutionEvent.Step
            val trajectory = step.trajectory
            stepCount = step.index

            val thought = trajectory.outputs["thought"] ?: trajectory.thought
            val action = trajectory.outputs["action"] ?: trajectory.action?.toolName
            val response = trajectory.outputs["response"]

            // Stream intermediate steps as TaskStatusUpdateEvents
            if (thought != null) {
                eventProcessor.sendTaskEvent(
                    TaskStatusUpdateEvent(
                        taskId = context.taskId,
                        contextId = a2aContextId,
                        status = TaskStatus(
                            state = TaskState.Working,
                            message = Message(
                                messageId = "thought-${context.taskId}-$stepCount",
                                role = Role.Agent,
                                parts = listOf(TextPart(text = "💭 $thought")),
                                contextId = a2aContextId,
                                taskId = context.taskId,
                                metadata =
                                    buildJsonObject {
                                        put("type", "thought")
                                        put("step", stepCount.toString())
                                    },
                            ),
                        ),
                        `final` = false,
                    ),
                )
            }
            if (action != null) {
                eventProcessor.sendTaskEvent(
                    TaskStatusUpdateEvent(
                        taskId = context.taskId,
                        contextId = a2aContextId,
                        status = TaskStatus(
                            state = TaskState.Working,
                            message = Message(
                                messageId = "action-${context.taskId}-$stepCount",
                                role = Role.Agent,
                                parts = listOf(TextPart(text = "🔧 $action")),
                                contextId = a2aContextId,
                                taskId = context.taskId,
                                metadata =
                                    buildJsonObject {
                                        put("type", "action")
                                        put("step", stepCount.toString())
                                    },
                            ),
                        ),
                        `final` = false,
                    ),
                )
            }
            if (response != null) {
                lastResponse = response
            }
        }

        // Send final response
        if (lastResponse.isNotBlank()) {
            eventProcessor.sendTaskEvent(
                TaskStatusUpdateEvent(
                    taskId = context.taskId,
                    contextId = a2aContextId,
                    status = TaskStatus(
                        state = TaskState.Completed,
                        message = Message(
                            messageId = "resp-${context.taskId}",
                            role = Role.Agent,
                            parts = listOf(TextPart(text = lastResponse)),
                            contextId = a2aContextId,
                            taskId = context.taskId,
                            metadata =
                                buildJsonObject {
                                    put("type", "response")
                                    put("final", "true")
                                },
                        ),
                    ),
                    `final` = true,
                ),
            )
        }
    }
}

/**
 * Executor for profile-based agents.
 * Uses the profile's specific LLM configuration.
 */
class ProfileAgentA2AExecutor(
    private val profile: dev.promethe.db.AgentProfileRow,
    private val executionService: AgentExecutionPort,
) : AgentExecutor {
    override suspend fun execute(
        context: RequestContext<MessageSendParams>,
        eventProcessor: SessionEventProcessor,
    ) {
        val inputText =
            context.params.message.parts
                .filterIsInstance<TextPart>()
                .joinToString("\n") { it.text }

        // ── Session & History ──────────────────────────────────────────
        // CRITICAL: context.contextId is the SDK-assigned contextId.
        // ALL events sent via eventProcessor MUST use this contextId.
        val a2aContextId = context.contextId
        // Use context.taskId for DB persistence — this is the orchestrator's childSessionId
        // (e.g. "sub-xxx"). Do NOT use message.contextId ("ctx-xxx") as it's an A2A transport
        // artifact that would pollute the session list.
        val sessionId = context.taskId
        // Initialize task in SDK (required before sending TaskStatusUpdateEvents)
        val task = Task(
            id = context.taskId,
            contextId = a2aContextId,
            status = TaskStatus(
                state = TaskState.Working,
                message = context.params.message,
            ),
        )
        eventProcessor.sendTaskEvent(task)

        val response = executionService.executeToCompletion(
            AgentExecutionRequest(
                sessionId = sessionId,
                text = inputText,
                profileId = profile.id,
                origin = AgentExecutionOrigin.A2A,
                channelHint = "profile-agent",
            ),
        )

        // Send response via A2A — use sendTaskEvent with proper contextId
        eventProcessor.sendTaskEvent(
            TaskStatusUpdateEvent(
                taskId = context.taskId,
                contextId = a2aContextId,
                status = TaskStatus(
                    state = TaskState.Completed,
                    message = Message(
                        messageId = "resp-${context.taskId}",
                        role = Role.Agent,
                        parts = listOf(TextPart(text = response)),
                        contextId = a2aContextId,
                        taskId = context.taskId,
                        metadata =
                            buildJsonObject {
                                put("type", "response")
                                put("final", "true")
                            },
                    ),
                ),
                `final` = true,
            ),
        )
    }
}
