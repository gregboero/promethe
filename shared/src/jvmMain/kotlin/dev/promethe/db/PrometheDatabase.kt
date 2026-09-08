package dev.promethe.db

import dev.promethe.api.AgentApprovalScope
import dev.promethe.api.AgentRunEventRecord
import dev.promethe.api.AgentRunEventType
import dev.promethe.api.AgentRunRecord
import dev.promethe.api.AgentRunStatus
import dev.promethe.api.PolicyDataTrust
import dev.promethe.api.ToolIntentRecord
import dev.promethe.api.ToolIntentStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Database access layer for Promethe — replaces SQLDelight-generated PrometheDatabase.
 *
 * All query methods are suspend functions using [newSuspendedTransaction] with [Dispatchers.IO].
 * Callers don't need to wrap calls in withContext(ioDispatcher).
 */
class PrometheDatabase(
    private val db: Database,
) : PrometheDatabaseApi {
    private val eventWriteMutex = Mutex()
    private val securityAuditWriteMutex = Mutex()

    /**
     * Initialize schema: create tables + FTS5 index + sync triggers.
     * Called once at startup by [DatabaseFactory].
     */
    fun initialize() {
        transaction(db) {
            @Suppress("DEPRECATION")
            SchemaUtils.createMissingTablesAndColumns(
                AgentRuns,
                ToolIntents,
                AgentRunEvents,
                ResourceGovernors,
                ResourceGovernorBindings,
                McpTasks,
                Projects,
                Sessions,
                Messages,
                Feedbacks,
                AgentProfiles,
                Checkpoints,
                UserFacts,
                ScheduledTasks,
                WebhookChannels,
                Settings,
                ApprovalGrants,
                LlmUsageLogs,
                RemoteOwners,
                AuthSessions,
                OAuthAuthorizations,
                OAuthConnections,
                McpServerConfigs,
                SecurityAuditLogs,
            )

            // FTS5 virtual table (raw SQL — Exposed doesn't support FTS natively)
            exec(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS message_search_index 
                USING fts5(content, content='messages', content_rowid='id')
                """.trimIndent(),
            )
            exec(
                """
                CREATE TRIGGER IF NOT EXISTS after_message_insert 
                AFTER INSERT ON messages BEGIN
                    INSERT INTO message_search_index(rowid, content) VALUES (new.id, new.content);
                END
                """.trimIndent(),
            )
            exec(
                """
                CREATE TRIGGER IF NOT EXISTS after_message_delete 
                AFTER DELETE ON messages BEGIN
                    DELETE FROM message_search_index WHERE rowid = old.id;
                END
                """.trimIndent(),
            )
            exec(
                """
                CREATE TRIGGER IF NOT EXISTS after_message_update 
                AFTER UPDATE ON messages BEGIN
                    DELETE FROM message_search_index WHERE rowid = old.id;
                    INSERT INTO message_search_index(rowid, content) VALUES (new.id, new.content);
                END
                """.trimIndent(),
            )

            // Seed default agent profile if table is empty
            val count = AgentProfiles.selectAll().count()
            if (count == 0L) {
                val now = System.currentTimeMillis()
                AgentProfiles.insert {
                    it[id] = "main"
                    it[name] = "Main Agent"
                    it[provider] = "openai"
                    it[model] = "gpt-4o-mini"
                    it[systemPrompt] = ""
                    it[tools] = "[]"
                    it[skills] = "[]"
                    it[maxIterations] = 10
                    it[temperature] = 0.2
                    it[isSystem] = true
                    it[ephemeral] = false
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                // Seed voice agent profile — specialized for voice interaction
                AgentProfiles.insert {
                    it[id] = "voice-agent"
                    it[name] = "Voice Assistant"
                    it[provider] = "google"
                    it[model] = "gemini-3.1-flash-live-preview"
                    it[systemPrompt] = """Tu es l'assistant vocal de Prométhé, un système d'agents IA autonomes.
                        |Tu parles de manière naturelle, concise et directe.
                        |
                        |Tu as accès à des outils simples que tu peux utiliser directement :
                        |lecture de fichiers, recherche en mémoire, recherche web, etc.
                        |
                        |Pour les tâches complexes qui nécessitent du planning, du code,
                        |du refactoring, de l'orchestration multi-agents ou des modifications
                        |de fichiers, utilise l'outil `delegate_task` pour déléguer
                        |au système Prométhé complet.
                        |
                        |Quand tu reçois le résultat de delegate_task, résume-le vocalement
                        |de manière concise et naturelle. Ne lis pas le texte brut,
                        |reformule pour l'oral.
                    """.trimMargin()
                    it[tools] = """["read_file","search_memory","web_search","delegate_task","get_subtask_result","list_files"]"""
                    it[skills] = "[]"
                    it[maxIterations] = 5
                    it[temperature] = 0.4
                    it[isSystem] = true
                    it[ephemeral] = false
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
        }
    }

    // ===== AGENT RUNS =====

    override suspend fun insertAgentRun(
        run: AgentRunRecord,
        event: AgentRunEventRecord?,
    ): Boolean =
        eventDbQuery {
            val inserted = AgentRuns
                .insertIgnore {
                    it[runId] = run.runId
                    it[parentRunId] = run.parentRunId
                    it[sessionId] = run.sessionId
                    it[origin] = run.origin
                    it[projectId] = run.projectId
                    it[requestFingerprint] = run.requestFingerprint
                    it[status] = run.status.name
                    it[stepCount] = run.stepCount
                    it[lastStepId] = run.lastStepId
                    it[errorCode] = run.errorCode
                    it[createdAt] = run.createdAt
                    it[startedAt] = run.startedAt
                    it[finishedAt] = run.finishedAt
                    it[updatedAt] = run.updatedAt
                }.insertedCount > 0
            if (inserted && event != null) {
                check(appendAgentRunEventInTransaction(event)) { "Failed to append agent run start event" }
            }
            inserted
        }

    override suspend fun transitionAgentRun(
        runId: String,
        expectedStatuses: Set<AgentRunStatus>,
        status: AgentRunStatus,
        stepCount: Int,
        lastStepId: String?,
        errorCode: String?,
        startedAt: Long?,
        finishedAt: Long?,
        updatedAt: Long,
        event: AgentRunEventRecord?,
    ): Boolean =
        eventDbQuery {
            if (expectedStatuses.isEmpty()) return@eventDbQuery false
            val updated = AgentRuns.update(
                where = {
                    (AgentRuns.runId eq runId) and
                        (AgentRuns.status inList expectedStatuses.map(AgentRunStatus::name))
                },
            ) {
                it[AgentRuns.status] = status.name
                it[AgentRuns.stepCount] = stepCount
                it[AgentRuns.lastStepId] = lastStepId
                it[AgentRuns.errorCode] = errorCode
                if (startedAt != null) it[AgentRuns.startedAt] = startedAt
                it[AgentRuns.finishedAt] = finishedAt
                it[AgentRuns.updatedAt] = updatedAt
            } > 0
            if (updated && event != null) {
                check(appendAgentRunEventInTransaction(event)) { "Failed to append agent run transition event" }
            }
            updated
        }

    override suspend fun updateAgentRunProgress(
        runId: String,
        stepCount: Int,
        lastStepId: String,
        updatedAt: Long,
        event: AgentRunEventRecord?,
    ): Boolean =
        eventDbQuery {
            val updated = AgentRuns.update(
                where = {
                    (AgentRuns.runId eq runId) and
                        (AgentRuns.status eq AgentRunStatus.RUNNING.name)
                },
            ) {
                it[AgentRuns.stepCount] = stepCount
                it[AgentRuns.lastStepId] = lastStepId
                it[AgentRuns.updatedAt] = updatedAt
            } > 0
            if (updated && event != null) {
                check(appendAgentRunEventInTransaction(event)) { "Failed to append agent run step event" }
            }
            updated
        }

    override suspend fun getAgentRun(runId: String): AgentRunRecord? =
        dbQuery {
            AgentRuns
                .selectAll()
                .where { AgentRuns.runId eq runId }
                .singleOrNull()
                ?.toAgentRunRecord()
        }

    override suspend fun getAgentRunsByStatus(statuses: Set<AgentRunStatus>): List<AgentRunRecord> =
        dbQuery {
            if (statuses.isEmpty()) return@dbQuery emptyList()
            AgentRuns
                .selectAll()
                .where { AgentRuns.status inList statuses.map(AgentRunStatus::name) }
                .orderBy(AgentRuns.updatedAt, SortOrder.ASC)
                .map { it.toAgentRunRecord() }
        }

    override suspend fun appendAgentRunEvent(event: AgentRunEventRecord): Boolean = eventDbQuery { appendAgentRunEventInTransaction(event) }

    override suspend fun getAgentRunEvents(runId: String): List<AgentRunEventRecord> =
        dbQuery {
            AgentRunEvents
                .selectAll()
                .where { AgentRunEvents.runId eq runId }
                .orderBy(AgentRunEvents.sequence, SortOrder.ASC)
                .map { it.toAgentRunEventRecord() }
        }

    // ===== RESOURCE GOVERNORS =====

    override suspend fun insertResourceGovernorState(state: ResourceGovernorStateRow): Boolean =
        dbQuery {
            ResourceGovernors
                .insertIgnore {
                    it[rootRunId] = state.rootRunId
                    it[maxTokens] = state.maxTokens
                    it[maxCostDollars] = state.maxCostDollars
                    it[maxLlmCalls] = state.maxLlmCalls
                    it[maxToolStarts] = state.maxToolStarts
                    it[maxSubAgents] = state.maxSubAgents
                    it[maxDurationMs] = state.maxDurationMs
                    it[startedAt] = state.startedAt
                    it[tokensUsed] = state.tokensUsed
                    it[costDollars] = state.costDollars
                    it[llmCallsStarted] = state.llmCallsStarted
                    it[toolsStarted] = state.toolsStarted
                    it[subAgentsStarted] = state.subAgentsStarted
                    it[version] = state.version
                    it[updatedAt] = state.updatedAt
                }.insertedCount > 0
        }

    override suspend fun updateResourceGovernorState(
        expectedVersion: Long,
        state: ResourceGovernorStateRow,
    ): Boolean =
        dbQuery {
            ResourceGovernors.update(
                where = {
                    (ResourceGovernors.rootRunId eq state.rootRunId) and
                        (ResourceGovernors.version eq expectedVersion)
                },
            ) {
                it[tokensUsed] = state.tokensUsed
                it[costDollars] = state.costDollars
                it[llmCallsStarted] = state.llmCallsStarted
                it[toolsStarted] = state.toolsStarted
                it[subAgentsStarted] = state.subAgentsStarted
                it[version] = state.version
                it[updatedAt] = state.updatedAt
            } > 0
        }

    override suspend fun getResourceGovernorState(rootRunId: String): ResourceGovernorStateRow? =
        dbQuery {
            ResourceGovernors
                .selectAll()
                .where { ResourceGovernors.rootRunId eq rootRunId }
                .singleOrNull()
                ?.toResourceGovernorStateRow()
        }

    override suspend fun insertResourceGovernorBinding(binding: ResourceGovernorBindingRow): Boolean =
        dbQuery {
            ResourceGovernorBindings
                .insertIgnore {
                    it[sessionId] = binding.sessionId
                    it[rootRunId] = binding.rootRunId
                    it[runId] = binding.runId
                    it[createdAt] = binding.createdAt
                    it[updatedAt] = binding.updatedAt
                }.insertedCount > 0
        }

    override suspend fun claimResourceGovernorBinding(
        sessionId: String,
        expectedRunId: String?,
        runId: String,
        updatedAt: Long,
    ): Boolean =
        dbQuery {
            ResourceGovernorBindings.update(
                where = {
                    (ResourceGovernorBindings.sessionId eq sessionId) and
                        if (expectedRunId == null) {
                            ResourceGovernorBindings.runId.isNull()
                        } else {
                            ResourceGovernorBindings.runId eq expectedRunId
                        }
                },
            ) {
                it[ResourceGovernorBindings.runId] = runId
                it[ResourceGovernorBindings.updatedAt] = updatedAt
            } > 0
        }

    override suspend fun getResourceGovernorBinding(sessionId: String): ResourceGovernorBindingRow? =
        dbQuery {
            ResourceGovernorBindings
                .selectAll()
                .where { ResourceGovernorBindings.sessionId eq sessionId }
                .singleOrNull()
                ?.toResourceGovernorBindingRow()
        }

    override suspend fun getResourceGovernorBindingForRun(runId: String): ResourceGovernorBindingRow? =
        dbQuery {
            ResourceGovernorBindings
                .selectAll()
                .where { ResourceGovernorBindings.runId eq runId }
                .singleOrNull()
                ?.toResourceGovernorBindingRow()
        }

    override suspend fun deleteResourceGovernorBinding(
        sessionId: String,
        runId: String?,
    ): Boolean =
        dbQuery {
            ResourceGovernorBindings.deleteWhere {
                (ResourceGovernorBindings.sessionId eq sessionId) and
                    if (runId == null) {
                        ResourceGovernorBindings.runId.isNull()
                    } else {
                        ResourceGovernorBindings.runId eq runId
                    }
            } > 0
        }

    // ===== MCP TASKS =====

    override suspend fun insertMcpTask(task: McpTaskRecord): Boolean =
        dbQuery {
            McpTasks
                .insertIgnore {
                    it[taskId] = task.taskId
                    it[ownerSessionId] = task.ownerSessionId
                    it[method] = task.method
                    it[resourceName] = task.resourceName
                    it[runId] = task.runId
                    it[status] = task.status.name
                    it[statusMessage] = task.statusMessage
                    it[resultJson] = task.resultJson
                    it[errorJson] = task.errorJson
                    it[inputRequestsJson] = task.inputRequestsJson
                    it[createdAt] = task.createdAt
                    it[lastUpdatedAt] = task.lastUpdatedAt
                    it[ttlMs] = task.ttlMs
                    it[pollIntervalMs] = task.pollIntervalMs
                }.insertedCount > 0
        }

    override suspend fun updateMcpTask(
        taskId: String,
        ownerSessionId: String,
        expectedStatuses: Set<McpTaskStatus>,
        status: McpTaskStatus,
        statusMessage: String?,
        resultJson: String?,
        errorJson: String?,
        inputRequestsJson: String?,
        lastUpdatedAt: Long,
    ): Boolean =
        dbQuery {
            if (expectedStatuses.isEmpty()) return@dbQuery false
            McpTasks.update(
                where = {
                    (McpTasks.taskId eq taskId) and
                        (McpTasks.ownerSessionId eq ownerSessionId) and
                        (McpTasks.status inList expectedStatuses.map(McpTaskStatus::name))
                },
            ) {
                it[McpTasks.status] = status.name
                it[McpTasks.statusMessage] = statusMessage
                it[McpTasks.resultJson] = resultJson
                it[McpTasks.errorJson] = errorJson
                it[McpTasks.inputRequestsJson] = inputRequestsJson
                it[McpTasks.lastUpdatedAt] = lastUpdatedAt
            } > 0
        }

    override suspend fun getMcpTask(
        taskId: String,
        ownerSessionId: String,
    ): McpTaskRecord? =
        dbQuery {
            McpTasks
                .selectAll()
                .where { (McpTasks.taskId eq taskId) and (McpTasks.ownerSessionId eq ownerSessionId) }
                .singleOrNull()
                ?.toMcpTaskRecord()
        }

    override suspend fun getMcpTasksByStatus(statuses: Set<McpTaskStatus>): List<McpTaskRecord> =
        dbQuery {
            if (statuses.isEmpty()) return@dbQuery emptyList()
            McpTasks
                .selectAll()
                .where { McpTasks.status inList statuses.map(McpTaskStatus::name) }
                .orderBy(McpTasks.lastUpdatedAt, SortOrder.ASC)
                .map { it.toMcpTaskRecord() }
        }

    // ===== TOOL INTENTS =====

    override suspend fun insertToolIntent(
        intent: ToolIntentRecord,
        event: AgentRunEventRecord?,
    ): Boolean =
        eventDbQuery {
            val inserted = ToolIntents
                .insertIgnore {
                    it[intentId] = intent.intentId
                    it[idempotencyKeyHash] = intent.idempotencyKeyHash
                    it[invocationHash] = intent.invocationHash
                    it[runId] = intent.runId
                    it[stepId] = intent.stepId
                    it[sessionId] = intent.sessionId
                    it[toolName] = intent.toolName
                    it[risk] = intent.risk.name
                    it[status] = intent.status.name
                    it[resultHash] = intent.resultHash
                    it[artifactHash] = intent.artifactHash
                    it[errorCode] = intent.errorCode
                    it[createdAt] = intent.createdAt
                    it[startedAt] = intent.startedAt
                    it[finishedAt] = intent.finishedAt
                    it[updatedAt] = intent.updatedAt
                }.insertedCount > 0
            if (inserted && event != null) {
                check(appendAgentRunEventInTransaction(event)) { "Failed to append tool intent event" }
            }
            inserted
        }

    override suspend fun transitionToolIntent(
        intentId: String,
        expectedStatuses: Set<ToolIntentStatus>,
        status: ToolIntentStatus,
        resultHash: String?,
        artifactHash: String?,
        errorCode: String?,
        startedAt: Long?,
        finishedAt: Long?,
        updatedAt: Long,
        event: AgentRunEventRecord?,
    ): Boolean =
        eventDbQuery {
            if (expectedStatuses.isEmpty()) return@eventDbQuery false
            val updated = ToolIntents.update(
                where = {
                    (ToolIntents.intentId eq intentId) and
                        (ToolIntents.status inList expectedStatuses.map(ToolIntentStatus::name))
                },
            ) {
                it[ToolIntents.status] = status.name
                it[ToolIntents.resultHash] = resultHash
                it[ToolIntents.artifactHash] = artifactHash
                it[ToolIntents.errorCode] = errorCode
                if (startedAt != null) it[ToolIntents.startedAt] = startedAt
                it[ToolIntents.finishedAt] = finishedAt
                it[ToolIntents.updatedAt] = updatedAt
            } > 0
            if (updated && event != null) {
                check(appendAgentRunEventInTransaction(event)) { "Failed to append tool intent transition event" }
            }
            updated
        }

    override suspend fun getToolIntentByIdempotencyKeyHash(idempotencyKeyHash: String): ToolIntentRecord? =
        dbQuery {
            ToolIntents
                .selectAll()
                .where { ToolIntents.idempotencyKeyHash eq idempotencyKeyHash }
                .singleOrNull()
                ?.toToolIntentRecord()
        }

    override suspend fun getToolIntent(intentId: String): ToolIntentRecord? =
        dbQuery {
            ToolIntents
                .selectAll()
                .where { ToolIntents.intentId eq intentId }
                .singleOrNull()
                ?.toToolIntentRecord()
        }

    override suspend fun resetToolIntentForRetry(
        intentId: String,
        expectedStatuses: Set<ToolIntentStatus>,
        updatedAt: Long,
        event: AgentRunEventRecord?,
    ): Boolean =
        eventDbQuery {
            if (expectedStatuses.isEmpty()) return@eventDbQuery false
            val updated = ToolIntents.update(
                where = {
                    (ToolIntents.intentId eq intentId) and
                        (ToolIntents.status inList expectedStatuses.map(ToolIntentStatus::name))
                },
            ) {
                it[status] = ToolIntentStatus.PREPARED.name
                it[resultHash] = null
                it[artifactHash] = null
                it[errorCode] = null
                it[startedAt] = null
                it[finishedAt] = null
                it[ToolIntents.updatedAt] = updatedAt
            } > 0
            if (updated && event != null) {
                check(appendAgentRunEventInTransaction(event)) { "Failed to append tool intent retry event" }
            }
            updated
        }

    override suspend fun getToolIntentsByStatus(statuses: Set<ToolIntentStatus>): List<ToolIntentRecord> =
        dbQuery {
            if (statuses.isEmpty()) return@dbQuery emptyList()
            ToolIntents
                .selectAll()
                .where { ToolIntents.status inList statuses.map(ToolIntentStatus::name) }
                .orderBy(ToolIntents.updatedAt, SortOrder.ASC)
                .map { it.toToolIntentRecord() }
        }

    // ===== PROJECTS =====

    override suspend fun insertProject(project: ProjectRow) {
        dbQuery {
            Projects.insert {
                it[id] = project.id
                it[name] = project.name
                it[description] = project.description
                it[instructions] = project.instructions
                it[workspacePath] = project.workspacePath
                it[memoryNamespace] = project.memoryNamespace
                it[archived] = project.archived
                it[createdAt] = project.createdAt
                it[updatedAt] = project.updatedAt
            }
        }
    }

    override suspend fun updateProject(project: ProjectRow) {
        dbQuery {
            Projects.update({ Projects.id eq project.id }) {
                it[name] = project.name
                it[description] = project.description
                it[instructions] = project.instructions
                it[archived] = project.archived
                it[updatedAt] = project.updatedAt
            }
        }
    }

    override suspend fun getProject(id: String): ProjectRow? =
        dbQuery {
            Projects.selectAll().where { Projects.id eq id }.singleOrNull()?.toProjectRow()
        }

    override suspend fun getAllProjects(): List<ProjectRow> =
        dbQuery {
            Projects.selectAll().orderBy(Projects.updatedAt, SortOrder.DESC).map { it.toProjectRow() }
        }

    override suspend fun getProjectSessionCounts(): Map<String, Int> =
        dbQuery {
            Sessions
                .selectAll()
                .mapNotNull { row -> row[Sessions.projectId] }
                .groupingBy { it }
                .eachCount()
        }

    // ===== SESSIONS =====

    override suspend fun insertSession(
        id: String,
        createdAt: Long,
        metadata: String?,
    ) {
        dbQuery {
            Sessions.insert {
                it[Sessions.id] = id
                it[Sessions.createdAt] = createdAt
                it[Sessions.metadata] = metadata
            }
        }
    }

    override suspend fun insertSessionOrIgnore(
        id: String,
        createdAt: Long,
        metadata: String?,
    ) {
        dbQuery {
            Sessions.insertIgnore {
                it[Sessions.id] = id
                it[Sessions.createdAt] = createdAt
                it[Sessions.metadata] = metadata
            }
        }
    }

    override suspend fun getAllSessions(): List<SessionRow> =
        dbQuery {
            Sessions
                .selectAll()
                .orderBy(Sessions.createdAt, SortOrder.DESC)
                .map { it.toSessionRow() }
        }

    override suspend fun getSession(id: String): SessionRow? =
        dbQuery {
            Sessions.selectAll().where { Sessions.id eq id }.singleOrNull()?.toSessionRow()
        }

    override suspend fun assignSessionToProject(
        sessionId: String,
        projectId: String?,
    ) {
        dbQuery {
            Sessions.update({ Sessions.id eq sessionId }) {
                it[Sessions.projectId] = projectId
            }
        }
    }

    override suspend fun deleteSession(id: String) {
        dbQuery {
            Checkpoints.deleteWhere { Checkpoints.sessionId eq id }
            Feedbacks.deleteWhere { Feedbacks.sessionId eq id }
            Messages.deleteWhere { Messages.sessionId eq id }
            Sessions.deleteWhere { Sessions.id eq id }
        }
    }

    override suspend fun getSessionMessageCounts(): Map<String, Int> =
        dbQuery {
            val countCol = Messages.id.count()
            Messages
                .select(Messages.sessionId, countCol)
                .groupBy(Messages.sessionId)
                .associate { it[Messages.sessionId] to it[countCol].toInt() }
        }

    override suspend fun updateSessionTitle(
        id: String,
        title: String,
    ) {
        dbQuery {
            Sessions.update({ Sessions.id eq id }) {
                it[Sessions.title] = title
            }
        }
    }

    override suspend fun updateSessionMetadata(
        id: String,
        metadata: String,
    ) {
        dbQuery {
            Sessions.update({ Sessions.id eq id }) {
                it[Sessions.metadata] = metadata
            }
        }
    }

    override suspend fun getRecentSessions(
        userId: String,
        limit: Int,
    ): List<SessionSummary> {
        val counts = getSessionMessageCounts()
        return dbQuery {
            Sessions
                .selectAll()
                .orderBy(Sessions.createdAt, SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    val sid = row[Sessions.id]
                    SessionSummary(
                        sessionId = sid,
                        messageCount = counts[sid] ?: 0,
                        lastActivity =
                            java.time.Instant
                                .ofEpochMilli(row[Sessions.createdAt])
                                .toString(),
                    )
                }
        }
    }

    // ===== MESSAGES =====

    override suspend fun insertMessage(
        sessionId: String,
        role: String,
        content: String,
        timestamp: Long,
        dataTrust: PolicyDataTrust,
        sourceRunId: String?,
    ): Int =
        dbQuery {
            Messages.insert {
                it[Messages.sessionId] = sessionId
                it[Messages.role] = role
                it[Messages.content] = content
                it[Messages.timestamp] = timestamp
                it[Messages.dataTrust] = dataTrust.name
                it[Messages.sourceRunId] = sourceRunId
            } get Messages.id
        }

    override suspend fun getMessagesForSession(sessionId: String): List<MessageRow> =
        dbQuery {
            Messages
                .selectAll()
                .where { Messages.sessionId eq sessionId }
                .orderBy(Messages.timestamp, SortOrder.ASC)
                .map { it.toMessageRow() }
        }

    override suspend fun searchMessages(query: String): List<MessageRow> =
        dbQuery {
            val sanitized = query.replace("'", "''")

            // Use a custom Op to integrate FTS5 MATCH into Exposed DSL
            val ftsMatch =
                object : Op<Boolean>() {
                    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
                        queryBuilder.append(
                            "\"messages\".\"id\" IN (SELECT rowid FROM message_search_index WHERE message_search_index MATCH '$sanitized')",
                        )
                    }
                }

            Messages
                .selectAll()
                .where { ftsMatch }
                .orderBy(Messages.timestamp, SortOrder.DESC)
                .map { it.toMessageRow() }
        }

    // ===== FEEDBACKS =====

    override suspend fun insertFeedback(
        sessionId: String,
        score: Double,
        comment: String?,
        timestamp: Long,
    ) {
        dbQuery {
            Feedbacks.insert {
                it[Feedbacks.sessionId] = sessionId
                it[Feedbacks.score] = score
                it[Feedbacks.comment] = comment
                it[Feedbacks.timestamp] = timestamp
            }
        }
    }

    override suspend fun getFeedbackForSession(sessionId: String): List<FeedbackRow> =
        dbQuery {
            Feedbacks
                .selectAll()
                .where { Feedbacks.sessionId eq sessionId }
                .orderBy(Feedbacks.timestamp, SortOrder.DESC)
                .map { it.toFeedbackRow() }
        }

    override suspend fun getAverageFeedback(): FeedbackStats =
        dbQuery {
            val avg = Feedbacks.score.avg()
            val count = Feedbacks.id.count()
            Feedbacks.select(avg, count).firstOrNull()?.let {
                FeedbackStats(
                    avgScore = it[avg]?.toDouble() ?: 0.0,
                    feedbackCount = it[count],
                )
            } ?: FeedbackStats(0.0, 0)
        }

    override suspend fun getRecentPositiveSessions(limit: Int): List<String> =
        dbQuery {
            Feedbacks
                .select(Feedbacks.sessionId)
                .where { Feedbacks.score greaterEq 0.7 }
                .orderBy(Feedbacks.timestamp, SortOrder.DESC)
                .limit(limit)
                .withDistinct()
                .map { it[Feedbacks.sessionId] }
        }

    // ===== AGENT PROFILES =====

    override suspend fun insertAgentProfile(profile: AgentProfileRow) {
        dbQuery {
            AgentProfiles.insert {
                it[id] = profile.id
                it[name] = profile.name
                it[provider] = profile.provider
                it[model] = profile.model
                it[systemPrompt] = profile.systemPrompt
                it[tools] = profile.tools
                it[skills] = profile.skills
                it[maxIterations] = profile.maxIterations
                it[temperature] = profile.temperature
                it[reasoningEffort] = profile.reasoningEffort.name
                it[isSystem] = profile.isSystem
                it[ephemeral] = profile.ephemeral
                it[createdAt] = profile.createdAt
                it[updatedAt] = profile.updatedAt
            }
        }
    }

    override suspend fun updateAgentProfile(profile: AgentProfileRow) {
        dbQuery {
            AgentProfiles.update({ AgentProfiles.id eq profile.id }) {
                it[name] = profile.name
                it[provider] = profile.provider
                it[model] = profile.model
                it[systemPrompt] = profile.systemPrompt
                it[tools] = profile.tools
                it[skills] = profile.skills
                it[maxIterations] = profile.maxIterations
                it[temperature] = profile.temperature
                it[reasoningEffort] = profile.reasoningEffort.name
                it[isSystem] = profile.isSystem
                it[ephemeral] = profile.ephemeral
                it[updatedAt] = profile.updatedAt
            }
        }
    }

    override suspend fun upsertAgentProfile(profile: AgentProfileRow) =
        dbQuery {
            val exists =
                AgentProfiles
                    .selectAll()
                    .where { AgentProfiles.id eq profile.id }
                    .count() > 0
            if (exists) {
                AgentProfiles.update({ AgentProfiles.id eq profile.id }) {
                    it[name] = profile.name
                    it[provider] = profile.provider
                    it[model] = profile.model
                    it[systemPrompt] = profile.systemPrompt
                    it[tools] = profile.tools
                    it[skills] = profile.skills
                    it[maxIterations] = profile.maxIterations
                    it[temperature] = profile.temperature
                    it[isSystem] = profile.isSystem
                    it[ephemeral] = profile.ephemeral
                    it[updatedAt] = profile.updatedAt
                }
            } else {
                AgentProfiles.insert {
                    it[id] = profile.id
                    it[name] = profile.name
                    it[provider] = profile.provider
                    it[model] = profile.model
                    it[systemPrompt] = profile.systemPrompt
                    it[tools] = profile.tools
                    it[skills] = profile.skills
                    it[maxIterations] = profile.maxIterations
                    it[temperature] = profile.temperature
                    it[isSystem] = profile.isSystem
                    it[ephemeral] = profile.ephemeral
                    it[createdAt] = profile.createdAt
                    it[updatedAt] = profile.updatedAt
                }
            }
            Unit
        }

    override suspend fun deleteAgentProfile(id: String) {
        dbQuery { AgentProfiles.deleteWhere { AgentProfiles.id eq id } }
    }

    override suspend fun getAgentProfile(id: String): AgentProfileRow? =
        dbQuery {
            AgentProfiles
                .selectAll()
                .where { AgentProfiles.id eq id }
                .firstOrNull()
                ?.toAgentProfileRow()
        }

    override suspend fun getAllAgentProfiles(): List<AgentProfileRow> =
        dbQuery {
            AgentProfiles
                .selectAll()
                .orderBy(AgentProfiles.isSystem, SortOrder.DESC)
                .orderBy(AgentProfiles.createdAt, SortOrder.ASC)
                .map { it.toAgentProfileRow() }
        }

    override suspend fun getDefaultAgentProfile(): AgentProfileRow? =
        dbQuery {
            AgentProfiles
                .selectAll()
                .where { AgentProfiles.isSystem eq true }
                .firstOrNull()
                ?.toAgentProfileRow()
        }

    override suspend fun setDefaultProfile(id: String) {
        dbQuery {
            // no-op: isSystem is set at seed time only
            // setDefaultProfile is deprecated — system agents are non-deletable
        }
    }

    // ===== CHECKPOINTS =====

    override suspend fun insertCheckpoint(
        sessionId: String,
        stepIndex: Int,
        stateJson: String,
    ) = dbQuery {
        Checkpoints.insert {
            it[Checkpoints.sessionId] = sessionId
            it[Checkpoints.stepIndex] = stepIndex
            it[Checkpoints.stateJson] = stateJson
            it[createdAt] = System.currentTimeMillis()
        }
        Unit
    }

    override suspend fun getLatestCheckpoint(sessionId: String): String? =
        dbQuery {
            Checkpoints
                .selectAll()
                .where { Checkpoints.sessionId eq sessionId }
                .orderBy(Checkpoints.createdAt to org.jetbrains.exposed.v1.core.SortOrder.DESC)
                .limit(1)
                .map { it[Checkpoints.stateJson] }
                .firstOrNull()
        }

    override suspend fun clearCheckpoints(sessionId: String) =
        dbQuery {
            Checkpoints.deleteWhere { Checkpoints.sessionId eq sessionId }
            Unit
        }

    // ===== USER FACTS =====

    override suspend fun insertUserFact(fact: UserFactRow) =
        dbQuery {
            UserFacts.insert {
                it[userId] = fact.userId
                it[category] = fact.category
                it[UserFacts.fact] = fact.fact
                it[confidence] = fact.confidence
                it[sourceSession] = fact.sourceSession
                it[createdAt] = fact.createdAt
                it[updatedAt] = fact.updatedAt
            }
            Unit
        }

    override suspend fun getAllUserFacts(userId: String): List<UserFactRow> =
        dbQuery {
            UserFacts
                .selectAll()
                .where { UserFacts.userId eq userId }
                .map { it.toUserFactRow() }
        }

    override suspend fun searchUserFacts(query: String): List<UserFactRow> =
        dbQuery {
            val trimmed = query.trim()
            if (trimmed.isBlank()) return@dbQuery emptyList()
            UserFacts
                .selectAll()
                .where {
                    UserFacts.fact like "%$trimmed%"
                }.map { it.toUserFactRow() }
        }

    override suspend fun deleteUserFact(id: Int) =
        dbQuery {
            UserFacts.deleteWhere { UserFacts.id eq id }
            Unit
        }

    // ===== SCHEDULED TASKS =====

    override suspend fun insertScheduledTask(task: ScheduledTaskRow) =
        dbQuery {
            ScheduledTasks.insert {
                it[id] = task.id
                it[name] = task.name
                it[cronExpression] = task.cronExpression
                it[prompt] = task.prompt
                it[profileId] = task.profileId
                it[enabled] = task.enabled
                it[lastRunAt] = task.lastRunAt
                it[nextRunAt] = task.nextRunAt
                it[lastRunStatus] = task.lastRunStatus
                it[createdAt] = task.createdAt
            }
            Unit
        }

    override suspend fun updateScheduledTask(task: ScheduledTaskRow) =
        dbQuery {
            ScheduledTasks.update({ ScheduledTasks.id eq task.id }) {
                it[name] = task.name
                it[cronExpression] = task.cronExpression
                it[prompt] = task.prompt
                it[profileId] = task.profileId
                it[enabled] = task.enabled
                it[nextRunAt] = task.nextRunAt
            }
            Unit
        }

    override suspend fun deleteScheduledTask(id: String) =
        dbQuery {
            ScheduledTasks.deleteWhere { ScheduledTasks.id eq id }
            Unit
        }

    override suspend fun getScheduledTask(id: String): ScheduledTaskRow? =
        dbQuery {
            ScheduledTasks
                .selectAll()
                .where { ScheduledTasks.id eq id }
                .firstOrNull()
                ?.toScheduledTaskRow()
        }

    override suspend fun getAllScheduledTasks(): List<ScheduledTaskRow> =
        dbQuery {
            ScheduledTasks
                .selectAll()
                .orderBy(ScheduledTasks.createdAt, SortOrder.ASC)
                .map { it.toScheduledTaskRow() }
        }

    override suspend fun getEnabledScheduledTasks(): List<ScheduledTaskRow> =
        dbQuery {
            ScheduledTasks
                .selectAll()
                .where { ScheduledTasks.enabled eq true }
                .map { it.toScheduledTaskRow() }
        }

    override suspend fun updateScheduledTaskRun(
        id: String,
        lastRunAt: Long,
        nextRunAt: Long?,
        status: String,
    ) = dbQuery {
        ScheduledTasks.update({ ScheduledTasks.id eq id }) {
            it[ScheduledTasks.lastRunAt] = lastRunAt
            it[ScheduledTasks.nextRunAt] = nextRunAt
            it[lastRunStatus] = status
        }
        Unit
    }

    // ── Webhook Channels ──

    override suspend fun insertWebhookChannel(channel: WebhookChannelRow) =
        dbQuery {
            WebhookChannels.insert {
                it[id] = channel.id
                it[name] = channel.name
                it[type] = channel.type
                it[secret] = channel.secret
                it[outboundUrl] = channel.outboundUrl
                it[enabled] = channel.enabled
                it[headerName] = channel.headerName
                it[payloadTemplate] = channel.payloadTemplate
                it[createdAt] = channel.createdAt
            }
            Unit
        }

    override suspend fun updateWebhookChannel(channel: WebhookChannelRow) =
        dbQuery {
            WebhookChannels.update({ WebhookChannels.id eq channel.id }) {
                it[name] = channel.name
                it[type] = channel.type
                it[secret] = channel.secret
                it[outboundUrl] = channel.outboundUrl
                it[enabled] = channel.enabled
                it[headerName] = channel.headerName
                it[payloadTemplate] = channel.payloadTemplate
            }
            Unit
        }

    override suspend fun deleteWebhookChannel(id: String) =
        dbQuery {
            WebhookChannels.deleteWhere { WebhookChannels.id eq id }
            Unit
        }

    override suspend fun getWebhookChannel(name: String): WebhookChannelRow? =
        dbQuery {
            WebhookChannels
                .selectAll()
                .where { WebhookChannels.name eq name }
                .singleOrNull()
                ?.toWebhookChannelRow()
        }

    override suspend fun getWebhookChannelById(id: String): WebhookChannelRow? =
        dbQuery {
            WebhookChannels
                .selectAll()
                .where { WebhookChannels.id eq id }
                .singleOrNull()
                ?.toWebhookChannelRow()
        }

    override suspend fun getAllWebhookChannels(): List<WebhookChannelRow> =
        dbQuery {
            WebhookChannels.selectAll().map { it.toWebhookChannelRow() }
        }

    // ===== SETTINGS =====

    override suspend fun getSetting(key: String): String? =
        dbQuery {
            Settings
                .selectAll()
                .where { Settings.key eq key }
                .firstOrNull()
                ?.get(Settings.value)
        }

    override suspend fun upsertSetting(
        key: String,
        value: String,
    ) {
        dbQuery {
            val exists = Settings.selectAll().where { Settings.key eq key }.count() > 0
            if (exists) {
                Settings.update({ Settings.key eq key }) {
                    it[Settings.value] = value
                    it[updatedAt] = System.currentTimeMillis()
                }
            } else {
                Settings.insert {
                    it[Settings.key] = key
                    it[Settings.value] = value
                    it[updatedAt] = System.currentTimeMillis()
                }
            }
        }
    }

    override suspend fun deleteSetting(key: String) {
        dbQuery {
            Settings.deleteWhere { Settings.key eq key }
        }
    }

    override suspend fun getAllSettings(): Map<String, String> =
        dbQuery {
            Settings.selectAll().associate {
                it[Settings.key] to it[Settings.value]
            }
        }

    // ===== DURABLE APPROVAL GRANTS =====

    override suspend fun getPersistentApprovalGrants(now: Long): List<PersistentApprovalGrantRow> =
        dbQuery {
            ApprovalGrants.deleteWhere { ApprovalGrants.expiresAt lessEq now }
            ApprovalGrants.selectAll().map { row ->
                PersistentApprovalGrantRow(
                    id = row[ApprovalGrants.id],
                    fingerprint = row[ApprovalGrants.fingerprint],
                    allowed = row[ApprovalGrants.allowed],
                    createdAt = row[ApprovalGrants.createdAt],
                    expiresAt = row[ApprovalGrants.expiresAt],
                )
            }
        }

    override suspend fun replacePersistentApprovalGrant(grant: PersistentApprovalGrantRow) {
        dbQuery {
            ApprovalGrants.deleteWhere {
                (ApprovalGrants.fingerprint eq grant.fingerprint) and
                    (ApprovalGrants.allowed eq grant.allowed)
            }
            ApprovalGrants.insert {
                it[id] = grant.id
                it[fingerprint] = grant.fingerprint
                it[allowed] = grant.allowed
                it[createdAt] = grant.createdAt
                it[expiresAt] = grant.expiresAt
            }
        }
    }

    override suspend fun deletePersistentApprovalGrant(id: String) {
        dbQuery {
            ApprovalGrants.deleteWhere { ApprovalGrants.id eq id }
        }
    }

    // ===== GATEWAY SECURITY =====

    override suspend fun getRemoteOwner(): RemoteOwnerRow? =
        dbQuery {
            RemoteOwners.selectAll().firstOrNull()?.toRemoteOwnerRow()
        }

    override suspend fun upsertRemoteOwner(owner: RemoteOwnerRow) {
        dbQuery {
            val exists = RemoteOwners.selectAll().where { RemoteOwners.id eq owner.id }.count() > 0
            if (exists) {
                RemoteOwners.update({ RemoteOwners.id eq owner.id }) {
                    it[username] = owner.username
                    it[passwordHash] = owner.passwordHash
                    it[updatedAt] = owner.updatedAt
                }
            } else {
                RemoteOwners.insert {
                    it[id] = owner.id
                    it[username] = owner.username
                    it[passwordHash] = owner.passwordHash
                    it[createdAt] = owner.createdAt
                    it[updatedAt] = owner.updatedAt
                }
            }
        }
    }

    override suspend fun insertAuthSession(session: AuthSessionRow) {
        dbQuery {
            AuthSessions.insert {
                it[id] = session.id
                it[ownerId] = session.ownerId
                it[tokenHash] = session.tokenHash
                it[createdAt] = session.createdAt
                it[expiresAt] = session.expiresAt
                it[lastSeenAt] = session.lastSeenAt
                it[revokedAt] = session.revokedAt
            }
        }
    }

    override suspend fun getAuthSessionByTokenHash(tokenHash: String): AuthSessionRow? =
        dbQuery {
            AuthSessions
                .selectAll()
                .where { AuthSessions.tokenHash eq tokenHash }
                .firstOrNull()
                ?.toAuthSessionRow()
        }

    override suspend fun touchAuthSession(
        id: String,
        lastSeenAt: Long,
    ) {
        dbQuery {
            AuthSessions.update({ AuthSessions.id eq id }) { it[AuthSessions.lastSeenAt] = lastSeenAt }
        }
    }

    override suspend fun revokeAuthSession(
        id: String,
        revokedAt: Long,
    ) {
        dbQuery {
            AuthSessions.update({ AuthSessions.id eq id }) { it[AuthSessions.revokedAt] = revokedAt }
        }
    }

    override suspend fun revokeAuthSessionsForOwner(
        ownerId: String,
        revokedAt: Long,
    ) {
        dbQuery {
            AuthSessions.update({ (AuthSessions.ownerId eq ownerId) and AuthSessions.revokedAt.isNull() }) {
                it[AuthSessions.revokedAt] = revokedAt
            }
        }
    }

    override suspend fun insertSecurityAuditLog(log: SecurityAuditLogRow) {
        securityAuditWriteMutex.withLock {
            dbQuery {
                val previousHash =
                    SecurityAuditLogs
                        .selectAll()
                        .where { SecurityAuditLogs.entryHash neq "" }
                        .orderBy(SecurityAuditLogs.id, SortOrder.DESC)
                        .limit(1)
                        .singleOrNull()
                        ?.get(SecurityAuditLogs.entryHash)
                        ?: SECURITY_AUDIT_GENESIS_HASH
                val entryHash = securityAuditHash(log, previousHash)
                SecurityAuditLogs.insert {
                    it[eventType] = log.eventType
                    it[actor] = log.actor
                    it[remoteAddress] = log.remoteAddress
                    it[detail] = log.detail
                    it[createdAt] = log.createdAt
                    it[SecurityAuditLogs.previousHash] = previousHash
                    it[SecurityAuditLogs.entryHash] = entryHash
                }
            }
        }
    }

    override suspend fun listSecurityAuditLogs(limit: Int): List<SecurityAuditLogRow> =
        dbQuery {
            SecurityAuditLogs
                .selectAll()
                .orderBy(SecurityAuditLogs.id, SortOrder.ASC)
                .limit(limit.coerceIn(1, MAX_SECURITY_AUDIT_READ_LIMIT))
                .map(ResultRow::toSecurityAuditLogRow)
        }

    override suspend fun verifySecurityAuditChain(): SecurityAuditChainVerification {
        val rows =
            dbQuery {
                SecurityAuditLogs
                    .selectAll()
                    .where { SecurityAuditLogs.entryHash neq "" }
                    .orderBy(SecurityAuditLogs.id, SortOrder.ASC)
                    .map(ResultRow::toSecurityAuditLogRow)
            }
        var expectedPreviousHash = SECURITY_AUDIT_GENESIS_HASH
        rows.forEachIndexed { index, row ->
            val expectedHash = securityAuditHash(row, expectedPreviousHash)
            if (row.previousHash != expectedPreviousHash || row.entryHash != expectedHash) {
                return SecurityAuditChainVerification(
                    valid = false,
                    entries = index,
                    invalidEntryHash = row.entryHash,
                )
            }
            expectedPreviousHash = row.entryHash
        }
        return SecurityAuditChainVerification(valid = true, entries = rows.size)
    }

    override suspend fun insertOAuthAuthorization(authorization: OAuthAuthorizationRow) {
        dbQuery {
            OAuthAuthorizations.insert {
                it[stateHash] = authorization.stateHash
                it[ownerId] = authorization.ownerId
                it[provider] = authorization.provider
                it[redirectUri] = authorization.redirectUri
                it[encryptedVerifier] = authorization.encryptedVerifier
                it[expiresAt] = authorization.expiresAt
                it[consumedAt] = authorization.consumedAt
            }
        }
    }

    override suspend fun consumeOAuthAuthorization(
        stateHash: String,
        now: Long,
    ): OAuthAuthorizationRow? =
        dbQuery {
            val row =
                OAuthAuthorizations
                    .selectAll()
                    .where { OAuthAuthorizations.stateHash eq stateHash }
                    .firstOrNull()
                    ?: return@dbQuery null
            if (row[OAuthAuthorizations.consumedAt] != null || row[OAuthAuthorizations.expiresAt] <= now) {
                return@dbQuery null
            }
            OAuthAuthorizations.update({ OAuthAuthorizations.stateHash eq stateHash }) {
                it[consumedAt] = now
            }
            row.toOAuthAuthorizationRow()
        }

    override suspend fun upsertOAuthConnection(connection: OAuthConnectionRow) {
        dbQuery {
            val exists =
                OAuthConnections
                    .selectAll()
                    .where { (OAuthConnections.ownerId eq connection.ownerId) and (OAuthConnections.provider eq connection.provider) }
                    .count() > 0
            if (exists) {
                OAuthConnections.update({ (OAuthConnections.ownerId eq connection.ownerId) and (OAuthConnections.provider eq connection.provider) }) {
                    it[encryptedTokens] = connection.encryptedTokens
                    it[expiresAt] = connection.expiresAt
                    it[updatedAt] = connection.updatedAt
                }
            } else {
                OAuthConnections.insert {
                    it[ownerId] = connection.ownerId
                    it[provider] = connection.provider
                    it[encryptedTokens] = connection.encryptedTokens
                    it[expiresAt] = connection.expiresAt
                    it[updatedAt] = connection.updatedAt
                }
            }
        }
    }

    override suspend fun getOAuthConnection(
        ownerId: String,
        provider: String,
    ): OAuthConnectionRow? =
        dbQuery {
            OAuthConnections
                .selectAll()
                .where { (OAuthConnections.ownerId eq ownerId) and (OAuthConnections.provider eq provider) }
                .firstOrNull()
                ?.toOAuthConnectionRow()
        }

    override suspend fun deleteOAuthConnection(
        ownerId: String,
        provider: String,
    ) {
        dbQuery {
            OAuthConnections.deleteWhere { (OAuthConnections.ownerId eq ownerId) and (OAuthConnections.provider eq provider) }
        }
    }

    override suspend fun getOAuthConnections(ownerId: String): List<OAuthConnectionRow> =
        dbQuery {
            OAuthConnections
                .selectAll()
                .where { OAuthConnections.ownerId eq ownerId }
                .map { it.toOAuthConnectionRow() }
        }

    override suspend fun upsertMcpServerConfig(config: McpServerConfigRow) {
        dbQuery {
            val exists = McpServerConfigs.selectAll().where { McpServerConfigs.id eq config.id }.count() > 0
            if (exists) {
                McpServerConfigs.update({ McpServerConfigs.id eq config.id }) {
                    it[configJson] = config.configJson
                    it[encryptedSecrets] = config.encryptedSecrets
                    it[updatedAt] = config.updatedAt
                }
            } else {
                McpServerConfigs.insert {
                    it[id] = config.id
                    it[configJson] = config.configJson
                    it[encryptedSecrets] = config.encryptedSecrets
                    it[createdAt] = config.createdAt
                    it[updatedAt] = config.updatedAt
                }
            }
        }
    }

    override suspend fun getMcpServerConfigs(): List<McpServerConfigRow> =
        dbQuery {
            McpServerConfigs.selectAll().map { it.toMcpServerConfigRow() }
        }

    override suspend fun deleteMcpServerConfig(id: String) {
        dbQuery {
            McpServerConfigs.deleteWhere { McpServerConfigs.id eq id }
        }
    }

    // ===== LLM USAGE LOGS =====

    override suspend fun insertLlmUsageLog(log: LlmUsageLogRow) =
        dbQuery {
            LlmUsageLogs.insert {
                it[provider] = log.provider
                it[model] = log.model
                it[promptTokens] = log.promptTokens
                it[completionTokens] = log.completionTokens
                it[cost] = log.cost
                it[timestamp] = log.timestamp
            }
            Unit
        }

    override suspend fun getAggregatedLlmStats(): LlmAggregatedStats =
        dbQuery {
            val count = LlmUsageLogs.id.count()
            val sumPrompt = LlmUsageLogs.promptTokens.sum()
            val sumCompletion = LlmUsageLogs.completionTokens.sum()
            val sumCost = LlmUsageLogs.cost.sum()
            LlmUsageLogs.select(count, sumPrompt, sumCompletion, sumCost)
                .firstOrNull()?.let {
                    LlmAggregatedStats(
                        totalRequests = it[count].toInt(),
                        totalPromptTokens = it[sumPrompt]?.toLong() ?: 0L,
                        totalCompletionTokens = it[sumCompletion]?.toLong() ?: 0L,
                        totalCost = it[sumCost]?.toDouble() ?: 0.0,
                    )
                } ?: LlmAggregatedStats(0, 0L, 0L, 0.0)
        }

    override suspend fun getLlmUsageByProvider(): Map<String, LlmProviderStats> =
        dbQuery {
            val count = LlmUsageLogs.id.count()
            val sumTokens = (LlmUsageLogs.promptTokens + LlmUsageLogs.completionTokens).sum()
            val sumCost = LlmUsageLogs.cost.sum()
            LlmUsageLogs
                .select(LlmUsageLogs.provider, count, sumTokens, sumCost)
                .groupBy(LlmUsageLogs.provider)
                .associate { row ->
                    val p = row[LlmUsageLogs.provider]
                    p to LlmProviderStats(
                        provider = p,
                        requestCount = row[count].toInt(),
                        totalTokens = row[sumTokens]?.toLong() ?: 0L,
                        totalCost = row[sumCost]?.toDouble() ?: 0.0,
                    )
                }
        }

    private fun appendAgentRunEventInTransaction(event: AgentRunEventRecord): Boolean {
        require(event.eventId.isNotBlank()) { "Agent run event ID must not be blank" }
        require(event.runId.isNotBlank()) { "Agent run event run ID must not be blank" }
        require(event.sequence == 0L) { "Agent run event sequence is assigned by the database" }

        val maxSequence = AgentRunEvents.sequence.max()
        val currentSequence =
            AgentRunEvents
                .select(maxSequence)
                .where { AgentRunEvents.runId eq event.runId }
                .singleOrNull()
                ?.get(maxSequence) ?: 0L
        val nextSequence = currentSequence + 1L
        return AgentRunEvents
            .insertIgnore {
                it[eventId] = event.eventId
                it[runId] = event.runId
                it[sequence] = nextSequence
                it[eventType] = event.type.name
                it[parentRunId] = event.parentRunId
                it[sessionId] = event.sessionId
                it[origin] = event.origin
                it[projectId] = event.projectId
                it[requestFingerprint] = event.requestFingerprint
                it[stepId] = event.stepId
                it[stepCount] = event.stepCount
                it[intentId] = event.intentId
                it[idempotencyKeyHash] = event.idempotencyKeyHash
                it[invocationHash] = event.invocationHash
                it[toolName] = event.toolName
                it[risk] = event.risk?.name
                it[runStatus] = event.runStatus?.name
                it[intentStatus] = event.intentStatus?.name
                it[resultHash] = event.resultHash
                it[artifactHash] = event.artifactHash
                it[errorCode] = event.errorCode
                it[approvalId] = event.approvalId
                it[approvalAllowed] = event.approvalAllowed
                it[approvalScope] = event.approvalScope?.name
                it[createdAt] = event.createdAt
                it[eventVersion] = event.eventVersion
            }.insertedCount > 0
    }

    // ===== HELPERS =====

    @Suppress("DEPRECATION")
    private suspend fun <T> dbQuery(block: Transaction.() -> T): T = newSuspendedTransaction(Dispatchers.IO, db) { block() }

    private suspend fun <T> eventDbQuery(block: Transaction.() -> T): T = eventWriteMutex.withLock { dbQuery(block) }
}

// ===== EXTENSION MAPPERS =====

private fun ResultRow.toSessionRow() =
    SessionRow(
        id = this[Sessions.id],
        createdAt = this[Sessions.createdAt],
        metadata = this[Sessions.metadata],
        title = this[Sessions.title],
        projectId = this[Sessions.projectId],
    )

private fun ResultRow.toAgentRunRecord() =
    AgentRunRecord(
        runId = this[AgentRuns.runId],
        parentRunId = this[AgentRuns.parentRunId],
        sessionId = this[AgentRuns.sessionId],
        origin = this[AgentRuns.origin],
        projectId = this[AgentRuns.projectId],
        requestFingerprint = this[AgentRuns.requestFingerprint],
        status = AgentRunStatus.valueOf(this[AgentRuns.status]),
        stepCount = this[AgentRuns.stepCount],
        lastStepId = this[AgentRuns.lastStepId],
        errorCode = this[AgentRuns.errorCode],
        createdAt = this[AgentRuns.createdAt],
        startedAt = this[AgentRuns.startedAt],
        finishedAt = this[AgentRuns.finishedAt],
        updatedAt = this[AgentRuns.updatedAt],
    )

private fun ResultRow.toResourceGovernorStateRow() =
    ResourceGovernorStateRow(
        rootRunId = this[ResourceGovernors.rootRunId],
        maxTokens = this[ResourceGovernors.maxTokens],
        maxCostDollars = this[ResourceGovernors.maxCostDollars],
        maxLlmCalls = this[ResourceGovernors.maxLlmCalls],
        maxToolStarts = this[ResourceGovernors.maxToolStarts],
        maxSubAgents = this[ResourceGovernors.maxSubAgents],
        maxDurationMs = this[ResourceGovernors.maxDurationMs],
        startedAt = this[ResourceGovernors.startedAt],
        tokensUsed = this[ResourceGovernors.tokensUsed],
        costDollars = this[ResourceGovernors.costDollars],
        llmCallsStarted = this[ResourceGovernors.llmCallsStarted],
        toolsStarted = this[ResourceGovernors.toolsStarted],
        subAgentsStarted = this[ResourceGovernors.subAgentsStarted],
        version = this[ResourceGovernors.version],
        updatedAt = this[ResourceGovernors.updatedAt],
    )

private fun ResultRow.toResourceGovernorBindingRow() =
    ResourceGovernorBindingRow(
        sessionId = this[ResourceGovernorBindings.sessionId],
        rootRunId = this[ResourceGovernorBindings.rootRunId],
        runId = this[ResourceGovernorBindings.runId],
        createdAt = this[ResourceGovernorBindings.createdAt],
        updatedAt = this[ResourceGovernorBindings.updatedAt],
    )

private fun ResultRow.toMcpTaskRecord() =
    McpTaskRecord(
        taskId = this[McpTasks.taskId],
        ownerSessionId = this[McpTasks.ownerSessionId],
        method = this[McpTasks.method],
        resourceName = this[McpTasks.resourceName],
        runId = this[McpTasks.runId],
        status = McpTaskStatus.valueOf(this[McpTasks.status]),
        statusMessage = this[McpTasks.statusMessage],
        resultJson = this[McpTasks.resultJson],
        errorJson = this[McpTasks.errorJson],
        inputRequestsJson = this[McpTasks.inputRequestsJson],
        createdAt = this[McpTasks.createdAt],
        lastUpdatedAt = this[McpTasks.lastUpdatedAt],
        ttlMs = this[McpTasks.ttlMs],
        pollIntervalMs = this[McpTasks.pollIntervalMs],
    )

private fun ResultRow.toToolIntentRecord() =
    ToolIntentRecord(
        intentId = this[ToolIntents.intentId],
        idempotencyKeyHash = this[ToolIntents.idempotencyKeyHash],
        invocationHash = this[ToolIntents.invocationHash],
        runId = this[ToolIntents.runId],
        stepId = this[ToolIntents.stepId],
        sessionId = this[ToolIntents.sessionId],
        toolName = this[ToolIntents.toolName],
        risk = dev.promethe.api.ToolRisk.valueOf(this[ToolIntents.risk]),
        status = ToolIntentStatus.valueOf(this[ToolIntents.status]),
        resultHash = this[ToolIntents.resultHash],
        artifactHash = this[ToolIntents.artifactHash],
        errorCode = this[ToolIntents.errorCode],
        createdAt = this[ToolIntents.createdAt],
        startedAt = this[ToolIntents.startedAt],
        finishedAt = this[ToolIntents.finishedAt],
        updatedAt = this[ToolIntents.updatedAt],
    )

private fun ResultRow.toAgentRunEventRecord() =
    AgentRunEventRecord(
        eventId = this[AgentRunEvents.eventId],
        runId = this[AgentRunEvents.runId],
        sequence = this[AgentRunEvents.sequence],
        type = AgentRunEventType.valueOf(this[AgentRunEvents.eventType]),
        parentRunId = this[AgentRunEvents.parentRunId],
        sessionId = this[AgentRunEvents.sessionId],
        origin = this[AgentRunEvents.origin],
        projectId = this[AgentRunEvents.projectId],
        requestFingerprint = this[AgentRunEvents.requestFingerprint],
        stepId = this[AgentRunEvents.stepId],
        stepCount = this[AgentRunEvents.stepCount],
        intentId = this[AgentRunEvents.intentId],
        idempotencyKeyHash = this[AgentRunEvents.idempotencyKeyHash],
        invocationHash = this[AgentRunEvents.invocationHash],
        toolName = this[AgentRunEvents.toolName],
        risk = this[AgentRunEvents.risk]?.let(dev.promethe.api.ToolRisk::valueOf),
        runStatus = this[AgentRunEvents.runStatus]?.let(AgentRunStatus::valueOf),
        intentStatus = this[AgentRunEvents.intentStatus]?.let(ToolIntentStatus::valueOf),
        resultHash = this[AgentRunEvents.resultHash],
        artifactHash = this[AgentRunEvents.artifactHash],
        errorCode = this[AgentRunEvents.errorCode],
        approvalId = this[AgentRunEvents.approvalId],
        approvalAllowed = this[AgentRunEvents.approvalAllowed],
        approvalScope = this[AgentRunEvents.approvalScope]?.let(AgentApprovalScope::valueOf),
        createdAt = this[AgentRunEvents.createdAt],
        eventVersion = this[AgentRunEvents.eventVersion],
    )

private fun ResultRow.toProjectRow() =
    ProjectRow(
        id = this[Projects.id],
        name = this[Projects.name],
        description = this[Projects.description],
        instructions = this[Projects.instructions],
        workspacePath = this[Projects.workspacePath],
        memoryNamespace = this[Projects.memoryNamespace],
        archived = this[Projects.archived],
        createdAt = this[Projects.createdAt],
        updatedAt = this[Projects.updatedAt],
    )

private fun ResultRow.toMessageRow() =
    MessageRow(
        id = this[Messages.id],
        sessionId = this[Messages.sessionId],
        role = this[Messages.role],
        content = this[Messages.content],
        timestamp = this[Messages.timestamp],
        dataTrust = PolicyDataTrust.valueOf(this[Messages.dataTrust]),
        sourceRunId = this[Messages.sourceRunId],
    )

private fun ResultRow.toFeedbackRow() =
    FeedbackRow(
        id = this[Feedbacks.id],
        sessionId = this[Feedbacks.sessionId],
        score = this[Feedbacks.score],
        comment = this[Feedbacks.comment],
        timestamp = this[Feedbacks.timestamp],
    )

private fun ResultRow.toAgentProfileRow() =
    AgentProfileRow(
        id = this[AgentProfiles.id],
        name = this[AgentProfiles.name],
        provider = this[AgentProfiles.provider],
        model = this[AgentProfiles.model],
        systemPrompt = this[AgentProfiles.systemPrompt],
        tools = this[AgentProfiles.tools],
        skills = this[AgentProfiles.skills],
        maxIterations = this[AgentProfiles.maxIterations],
        temperature = this[AgentProfiles.temperature],
        isSystem = this[AgentProfiles.isSystem],
        ephemeral = this[AgentProfiles.ephemeral],
        reasoningEffort = try {
            dev.promethe.api.ReasoningEffort.valueOf(this[AgentProfiles.reasoningEffort])
        } catch (e: Exception) {
            dev.promethe.api.ReasoningEffort.AUTO
        },
        createdAt = this[AgentProfiles.createdAt],
        updatedAt = this[AgentProfiles.updatedAt],
    )

private fun ResultRow.toUserFactRow() =
    UserFactRow(
        id = this[UserFacts.id],
        userId = this[UserFacts.userId],
        category = this[UserFacts.category],
        fact = this[UserFacts.fact],
        confidence = this[UserFacts.confidence],
        sourceSession = this[UserFacts.sourceSession],
        createdAt = this[UserFacts.createdAt],
        updatedAt = this[UserFacts.updatedAt],
    )

private fun ResultRow.toScheduledTaskRow() =
    ScheduledTaskRow(
        id = this[ScheduledTasks.id],
        name = this[ScheduledTasks.name],
        cronExpression = this[ScheduledTasks.cronExpression],
        prompt = this[ScheduledTasks.prompt],
        profileId = this[ScheduledTasks.profileId],
        enabled = this[ScheduledTasks.enabled],
        lastRunAt = this[ScheduledTasks.lastRunAt],
        nextRunAt = this[ScheduledTasks.nextRunAt],
        lastRunStatus = this[ScheduledTasks.lastRunStatus],
        createdAt = this[ScheduledTasks.createdAt],
    )

private fun ResultRow.toWebhookChannelRow() =
    WebhookChannelRow(
        id = this[WebhookChannels.id],
        name = this[WebhookChannels.name],
        type = this[WebhookChannels.type],
        secret = this[WebhookChannels.secret],
        outboundUrl = this[WebhookChannels.outboundUrl],
        enabled = this[WebhookChannels.enabled],
        headerName = this[WebhookChannels.headerName],
        payloadTemplate = this[WebhookChannels.payloadTemplate],
        createdAt = this[WebhookChannels.createdAt],
    )

private fun ResultRow.toRemoteOwnerRow() =
    RemoteOwnerRow(
        id = this[RemoteOwners.id],
        username = this[RemoteOwners.username],
        passwordHash = this[RemoteOwners.passwordHash],
        createdAt = this[RemoteOwners.createdAt],
        updatedAt = this[RemoteOwners.updatedAt],
    )

private fun ResultRow.toAuthSessionRow() =
    AuthSessionRow(
        id = this[AuthSessions.id],
        ownerId = this[AuthSessions.ownerId],
        tokenHash = this[AuthSessions.tokenHash],
        createdAt = this[AuthSessions.createdAt],
        expiresAt = this[AuthSessions.expiresAt],
        lastSeenAt = this[AuthSessions.lastSeenAt],
        revokedAt = this[AuthSessions.revokedAt],
    )

private fun ResultRow.toOAuthAuthorizationRow() =
    OAuthAuthorizationRow(
        stateHash = this[OAuthAuthorizations.stateHash],
        ownerId = this[OAuthAuthorizations.ownerId],
        provider = this[OAuthAuthorizations.provider],
        redirectUri = this[OAuthAuthorizations.redirectUri],
        encryptedVerifier = this[OAuthAuthorizations.encryptedVerifier],
        expiresAt = this[OAuthAuthorizations.expiresAt],
        consumedAt = this[OAuthAuthorizations.consumedAt],
    )

private fun ResultRow.toOAuthConnectionRow() =
    OAuthConnectionRow(
        ownerId = this[OAuthConnections.ownerId],
        provider = this[OAuthConnections.provider],
        encryptedTokens = this[OAuthConnections.encryptedTokens],
        expiresAt = this[OAuthConnections.expiresAt],
        updatedAt = this[OAuthConnections.updatedAt],
    )

private fun ResultRow.toMcpServerConfigRow() =
    McpServerConfigRow(
        id = this[McpServerConfigs.id],
        configJson = this[McpServerConfigs.configJson],
        encryptedSecrets = this[McpServerConfigs.encryptedSecrets],
        createdAt = this[McpServerConfigs.createdAt],
        updatedAt = this[McpServerConfigs.updatedAt],
    )

private fun ResultRow.toSecurityAuditLogRow() =
    SecurityAuditLogRow(
        eventType = this[SecurityAuditLogs.eventType],
        actor = this[SecurityAuditLogs.actor],
        remoteAddress = this[SecurityAuditLogs.remoteAddress],
        detail = this[SecurityAuditLogs.detail],
        createdAt = this[SecurityAuditLogs.createdAt],
        previousHash = this[SecurityAuditLogs.previousHash],
        entryHash = this[SecurityAuditLogs.entryHash],
    )

private const val SECURITY_AUDIT_GENESIS_HASH = "promethe-security-audit-genesis-v1"
private const val MAX_SECURITY_AUDIT_READ_LIMIT = 100_000

private fun securityAuditHash(
    log: SecurityAuditLogRow,
    previousHash: String,
): String {
    val canonical =
        buildString {
            append("promethe-security-audit-v1\u0000")
            appendField(previousHash)
            appendField(log.eventType)
            appendField(log.actor)
            appendField(log.remoteAddress)
            appendField(log.detail)
            append(log.createdAt)
        }
    return MessageDigest
        .getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

private fun StringBuilder.appendField(value: String) {
    append(value.length)
    append(':')
    append(value)
    append('\u0000')
}
