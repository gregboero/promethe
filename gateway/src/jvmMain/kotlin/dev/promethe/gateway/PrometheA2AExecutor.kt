package dev.promethe.gateway

import ai.koog.a2a.model.*
import ai.koog.a2a.server.A2AServer
import ai.koog.a2a.server.agent.AgentExecutor
import ai.koog.a2a.server.session.RequestContext
import ai.koog.a2a.server.session.SessionEventProcessor
import ai.koog.a2a.server.tasks.TaskStorage
import ai.koog.a2a.transport.server.jsonrpc.http.HttpJSONRPCServerTransport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import dev.promethe.api.ProviderRegistry
import dev.promethe.core.AgentExecutionEvent
import dev.promethe.core.AgentExecutionException
import dev.promethe.core.AgentExecutionOrigin
import dev.promethe.core.AgentExecutionRequest
import dev.promethe.core.AgentExecutionPort
import dev.promethe.core.SkillLoader
import dev.promethe.core.ToolRegistry
import dev.promethe.db.PrometheDatabaseApi
import io.ktor.server.routing.*
import dev.promethe.gateway.AgentEventBus.toAgentEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * TaskStorage that auto-creates tasks on first update.
 *
 * The default InMemoryTaskStorage throws TaskOperationException when
 * sendTaskEvent is called for a task that doesn't exist yet.
 * In the message/sendSubscribe flow, the SDK doesn't pre-create tasks,
 * so this wrapper creates them lazily on the first status update.
 */
class AutoCreateTaskStorage : TaskStorage {
    private val tasks = ConcurrentHashMap<String, Task>()
    private val tasksByContext = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()

    override suspend fun get(
        taskId: String,
        historyLength: Int?,
        includeArtifacts: Boolean,
    ): Task =
        tasks[taskId] ?: throw ai.koog.a2a.server.exceptions.TaskOperationException(
            "Task not found: $taskId",
        )

    override suspend fun getAll(
        taskIds: List<String>,
        historyLength: Int?,
        includeArtifacts: Boolean,
    ): List<Task> = taskIds.mapNotNull { tasks[it] }

    override suspend fun getByContext(
        contextId: String,
        historyLength: Int?,
        includeArtifacts: Boolean,
    ): List<Task> {
        val taskIds = tasksByContext[contextId] ?: return emptyList()
        return taskIds.mapNotNull { tasks[it] }
    }

    override suspend fun update(event: TaskEvent) {
        val taskId = event.taskId
        val contextId = event.contextId

        when (event) {
            is TaskStatusUpdateEvent -> {
                val existing = tasks[taskId]
                if (existing != null) {
                    // Update existing task
                    tasks[taskId] = existing.copy(status = event.status)
                } else {
                    // Auto-create task on first status update
                    val newTask = Task(
                        id = taskId,
                        contextId = contextId,
                        status = event.status,
                    )
                    tasks[taskId] = newTask
                    tasksByContext.getOrPut(contextId) { CopyOnWriteArrayList() }.add(taskId)
                    logger.debug { "Auto-created task $taskId in context $contextId" }
                }
            }

            is TaskArtifactUpdateEvent -> {
                val existing = tasks[taskId] ?: return
                val artifacts = existing.artifacts.orEmpty().toMutableList()
                artifacts.add(event.artifact)
                tasks[taskId] = existing.copy(artifacts = artifacts)
            }

            is Task -> {
                tasks[taskId] = event
                tasksByContext.getOrPut(contextId) { CopyOnWriteArrayList() }.add(taskId)
            }
        }
    }

    override suspend fun delete(taskId: String) {
        val task = tasks.remove(taskId) ?: return
        tasksByContext[task.contextId]?.remove(taskId)
    }

    override suspend fun deleteAll(taskIds: List<String>) {
        taskIds.forEach { delete(it) }
    }
}

/**
 * A2A Protocol integration for Promethe.
 *
 * The Agent Card is **dynamic** — it's rebuilt on each call to reflect:
 * - Currently registered tools (from ToolRegistry)
 * - Active LLM provider capabilities (from ProviderRegistry)
 * - GEPA-evolved skills over time
 *
 * This means another agent discovering Promethe always sees its current capabilities.
 *
 * NOTE: We bypass Koog's HttpJSONRPCServerTransport because it has an SSE
 * DuplicatePluginException bug with Ktor 3.x. Instead, we mount routes manually.
 */
