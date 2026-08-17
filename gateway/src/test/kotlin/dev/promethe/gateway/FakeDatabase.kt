package dev.promethe.gateway

import dev.promethe.db.*

/**
 * In-memory fake implementation of PrometheDatabaseApi for testing.
 * All data lives in mutable lists — no SQLite, no Exposed.
 */
class FakeDatabase : PrometheDatabaseApi {
    // ── Storage ──
    private val sessions = mutableListOf<SessionRow>()
    private val projects = mutableListOf<ProjectRow>()
    private val messages = mutableListOf<MessageRow>()
    private val feedbacks = mutableListOf<FeedbackRow>()
    private val agentProfiles = mutableListOf<AgentProfileRow>()
    private val checkpoints = mutableMapOf<String, String>()
    private val userFacts = mutableListOf<UserFactRow>()
    private val scheduledTasks = mutableListOf<ScheduledTaskRow>()
    private val webhookChannels = mutableListOf<WebhookChannelRow>()
    private var messageIdCounter = 1
    private var factIdCounter = 1

    // ── Projects ──
    override suspend fun insertProject(project: ProjectRow) {
        projects += project
    }

    override suspend fun updateProject(project: ProjectRow) {
        val index = projects.indexOfFirst { it.id == project.id }
        if (index >= 0) projects[index] = project
    }

    override suspend fun getProject(id: String): ProjectRow? = projects.find { it.id == id }

    override suspend fun getAllProjects(): List<ProjectRow> = projects.toList()

    override suspend fun getProjectSessionCounts(): Map<String, Int> = sessions.mapNotNull(SessionRow::projectId).groupingBy { it }.eachCount()

    // ── Sessions ──
    override suspend fun insertSession(
        id: String,
        createdAt: Long,
        metadata: String?,
    ) {
        sessions.add(SessionRow(id, createdAt, metadata))
    }

    override suspend fun insertSessionOrIgnore(
        id: String,
        createdAt: Long,
        metadata: String?,
    ) {
        if (sessions.none { it.id == id }) sessions.add(SessionRow(id, createdAt, metadata))
    }

    override suspend fun getAllSessions(): List<SessionRow> = sessions.toList()

    override suspend fun getSession(id: String): SessionRow? = sessions.find { it.id == id }

    override suspend fun assignSessionToProject(
        sessionId: String,
        projectId: String?,
    ) {
        val index = sessions.indexOfFirst { it.id == sessionId }
        if (index >= 0) sessions[index] = sessions[index].copy(projectId = projectId)
    }

    override suspend fun deleteSession(id: String) {
        sessions.removeAll { it.id == id }
    }

    override suspend fun getSessionMessageCounts(): Map<String, Int> = messages.groupBy { it.sessionId }.mapValues { it.value.size }

    override suspend fun updateSessionTitle(
        id: String,
        title: String,
    ) {
        val idx = sessions.indexOfFirst { it.id == id }
        if (idx >= 0) sessions[idx] = sessions[idx].copy(title = title)
    }

    override suspend fun getRecentSessions(
        userId: String,
        limit: Int,
    ): List<SessionSummary> =
        sessions.takeLast(limit).map {
            SessionSummary(it.id, messages.count { m -> m.sessionId == it.id }, "")
        }

    // ── Messages ──
    override suspend fun insertMessage(
        sessionId: String,
        role: String,
        content: String,
        timestamp: Long,
    ): Int {
        val id = messageIdCounter++
        messages.add(MessageRow(id, sessionId, role, content, timestamp))
        return id
    }

    override suspend fun getMessagesForSession(sessionId: String): List<MessageRow> = messages.filter { it.sessionId == sessionId }

    override suspend fun searchMessages(query: String): List<MessageRow> = messages.filter { it.content.contains(query, ignoreCase = true) }

    // ── Feedbacks ──
    override suspend fun insertFeedback(
        sessionId: String,
        score: Double,
        comment: String?,
        timestamp: Long,
    ) {
        feedbacks.add(FeedbackRow(feedbacks.size + 1, sessionId, score, comment, timestamp))
    }

    override suspend fun getFeedbackForSession(sessionId: String): List<FeedbackRow> = feedbacks.filter { it.sessionId == sessionId }

    override suspend fun getAverageFeedback(): FeedbackStats {
        val avg = if (feedbacks.isEmpty()) 0.0 else feedbacks.map { it.score }.average()
        return FeedbackStats(avg, feedbacks.size.toLong())
    }

    override suspend fun getRecentPositiveSessions(limit: Int): List<String> = feedbacks.filter { it.score >= 4.0 }.map { it.sessionId }.distinct().takeLast(limit)

    // ── Agent Profiles ──
    override suspend fun insertAgentProfile(profile: AgentProfileRow) {
        agentProfiles.add(profile)
    }

    override suspend fun updateAgentProfile(profile: AgentProfileRow) {
        val idx = agentProfiles.indexOfFirst { it.id == profile.id }
        if (idx >= 0) agentProfiles[idx] = profile
    }

    override suspend fun upsertAgentProfile(profile: AgentProfileRow) {
        val idx = agentProfiles.indexOfFirst { it.id == profile.id }
        if (idx >= 0) agentProfiles[idx] = profile else agentProfiles.add(profile)
    }

    override suspend fun deleteAgentProfile(id: String) {
        agentProfiles.removeAll { it.id == id }
    }

