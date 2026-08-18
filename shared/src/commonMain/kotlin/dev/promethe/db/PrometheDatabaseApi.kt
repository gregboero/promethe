package dev.promethe.db

import dev.promethe.api.ReasoningEffort
import dev.promethe.api.AgentRunRecord
import dev.promethe.api.AgentRunStatus

/**
 * Platform-agnostic database interface consumed by core classes in commonMain.
 * The actual implementation (Exposed) lives in jvmMain.
 */
interface PrometheDatabaseApi {
    // ── Agent runs ──
    suspend fun insertAgentRun(run: AgentRunRecord): Boolean = true

    suspend fun transitionAgentRun(
        runId: String,
        expectedStatuses: Set<AgentRunStatus>,
        status: AgentRunStatus,
        stepCount: Int,
        lastStepId: String?,
        errorCode: String?,
        startedAt: Long?,
        finishedAt: Long?,
        updatedAt: Long,
    ): Boolean = true

    suspend fun updateAgentRunProgress(
        runId: String,
        stepCount: Int,
        lastStepId: String,
        updatedAt: Long,
    ): Boolean = true

    suspend fun getAgentRun(runId: String): AgentRunRecord? = null

    suspend fun getAgentRunsByStatus(statuses: Set<AgentRunStatus>): List<AgentRunRecord> = emptyList()

    // ── Projects ──
    suspend fun insertProject(project: ProjectRow) = Unit

    suspend fun updateProject(project: ProjectRow) = Unit

    suspend fun getProject(id: String): ProjectRow? = null

    suspend fun getAllProjects(): List<ProjectRow> = emptyList()

    suspend fun getProjectSessionCounts(): Map<String, Int> = emptyMap()

    // ── Sessions ──
    suspend fun insertSession(
        id: String,
        createdAt: Long,
        metadata: String?,
    )

    suspend fun insertSessionOrIgnore(
        id: String,
        createdAt: Long,
        metadata: String?,
    )

    suspend fun getAllSessions(): List<SessionRow>

    suspend fun getSession(id: String): SessionRow? = getAllSessions().find { it.id == id }

    suspend fun assignSessionToProject(
        sessionId: String,
        projectId: String?,
    ) = Unit

    suspend fun deleteSession(id: String)

    suspend fun getSessionMessageCounts(): Map<String, Int>

    suspend fun updateSessionTitle(
        id: String,
        title: String,
    )

    suspend fun updateSessionMetadata(
        id: String,
        metadata: String,
    )

    suspend fun getRecentSessions(
        userId: String = "default",
        limit: Int = 10,
    ): List<SessionSummary>

    // ── Messages ──
    suspend fun insertMessage(
        sessionId: String,
        role: String,
        content: String,
        timestamp: Long,
    ): Int

    suspend fun getMessagesForSession(sessionId: String): List<MessageRow>

    suspend fun searchMessages(query: String): List<MessageRow>

    // ── Feedbacks ──
    suspend fun insertFeedback(
        sessionId: String,
        score: Double,
        comment: String?,
        timestamp: Long,
    )

    suspend fun getFeedbackForSession(sessionId: String): List<FeedbackRow>

    suspend fun getAverageFeedback(): FeedbackStats

    suspend fun getRecentPositiveSessions(limit: Int): List<String>

    // ── Agent Profiles ──
    suspend fun insertAgentProfile(profile: AgentProfileRow)

    suspend fun updateAgentProfile(profile: AgentProfileRow)

    suspend fun upsertAgentProfile(profile: AgentProfileRow)

    suspend fun deleteAgentProfile(id: String)

    suspend fun getAgentProfile(id: String): AgentProfileRow?

    suspend fun getAllAgentProfiles(): List<AgentProfileRow>

    suspend fun getDefaultAgentProfile(): AgentProfileRow?

    suspend fun setDefaultProfile(id: String)

    // ── Checkpoints ──
    suspend fun insertCheckpoint(
        sessionId: String,
        stepIndex: Int,
        stateJson: String,
    )

    suspend fun getLatestCheckpoint(sessionId: String): String?

    suspend fun clearCheckpoints(sessionId: String)

    // ── User Facts (Long-Term Memory) ──
    suspend fun insertUserFact(fact: UserFactRow)

    suspend fun getAllUserFacts(userId: String = "default"): List<UserFactRow>

    suspend fun searchUserFacts(query: String): List<UserFactRow>

    suspend fun deleteUserFact(id: Int)

    // ── Scheduled Tasks ──
    suspend fun insertScheduledTask(task: ScheduledTaskRow)

    suspend fun updateScheduledTask(task: ScheduledTaskRow)

    suspend fun deleteScheduledTask(id: String)

    suspend fun getScheduledTask(id: String): ScheduledTaskRow?

    suspend fun getAllScheduledTasks(): List<ScheduledTaskRow>

    suspend fun getEnabledScheduledTasks(): List<ScheduledTaskRow>

    suspend fun updateScheduledTaskRun(
        id: String,
        lastRunAt: Long,
        nextRunAt: Long?,
        status: String,
    )

    // ── Webhook Channels ──
    suspend fun insertWebhookChannel(channel: WebhookChannelRow)

    suspend fun updateWebhookChannel(channel: WebhookChannelRow)

    suspend fun deleteWebhookChannel(id: String)

    suspend fun getWebhookChannel(name: String): WebhookChannelRow?

    suspend fun getWebhookChannelById(id: String): WebhookChannelRow?

    suspend fun getAllWebhookChannels(): List<WebhookChannelRow>

    // ── Settings (key-value store) ──
    suspend fun getSetting(key: String): String?

    suspend fun upsertSetting(
        key: String,
        value: String,
    )

    suspend fun deleteSetting(key: String)