object PrometheA2A {
    /**
     * Build a dynamic Agent Card from the runtime state.
     *
     * Skills are derived from:
     * 1. Core skills (chat, code, research) — always present
     * 2. Registered tools — each tool becomes a discoverable skill
     * 3. Provider info — exposed as capability metadata
     */
    suspend fun buildAgentCard(
        baseUrl: String = "http://localhost:8080",
        skillLoader: SkillLoader? = null,
        database: PrometheDatabaseApi? = null,
    ): AgentCard {
        // ── Tool-based skills (dynamic from ToolRegistry) ───────────
        val toolSkills =
            ToolRegistry.toolsSnapshot().map { tool ->
                AgentSkill(
                    id = "tool-${tool.name}",
                    name = tool.name.replaceFirstChar { it.uppercase() },
                    description = tool.descriptor.description.ifBlank { "Tool: ${tool.name}" },
                    tags = listOf("tool", tool.name),
                    inputModes = listOf("text/plain"),
                    outputModes = listOf("text/plain"),
                )
            }

        // ── Agent skills (dynamic from SkillLoader .md files) ───────
        val agentSkills =
            try {
                skillLoader?.let {
                    it.listSkills().map { skill ->
                        // Extract first line as description
                        val description = skill.content
                            .lineSequence()
                            .firstOrNull { line -> line.isNotBlank() && !line.startsWith("#") }
                            ?.take(200)
                            ?: "Skill: ${skill.name}"
                        AgentSkill(
                            id = "skill-${skill.name}",
                            name = skill.name.replaceFirstChar { c -> c.uppercase() },
                            description = description,
                            tags = listOf("skill", skill.name),
                            inputModes = listOf("text/plain"),
                            outputModes = listOf("text/plain"),
                        )
                    }
                } ?: emptyList()
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load skills for agent card" }
                emptyList()
            }

        // ── Sub-agent skills (dynamic from active sessions) ─────────
        val subAgentSkills =
            try {
                database?.let { db ->
                    db.getAllSessions()
                        .filter { it.id.startsWith("a2a-") }
                        .take(10)
                        .map { session ->
                            AgentSkill(
                                id = "agent-${session.id}",
                                name = "Sub-Agent ${session.id.removePrefix("a2a-")}",
                                description = "Active A2A sub-agent session",
                                tags = listOf("agent", "sub-agent"),
                                inputModes = listOf("text/plain"),
                                outputModes = listOf("text/plain"),
                            )
                        }
                } ?: emptyList()
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load sub-agents for agent card" }
                emptyList()
            }

        // ── Provider description for card metadata ──────────────────
        val activeProviders = ProviderRegistry.providers.map { it.name }
        val providerDescription =
            if (activeProviders.isNotEmpty()) {
                " Supported LLM providers: ${activeProviders.joinToString(", ")}."
            } else {
                ""
            }

        return AgentCard(
            name = "Promethe",
            description =
                "Autonomous AI agent with multi-provider LLM support, " +
                    "tool execution, and self-optimizing prompts (GEPA).$providerDescription",
            url = "$baseUrl/agents/a2a",
            version = "1.0.0",
            capabilities =
                AgentCapabilities(
                    streaming = true,
                    pushNotifications = false,
                    stateTransitionHistory = true,
                ),
            skills = toolSkills + agentSkills + subAgentSkills,
            defaultInputModes = listOf("text/plain"),
            defaultOutputModes = listOf("text/plain"),
            provider = AgentProvider(organization = "Promethe", url = baseUrl),
        )
    }