    override suspend fun getAgentProfile(id: String): AgentProfileRow? = agentProfiles.find { it.id == id }

    override suspend fun getAllAgentProfiles(): List<AgentProfileRow> = agentProfiles.toList()

    override suspend fun getDefaultAgentProfile(): AgentProfileRow? = agentProfiles.find { it.isSystem }

    override suspend fun setDefaultProfile(id: String) {
        // no-op: isSystem is set at seed time only
    }

    // ── Checkpoints ──
    override suspend fun insertCheckpoint(
        sessionId: String,
        stepIndex: Int,
        stateJson: String,
    ) {
        checkpoints[sessionId] = stateJson
    }

    override suspend fun getLatestCheckpoint(sessionId: String): String? = checkpoints[sessionId]

    override suspend fun clearCheckpoints(sessionId: String) {
        checkpoints.remove(sessionId)
    }

    // ── User Facts ──
    override suspend fun insertUserFact(fact: UserFactRow) {
        userFacts.add(fact.copy(id = factIdCounter++))
    }

    override suspend fun getAllUserFacts(userId: String): List<UserFactRow> = userFacts.filter { it.userId == userId }

    override suspend fun searchUserFacts(query: String): List<UserFactRow> = userFacts.filter { it.fact.contains(query, ignoreCase = true) }

    override suspend fun deleteUserFact(id: Int) {
        userFacts.removeAll { it.id == id }
    }

    // ── Scheduled Tasks ──
    override suspend fun insertScheduledTask(task: ScheduledTaskRow) {
        scheduledTasks.add(task)
    }

    override suspend fun updateScheduledTask(task: ScheduledTaskRow) {
        val idx = scheduledTasks.indexOfFirst { it.id == task.id }
        if (idx >= 0) scheduledTasks[idx] = task
    }

    override suspend fun deleteScheduledTask(id: String) {
        scheduledTasks.removeAll { it.id == id }
    }

    override suspend fun getScheduledTask(id: String): ScheduledTaskRow? = scheduledTasks.find { it.id == id }

    override suspend fun getAllScheduledTasks(): List<ScheduledTaskRow> = scheduledTasks.toList()

    override suspend fun getEnabledScheduledTasks(): List<ScheduledTaskRow> = scheduledTasks.filter { it.enabled }

    override suspend fun updateScheduledTaskRun(
        id: String,
        lastRunAt: Long,
        nextRunAt: Long?,
        status: String,
    ) {
        val idx = scheduledTasks.indexOfFirst { it.id == id }
        if (idx >= 0) {
            scheduledTasks[idx] = scheduledTasks[idx].copy(
                lastRunAt = lastRunAt,
                nextRunAt = nextRunAt,
                lastRunStatus = status,
            )
        }
    }

    // ── Webhook Channels ──
    override suspend fun insertWebhookChannel(channel: WebhookChannelRow) {
        webhookChannels.add(channel)
    }

    override suspend fun updateWebhookChannel(channel: WebhookChannelRow) {
        val idx = webhookChannels.indexOfFirst { it.id == channel.id }
        if (idx >= 0) webhookChannels[idx] = channel
    }

    override suspend fun deleteWebhookChannel(id: String) {
        webhookChannels.removeAll { it.id == id }
    }

    override suspend fun getWebhookChannel(name: String): WebhookChannelRow? = webhookChannels.find { it.name == name }

    override suspend fun getWebhookChannelById(id: String): WebhookChannelRow? = webhookChannels.find { it.id == id }

    override suspend fun getAllWebhookChannels(): List<WebhookChannelRow> = webhookChannels.toList()

    // ── Settings ──
    private val settings = mutableMapOf<String, String>()

    override suspend fun getSetting(key: String): String? = settings[key]

    override suspend fun upsertSetting(
        key: String,
        value: String,
    ) {
        settings[key] = value
    }

    override suspend fun deleteSetting(key: String) {
        settings.remove(key)
    }

    override suspend fun getAllSettings(): Map<String, String> = settings.toMap()

    // ── Sessions metadata ──
    override suspend fun updateSessionMetadata(
        id: String,
        metadata: String,
    ) {
        val idx = sessions.indexOfFirst { it.id == id }
        if (idx >= 0) sessions[idx] = sessions[idx].copy(metadata = metadata)
    }

    // ── LLM Usage Logs (stubs) ──
    private val llmUsageLogs = mutableListOf<LlmUsageLogRow>()

    override suspend fun insertLlmUsageLog(log: LlmUsageLogRow) {
        llmUsageLogs.add(log)
    }

    override suspend fun getAggregatedLlmStats(): LlmAggregatedStats =
        LlmAggregatedStats(
            totalRequests = llmUsageLogs.size,
            totalPromptTokens = llmUsageLogs.sumOf { it.promptTokens.toLong() },
            totalCompletionTokens = llmUsageLogs.sumOf { it.completionTokens.toLong() },
            totalCost = llmUsageLogs.sumOf { it.cost },
        )

    override suspend fun getLlmUsageByProvider(): Map<String, LlmProviderStats> =
        llmUsageLogs.groupBy { it.provider }.mapValues { (provider, logs) ->
            LlmProviderStats(
                provider = provider,
                requestCount = logs.size,
                totalTokens = logs.sumOf { (it.promptTokens + it.completionTokens).toLong() },
                totalCost = logs.sumOf { it.cost },
            )
        }
}