    suspend fun getAllSettings(): Map<String, String>

    // ── Gateway security (JVM implementation overrides these methods) ──
    suspend fun getRemoteOwner(): RemoteOwnerRow? = null

    suspend fun upsertRemoteOwner(owner: RemoteOwnerRow) = Unit

    suspend fun insertAuthSession(session: AuthSessionRow) = Unit

    suspend fun getAuthSessionByTokenHash(tokenHash: String): AuthSessionRow? = null

    suspend fun touchAuthSession(
        id: String,
        lastSeenAt: Long,
    ) = Unit

    suspend fun revokeAuthSession(
        id: String,
        revokedAt: Long,
    ) = Unit

    suspend fun revokeAuthSessionsForOwner(
        ownerId: String,
        revokedAt: Long,
    ) = Unit

    suspend fun insertSecurityAuditLog(log: SecurityAuditLogRow) = Unit

    suspend fun insertOAuthAuthorization(authorization: OAuthAuthorizationRow) = Unit

    suspend fun consumeOAuthAuthorization(
        stateHash: String,
        now: Long,
    ): OAuthAuthorizationRow? = null

    suspend fun upsertOAuthConnection(connection: OAuthConnectionRow) = Unit

    suspend fun getOAuthConnection(
        ownerId: String,
        provider: String,
    ): OAuthConnectionRow? = null

    suspend fun deleteOAuthConnection(
        ownerId: String,
        provider: String,
    ) = Unit

    suspend fun getOAuthConnections(ownerId: String): List<OAuthConnectionRow> = emptyList()

    suspend fun upsertMcpServerConfig(config: McpServerConfigRow) = Unit

    suspend fun getMcpServerConfigs(): List<McpServerConfigRow> = emptyList()

    suspend fun deleteMcpServerConfig(id: String) = Unit

    // ── LLM Usage Logs ──
    suspend fun insertLlmUsageLog(log: LlmUsageLogRow)

    suspend fun getAggregatedLlmStats(): LlmAggregatedStats

    suspend fun getLlmUsageByProvider(): Map<String, LlmProviderStats>
}

// ── Data classes shared between commonMain and jvmMain ──

data class SessionRow(
    val id: String,
    val createdAt: Long,
    val metadata: String?,
    val title: String? = null,
    val projectId: String? = null,
)

data class ProjectRow(
    val id: String,
    val name: String,
    val description: String = "",
    val instructions: String = "",
    val workspacePath: String,
    val memoryNamespace: String,
    val archived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

data class MessageRow(
    val id: Int,
    val sessionId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
)

data class FeedbackRow(
    val id: Int,
    val sessionId: String,
    val score: Double,
    val comment: String?,
    val timestamp: Long,
)

data class FeedbackStats(
    val avgScore: Double,
    val feedbackCount: Long,
)

data class AgentProfileRow(
    val id: String,
    val name: String,
    val provider: String = "openai",
    val model: String = "gpt-4o-mini",
    val systemPrompt: String = "",
    val tools: String = "[]", // JSON array string
    val skills: String = "[]", // JSON array string
    val maxIterations: Int = 10,
    val temperature: Double = 0.2,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.AUTO,
    val isSystem: Boolean = false,
    val ephemeral: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

data class UserFactRow(
    val id: Int = 0,
    val userId: String = "default",
    val category: String,
    val fact: String,
    val confidence: Double = 1.0,
    val sourceSession: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

data class ScheduledTaskRow(
    val id: String,
    val name: String,
    val cronExpression: String,
    val prompt: String,
    val profileId: String? = null,
    val enabled: Boolean = true,
    val lastRunAt: Long? = null,
    val nextRunAt: Long? = null,
    val lastRunStatus: String? = null,
    val createdAt: Long = 0,
    val runAt: Long? = null, // One-shot: epoch ms target time
)

data class WebhookChannelRow(
    val id: String,
    val name: String,
    val type: String = "inbound",
    val secret: String = "",
    val outboundUrl: String = "",
    val enabled: Boolean = true,
    val headerName: String = "",
    val payloadTemplate: String = "",
    val createdAt: Long = 0,
)

data class SessionSummary(
    val sessionId: String,
    val messageCount: Int,
    val lastActivity: String,
)

data class LlmUsageLogRow(
    val id: Int = 0,
    val provider: String,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val cost: Double,
    val timestamp: Long,
)

data class LlmAggregatedStats(
    val totalRequests: Int,
    val totalPromptTokens: Long,
    val totalCompletionTokens: Long,
    val totalCost: Double,
)

data class LlmProviderStats(
    val provider: String,
    val requestCount: Int,
    val totalTokens: Long,
    val totalCost: Double,
)

data class RemoteOwnerRow(
    val id: String = "owner",
    val username: String,
    val passwordHash: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class AuthSessionRow(
    val id: String,
    val ownerId: String = "owner",
    val tokenHash: String,
    val createdAt: Long,
    val expiresAt: Long,
    val lastSeenAt: Long,
    val revokedAt: Long? = null,
)

data class SecurityAuditLogRow(
    val eventType: String,
    val actor: String = "",
    val remoteAddress: String = "",
    val detail: String = "",
    val createdAt: Long,
)

data class OAuthAuthorizationRow(
    val stateHash: String,
    val ownerId: String,
    val provider: String,
    val redirectUri: String,
    val encryptedVerifier: String,
    val expiresAt: Long,
    val consumedAt: Long? = null,
)

data class OAuthConnectionRow(
    val ownerId: String,
    val provider: String,
    val encryptedTokens: String,
    val expiresAt: Long,
    val updatedAt: Long,
)

data class McpServerConfigRow(
    val id: String,
    val configJson: String,
    val encryptedSecrets: String,
    val createdAt: Long,
    val updatedAt: Long,
)