    /**
     * Create the AgentExecutor that bridges A2A tasks → AIAgent.
     * Supports both tasks/send (batch) and tasks/sendSubscribe (streaming).
     */
    fun createExecutor(
        executionService: AgentExecutionPort,
        database: PrometheDatabaseApi,
    ): AgentExecutor {
        return object : AgentExecutor {
            override suspend fun execute(
                context: RequestContext<MessageSendParams>,
                eventProcessor: SessionEventProcessor,
            ) {
                // Extract text from A2A message parts
                val inputText =
                    context.params.message.parts
                        .filterIsInstance<TextPart>()
                        .joinToString("\n") { it.text }

                if (inputText.isBlank()) return

                // ── Derive A2A contextId and internal DB sessionId ──
                // CRITICAL: The Koog SDK validates that ALL TaskEvents use the
                // same contextId as the one assigned by the server session.
                // We MUST use context.contextId for all A2A events.
                // sessionId is only for our internal DB storage (history, messages).
                val a2aContextId: String = context.contextId
                val messageContextId = context.params.message.contextId
                val sessionId = if (!messageContextId.isNullOrBlank()) {
                    messageContextId
                } else {
                    "a2a-${context.taskId}"
                }
                val now = System.currentTimeMillis()
                database.insertSessionOrIgnore(sessionId, now, "{}")
                logger.debug { "A2A IDs — taskId=${context.taskId}, a2aContextId=$a2aContextId, sessionId=$sessionId (context.contextId=${context.contextId}, message.contextId=${context.params.message.contextId})" }

                // Extract optional per-request model overrides from message metadata.
                // Set by the frontend when the user selects a non-default agent profile.
                val metadata = context.params.message.metadata
                logger.debug { "A2A message metadata: $metadata (type=${metadata?.let { it::class.simpleName }})" }
                val overrideProvider = metadata?.get("provider")
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                val overrideModel = metadata?.get("model")
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                if (overrideProvider != null || overrideModel != null) {
                    logger.info { "Request uses profile override: provider=$overrideProvider, model=$overrideModel" }
                } else {
                    logger.debug { "No provider/model override in A2A metadata" }
                }

                val profileId = metadata?.get("profileId")
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }

                // ── Initialize Task (same pattern as orchestrator reference) ──
                val task = Task(
                    id = context.taskId,
                    contextId = a2aContextId,
                    status = TaskStatus(
                        state = TaskState.Submitted,
                        message = context.params.message,
                    ),
                )
                eventProcessor.sendTaskEvent(task)

                eventProcessor.sendTaskEvent(
                    TaskStatusUpdateEvent(
                        taskId = context.taskId,
                        contextId = a2aContextId,
                        status = TaskStatus(
                            state = TaskState.Working,
                            message = Message(
                                messageId = "working-${context.taskId}",
                                role = Role.Agent,
                                parts = listOf(TextPart(text = "Processing…")),
                                contextId = a2aContextId,
                                taskId = context.taskId,
                            ),
                        ),
                        `final` = false,
                    ),
                )

                // Run the agent with streaming — emit steps as TaskStatusUpdateEvent
                var lastResponse = ""
                var stepCount = 0

                try {
                    val agentFlow = executionService.execute(
                        AgentExecutionRequest(
                            sessionId = sessionId,
                            text = inputText,
                            profileId = profileId,
                            provider = overrideProvider,
                            model = overrideModel,
                            origin = AgentExecutionOrigin.A2A,
                            channelHint = "a2a",
                        ),
                    )
                    var lastActionDesc: String? = null
                    var responseSent = false

                    agentFlow.collect { event ->
                        when (event) {
                            is AgentExecutionEvent.Completed -> {
                                lastResponse = event.response
                                stepCount = event.steps
                            }

                            is AgentExecutionEvent.Failed -> {
                                throw AgentExecutionException(event.code, event.message)
                            }

                            is AgentExecutionEvent.Step -> {
                                val trajectory = event.trajectory
                                stepCount = event.index

                                // ── Broadcast to Monitor WebSocket ──
                                AgentEventBus.emit(trajectory.toAgentEvent(agentId = "main"))

                                val thought = trajectory.thought
                                val actionDesc = trajectory.action?.let { "🔧 ${it.toolName}(${it.args})" }
                                val observation = trajectory.observation
                                val response = trajectory.outputs["response"]
                                val systemEvent = trajectory.outputs["system"]

                                // Stream each step as TaskStatusUpdateEvent(WORKING, message=...)
                                // Skip thoughts after the response has been sent (post-processing hooks)
                                if (thought != null && !responseSent) {
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
                                                    metadata = buildJsonObject {
                                                        put("type", "thought")
                                                        put("step", stepCount.toString())
                                                    },
                                                ),
                                            ),
                                            `final` = false,
                                        ),
                                    )
                                }
                                // Skip duplicate consecutive actions (same tool + same args)
                                if (actionDesc != null && actionDesc != lastActionDesc && !responseSent) {
                                    lastActionDesc = actionDesc
                                    val toolAction = trajectory.action
                                    eventProcessor.sendTaskEvent(
                                        TaskStatusUpdateEvent(
                                            taskId = context.taskId,
                                            contextId = a2aContextId,
                                            status = TaskStatus(
                                                state = TaskState.Working,
                                                message = Message(
                                                    messageId = "action-${context.taskId}-$stepCount",
                                                    role = Role.Agent,
                                                    parts = listOf(TextPart(text = actionDesc)),
                                                    contextId = a2aContextId,
                                                    taskId = context.taskId,
                                                    metadata = buildJsonObject {
                                                        put("type", "action")
                                                        put("step", stepCount.toString())
                                                        toolAction?.let {
                                                            put("tool", it.toolName)
                                                            put("args", it.args.toString())
                                                        }
                                                    },
                                                ),
                                            ),
                                            `final` = false,
                                        ),
                                    )
                                }
                                if (response != null) {
                                    responseSent = true
                                    // ── A2UI bridge: extract __a2ui_tree__ from text → metadata ──
                                    val a2uiTreeRegex = Regex("__a2ui_tree__=(.+)")
                                    val a2uiDataRegex = Regex("__a2ui_data__=(.+)")
                                    val a2uiTreeMatch = a2uiTreeRegex.find(response)
                                    val a2uiDataMatch = a2uiDataRegex.find(response)

                                    val cleanResponse = response
                                        .replace(a2uiTreeRegex, "")
                                        .replace(a2uiDataRegex, "")
                                        .replace(Regex("\\[A2UI] UI tree accepted for rendering\\.\\s*"), "")
                                        .trim()

                                    lastResponse = cleanResponse.ifBlank { response }
                                    eventProcessor.sendTaskEvent(
                                        TaskStatusUpdateEvent(
                                            taskId = context.taskId,
                                            contextId = a2aContextId,
                                            status = TaskStatus(
                                                state = TaskState.Working,
                                                message = Message(
                                                    messageId = "response-${context.taskId}-$stepCount",
                                                    role = Role.Agent,
                                                    parts = listOf(TextPart(text = if (cleanResponse.isNotBlank()) cleanResponse else response)),
                                                    contextId = a2aContextId,
                                                    taskId = context.taskId,
                                                    metadata = buildJsonObject {
                                                        put("type", "response")
                                                        put("step", stepCount.toString())
                                                        a2uiTreeMatch?.groupValues?.get(1)?.let { treeJson ->
                                                            try {
                                                                put("a2ui_tree", Json.parseToJsonElement(treeJson))
                                                            } catch (_: Exception) {
                                                                logger.warn { "Failed to parse A2UI tree JSON" }
                                                            }
                                                        }
                                                        a2uiDataMatch?.groupValues?.get(1)?.let { dataJson ->
                                                            try {
                                                                put("a2ui_data", Json.parseToJsonElement(dataJson))
                                                            } catch (_: Exception) {
                                                            }
                                                        }
                                                    },
                                                ),
                                            ),
                                            `final` = false,
                                        ),
                                    )
                                }
                                // System events are already filtered client-side; skip after response
                                if (systemEvent != null && !responseSent) {
                                    eventProcessor.sendTaskEvent(
                                        TaskStatusUpdateEvent(
                                            taskId = context.taskId,
                                            contextId = a2aContextId,
                                            status = TaskStatus(
                                                state = TaskState.Working,
                                                message = Message(
                                                    messageId = "system-${context.taskId}-$stepCount",
                                                    role = Role.Agent,
                                                    parts = listOf(TextPart(text = systemEvent)),
                                                    contextId = a2aContextId,
                                                    taskId = context.taskId,
                                                    metadata = buildJsonObject {
                                                        put("type", "system")
                                                        put("step", stepCount.toString())
                                                    },
                                                ),
                                            ),
                                            `final` = false,
                                        ),
                                    )
                                }
                                if (observation != null && !responseSent) {
                                    // ── TTS Audio interception ──────────────────────────────
                                    // When the text_to_speech tool returns audio data, emit
                                    // an additional event with type="audio_message" so the
                                    // client can auto-play the synthesized audio.
                                    if (trajectory.action?.toolName == "text_to_speech") {
                                        try {
                                            val obsJson = Json.parseToJsonElement(observation).jsonObject
                                            val audioData = obsJson["data"]?.jsonPrimitive?.contentOrNull
                                            val sampleRate = obsJson["sampleRate"]?.jsonPrimitive?.intOrNull ?: 24000
                                            if (audioData != null) {
                                                eventProcessor.sendTaskEvent(
                                                    TaskStatusUpdateEvent(
                                                        taskId = context.taskId,
                                                        contextId = a2aContextId,
                                                        status = TaskStatus(
                                                            state = TaskState.Working,
                                                            message = Message(
                                                                messageId = "audio-${context.taskId}-$stepCount",
                                                                role = Role.Agent,
                                                                parts = listOf(TextPart(text = "[audio]")),
                                                                contextId = a2aContextId,
                                                                taskId = context.taskId,
                                                                metadata = buildJsonObject {
                                                                    put("type", "audio_message")
                                                                    put("data", audioData)
                                                                    put("sampleRate", sampleRate)
                                                                },
                                                            ),
                                                        ),
                                                        `final` = false,
                                                    ),
                                                )
                                                logger.info { "TTS audio emitted via A2A SSE (${audioData.length} chars base64, ${sampleRate}Hz)" }
                                            }
                                        } catch (_: Exception) {
                                            // Not valid JSON or not TTS result — fall through to normal observation
                                        }
                                    }

                                    eventProcessor.sendTaskEvent(
                                        TaskStatusUpdateEvent(
                                            taskId = context.taskId,
                                            contextId = a2aContextId,
                                            status = TaskStatus(
                                                state = TaskState.Working,
                                                message = Message(
                                                    messageId = "observation-${context.taskId}-$stepCount",
                                                    role = Role.Agent,
                                                    parts = listOf(TextPart(text = observation)),
                                                    contextId = a2aContextId,
                                                    taskId = context.taskId,
                                                    metadata = buildJsonObject {
                                                        put("type", "observation")
                                                        put("step", stepCount.toString())
                                                    },
                                                ),
                                            ),
                                            `final` = false,
                                        ),
                                    )
                                }
                            }
                        }
                    }

                    if (lastResponse.isBlank()) {
                        throw AgentExecutionException(
                            "missing_final_response",
                            "The agent completed without producing a final response",
                        )
                    }

                    // ── Terminal: COMPLETED ──
                    // Don't set metadata.type="response" here — the response was already
                    // streamed during the agent flow. This is a lifecycle-only event.
                    eventProcessor.sendTaskEvent(
                        TaskStatusUpdateEvent(
                            taskId = context.taskId,
                            contextId = a2aContextId,
                            status = TaskStatus(
                                state = TaskState.Completed,
                                message = Message(
                                    messageId = "completed-${context.taskId}",
                                    role = Role.Agent,
                                    parts = listOf(TextPart(text = lastResponse)),
                                    contextId = a2aContextId,
                                    taskId = context.taskId,
                                ),
                            ),
                            `final` = true,
                        ),
                    )
                } catch (e: Exception) {
                    logger.error(e) { "Agent execution failed for task ${context.taskId}" }
                    eventProcessor.sendTaskEvent(
                        TaskStatusUpdateEvent(
                            taskId = context.taskId,
                            contextId = a2aContextId,
                            status = TaskStatus(
                                state = TaskState.Failed,
                                message = Message(
                                    messageId = "error-${context.taskId}",
                                    role = Role.Agent,
                                    parts = listOf(TextPart(text = "Error: ${e.message}")),
                                    contextId = a2aContextId,
                                    taskId = context.taskId,
                                ),
                            ),
                            `final` = true,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Create the A2AServer + transport and install routes into existing Ktor routing.
     *
     * Routes mounted:
     * - POST /a2a — JSON-RPC endpoint (message/send, tasks/send)
     * - GET  /.well-known/agent-card.json — Agent Card discovery
     */
    fun installRoutes(
        route: Route,
        executionService: AgentExecutionPort,
        database: PrometheDatabaseApi,
        baseUrl: String = "http://localhost:8080",
        skillLoader: SkillLoader? = null,
    ) {
        val agentCard = kotlinx.coroutines.runBlocking {
            buildAgentCard(baseUrl, skillLoader, database)
        }
        val executor = createExecutor(executionService, database)
        val taskStorage = AutoCreateTaskStorage()
        val a2aServer = A2AServer(
            agentExecutor = executor,
            agentCard = agentCard,
            taskStorage = taskStorage,
        )
        val transport = HttpJSONRPCServerTransport(a2aServer)

        // Mount A2A JSON-RPC routes
        transport.transportRoutes(route, "/a2a")

        logger.info {
            "A2A Agent Card published with ${agentCard.skills.size} skills " +
                "(${agentCard.skills.count { it.id.startsWith("tool-") }} tools, " +
                "${agentCard.skills.count { it.id.startsWith("skill-") }} skills, " +
                "${agentCard.skills.count { it.id.startsWith("agent-") }} sub-agents)"
        }
    }
}
