package dev.promethe.db

import dev.promethe.api.AgentRunRecord
import dev.promethe.api.AgentRunStatus
import kotlinx.coroutines.Dispatchers
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
    /**
     * Initialize schema: create tables + FTS5 index + sync triggers.
     * Called once at startup by [DatabaseFactory].
     */
    fun initialize() {
        transaction(db) {
            @Suppress("DEPRECATION")
            SchemaUtils.createMissingTablesAndColumns(
                AgentRuns,
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

    override suspend fun insertAgentRun(run: AgentRunRecord): Boolean =
        dbQuery {
            AgentRuns
                .insertIgnore {
                    it[runId] = run.runId
                    it[parentRunId] = run.parentRunId
                    it[sessionId] = run.sessionId
                    it[origin] = run.origin
                    it[projectId] = run.projectId
                    it[status] = run.status.name
                    it[stepCount] = run.stepCount
                    it[lastStepId] = run.lastStepId
                    it[errorCode] = run.errorCode
                    it[createdAt] = run.createdAt
                    it[startedAt] = run.startedAt
                    it[finishedAt] = run.finishedAt
                    it[updatedAt] = run.updatedAt
                }.insertedCount > 0
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
    ): Boolean =
        dbQuery {
            if (expectedStatuses.isEmpty()) return@dbQuery false
            AgentRuns.update(
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
        }

    override suspend fun updateAgentRunProgress(
        runId: String,
        stepCount: Int,
        lastStepId: String,
        updatedAt: Long,
    ): Boolean =
        dbQuery {
            AgentRuns.update(
                where = {
                    (AgentRuns.runId eq runId) and
                        (AgentRuns.status eq AgentRunStatus.RUNNING.name)
                },
            ) {
                it[AgentRuns.stepCount] = stepCount
                it[AgentRuns.lastStepId] = lastStepId
                it[AgentRuns.updatedAt] = updatedAt
            } > 0
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
    ): Int =
        dbQuery {
            Messages.insert {
                it[Messages.sessionId] = sessionId
                it[Messages.role] = role
                it[Messages.content] = content
                it[Messages.timestamp] = timestamp
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
        dbQuery {
            SecurityAuditLogs.insert {
                it[eventType] = log.eventType
                it[actor] = log.actor
                it[remoteAddress] = log.remoteAddress
                it[detail] = log.detail
                it[createdAt] = log.createdAt
            }
        }
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

    // ===== HELPERS =====

    @Suppress("DEPRECATION")
    private suspend fun <T> dbQuery(block: Transaction.() -> T): T = newSuspendedTransaction(Dispatchers.IO, db) { block() }
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
        status = AgentRunStatus.valueOf(this[AgentRuns.status]),
        stepCount = this[AgentRuns.stepCount],
        lastStepId = this[AgentRuns.lastStepId],
        errorCode = this[AgentRuns.errorCode],
        createdAt = this[AgentRuns.createdAt],
        startedAt = this[AgentRuns.startedAt],
        finishedAt = this[AgentRuns.finishedAt],
        updatedAt = this[AgentRuns.updatedAt],
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
