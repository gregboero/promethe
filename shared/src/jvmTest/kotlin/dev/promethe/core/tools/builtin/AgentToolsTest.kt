package dev.promethe.core.tools.builtin

import dev.promethe.core.*
import dev.promethe.core.hooks.*
import dev.promethe.core.memory.*
import dev.promethe.core.tools.builtin.*
import dev.promethe.db.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class AgentToolsTest {
    private val fakeDatabase = FakePrometheDatabase()

    @Test
    fun testClarifyTool() =
        runTest {
            val tool = ClarifyTool()
            val result = tool.execute(ClarifyArgs(question = "What is your project?", suggestions = listOf("A", "B")))
            assertTrue(result.contains("[CLARIFY]"), "Result should contain CLARIFY marker")
            assertTrue(result.contains("Question: What is your project?"), "Result should contain question")
            assertTrue(result.contains("1. A"), "Result should list suggestions")
        }

    @Test
    fun testCronjobTool() =
        runTest {
            val db = FakePrometheDatabase()
            // Create dummy agent with default args
            val dummyAgent = createDummyAgent(db)
            val scheduler = TaskScheduler(db)

            val tool = CronjobTool(scheduler)

            // Create task
            val createResult = tool.execute(
                CronjobArgs(
                    cronExpression = "*/5 * * * *",
                    task = "Run self check",
                    jobId = "test_job",
                    action = "create",
                ),
            )
            assertTrue(createResult.contains("Cron job 'test_job' scheduled"), "Result should confirm scheduling")

            // List tasks
            val listResult = tool.execute(CronjobArgs(cronExpression = "", task = "", action = "list"))
            assertTrue(listResult.contains("test_job"), "List should contain scheduled job ID")

            // Delete task
            val deleteResult = tool.execute(CronjobArgs(cronExpression = "", task = "", jobId = "test_job", action = "delete"))
            assertTrue(deleteResult.contains("deleted"), "Delete result should confirm removal")
        }

    @Test
    fun testSendMessageTool() =
        runTest {
            val sender = object : ChannelSender {
                val sent = mutableListOf<Triple<String, String, String>>()

                override suspend fun send(
                    channel: String,
                    recipient: String,
                    message: String,
                ) {
                    sent.add(Triple(channel, recipient, message))
                }
            }
            val tool = SendMessageTool(sender)
            val result = tool.execute(SendMessageArgs(channel = "slack", recipient = "#general", message = "Hi team"))
            assertTrue(result.contains("Message sent"), "Result should indicate successful dispatch")
            assertEquals(1, sender.sent.size)
            assertEquals("slack", sender.sent[0].first)
            assertEquals("#general", sender.sent[0].second)
            assertEquals("Hi team", sender.sent[0].third)
        }

    @Test
    fun testSessionSearchTool() =
        runTest {
            val db = FakePrometheDatabase()
            db.insertMessage("session-1", "user", "Looking for Kotlin multiplatform guide", 1000)
            db.insertMessage("session-1", "assistant", "Sure, here is the info", 2000)
            db.insertMessage("session-2", "user", "Looking for Swift docs", 3000)

            val tool = SessionSearchTool(db)

            // Search Kotlin
            val result1 = tool.execute(SessionSearchArgs(query = "Kotlin"))
            assertTrue(result1.contains("session-1"), "Should find matching session ID")
            assertTrue(result1.contains("Looking for Kotlin"), "Should contain matched content preview")
            assertFalse(result1.contains("session-2"), "Should filter out non-matching session")
        }

    @Test
    fun testMixtureOfAgentsTool() =
        runTest {
            val config = AgentConfig()
            val adapter = object : KoogLlmAdapter(config) {
                override suspend fun complete(
                    systemPrompt: String,
                    messages: List<Pair<String, String>>,
                    model: String,
                    temperature: Double,
                ): LlmResponse {
                    val content = when (model) {
                        "nousresearch/hermes-3-llama-3.1-405b" -> "Hermes answer"
                        "anthropic/claude-3.5-sonnet" -> "Claude answer"
                        "openai/gpt-4o" -> "GPT answer"
                        else -> "Consensus synthesized answer"
                    }
                    return LlmResponse(content, 10, 10, model, "mock-provider")
                }
            }

            val tool = MixtureOfAgentsTool(adapter, config)
            val result = tool.execute(MixtureOfAgentsArgs(prompt = "Kotlin vs Java", strategy = "vote"))
            assertTrue(
                result.contains("Consensus synthesized answer") || result.contains("Hermes answer") || result.contains("Claude answer") ||
                    result.contains("GPT answer"),
            )
        }

    private fun createDummyAgent(db: PrometheDatabaseApi): AIAgent {
        val config = AgentConfig()
        val llm = KoogLlmAdapter(config, db)
        val pm = ProfileManager(null)
        val ae = ActionExecutor(config, io.ktor.client.HttpClient(), hookManager = HookManager())
        val sl = SkillLoader(getFileSystem(), getProfileDirectoryPath(config))
        val sw = SkillWriter(getFileSystem(), getProfileDirectoryPath(config))
        val te = TrajectoryEvaluator(llm, config)
        val ml = MemoryLayer(db, llm, EmbeddedMemoryProvider(db))
        val rs = RewardSignal()
        val res = ResilienceStrategy(llm)
        val hm = HookManager()
        val cc = ContextCompressor(llm, config)
        return AIAgent(
            config = config,
            database = db,
            llmAdapter = llm,
            profileManager = pm,
            actionExecutor = ae,
            skillLoader = sl,
            trajectoryEvaluator = te,
            skillWriter = sw,
            memoryLayer = ml,
            rewardSignal = rs,
            resilience = res,
            hookManager = hm,
            contextCompressor = cc,
        )
    }
}

// ── Shared Fake Database Helper ──
class FakePrometheDatabase : PrometheDatabaseApi {
    val sessions = mutableListOf<SessionRow>()
    val messages = mutableListOf<MessageRow>()
    val feedbacks = mutableListOf<FeedbackRow>()
    val profiles = mutableMapOf<String, AgentProfileRow>()
    val facts = mutableListOf<UserFactRow>()
    val tasks = mutableMapOf<String, ScheduledTaskRow>()
    val webhooks = mutableMapOf<String, WebhookChannelRow>()
    var defaultProfileId: String? = null
    val settings = mutableMapOf<String, String>()

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
        if (sessions.none { it.id == id }) {
            insertSession(id, createdAt, metadata)
        }
    }

    override suspend fun getAllSessions(): List<SessionRow> = sessions

    override suspend fun deleteSession(id: String) {
        sessions.removeIf { it.id == id }
    }

    override suspend fun getSessionMessageCounts(): Map<String, Int> = messages.groupBy { it.sessionId }.mapValues { it.value.size }

    override suspend fun updateSessionTitle(
        id: String,
        title: String,
    ) {
        val idx = sessions.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val old = sessions[idx]
            sessions[idx] = SessionRow(old.id, old.createdAt, old.metadata, title)
        }
    }

    override suspend fun updateSessionMetadata(
        id: String,
        metadata: String,
    ) {
        val idx = sessions.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val old = sessions[idx]
            sessions[idx] = SessionRow(old.id, old.createdAt, metadata, old.title)
        }
    }

    override suspend fun getRecentSessions(
        userId: String,
        limit: Int,
    ): List<SessionSummary> =
        sessions.take(limit).map {
            SessionSummary(it.id, 0, "")
        }

    override suspend fun insertMessage(
        sessionId: String,
        role: String,
        content: String,
        timestamp: Long,
    ): Int {
        val nextId = messages.size + 1
        messages.add(MessageRow(nextId, sessionId, role, content, timestamp))
        return nextId
    }

    override suspend fun getMessagesForSession(sessionId: String): List<MessageRow> = messages.filter { it.sessionId == sessionId }

    override suspend fun searchMessages(query: String): List<MessageRow> = messages.filter { it.content.contains(query, ignoreCase = true) }

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

    override suspend fun getRecentPositiveSessions(limit: Int): List<String> =
        feedbacks.filter {
            it.score >= 4.0
        }.map { it.sessionId }.distinct().take(limit)

    override suspend fun insertAgentProfile(profile: AgentProfileRow) {
        profiles[profile.id] = profile
    }

    override suspend fun updateAgentProfile(profile: AgentProfileRow) {
        profiles[profile.id] = profile
    }

    override suspend fun upsertAgentProfile(profile: AgentProfileRow) {
        profiles[profile.id] = profile
    }

    override suspend fun deleteAgentProfile(id: String) {
        profiles.remove(id)
    }

    override suspend fun getAgentProfile(id: String): AgentProfileRow? = profiles[id]

    override suspend fun getAllAgentProfiles(): List<AgentProfileRow> = profiles.values.toList()

    override suspend fun getDefaultAgentProfile(): AgentProfileRow? = profiles[defaultProfileId]

    override suspend fun setDefaultProfile(id: String) {
        defaultProfileId = id
    }

    override suspend fun insertCheckpoint(
        sessionId: String,
        stepIndex: Int,
        stateJson: String,
    ) {}

    override suspend fun getLatestCheckpoint(sessionId: String): String? = null

    override suspend fun clearCheckpoints(sessionId: String) {}

    override suspend fun insertUserFact(fact: UserFactRow) {
        facts.add(fact)
    }

    override suspend fun getAllUserFacts(userId: String): List<UserFactRow> = facts

    override suspend fun searchUserFacts(query: String): List<UserFactRow> = facts.filter { it.fact.contains(query, ignoreCase = true) }

    override suspend fun deleteUserFact(id: Int) {
        facts.removeIf { it.id == id }
    }

    override suspend fun insertScheduledTask(task: ScheduledTaskRow) {
        tasks[task.id] = task
    }

    override suspend fun updateScheduledTask(task: ScheduledTaskRow) {
        tasks[task.id] = task
    }

    override suspend fun deleteScheduledTask(id: String) {
        tasks.remove(id)
    }

    override suspend fun getScheduledTask(id: String): ScheduledTaskRow? = tasks[id]

    override suspend fun getAllScheduledTasks(): List<ScheduledTaskRow> = tasks.values.toList()

    override suspend fun getEnabledScheduledTasks(): List<ScheduledTaskRow> = tasks.values.filter { it.enabled }

    override suspend fun updateScheduledTaskRun(
        id: String,
        lastRunAt: Long,
        nextRunAt: Long?,
        status: String,
    ) {
        val t = tasks[id]
        if (t != null) {
            tasks[id] = t.copy(lastRunAt = lastRunAt, nextRunAt = nextRunAt, lastRunStatus = status)
        }
    }

    override suspend fun insertWebhookChannel(channel: WebhookChannelRow) {
        webhooks[channel.id] = channel
    }

    override suspend fun updateWebhookChannel(channel: WebhookChannelRow) {
        webhooks[channel.id] = channel
    }

    override suspend fun deleteWebhookChannel(id: String) {
        webhooks.remove(id)
    }

    override suspend fun getWebhookChannel(name: String): WebhookChannelRow? = webhooks.values.firstOrNull { it.name == name }

    override suspend fun getWebhookChannelById(id: String): WebhookChannelRow? = webhooks[id]

    override suspend fun getAllWebhookChannels(): List<WebhookChannelRow> = webhooks.values.toList()

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

    override suspend fun getAllSettings(): Map<String, String> = settings

    // ── LLM Usage Logs (stub for tests) ──
    private val llmLogs = mutableListOf<LlmUsageLogRow>()

    override suspend fun insertLlmUsageLog(log: LlmUsageLogRow) {
        llmLogs.add(log)
    }

    override suspend fun getAggregatedLlmStats(): LlmAggregatedStats =
        LlmAggregatedStats(
            totalRequests = llmLogs.size,
            totalPromptTokens = llmLogs.sumOf { it.promptTokens.toLong() },
            totalCompletionTokens = llmLogs.sumOf { it.completionTokens.toLong() },
            totalCost = llmLogs.sumOf { it.cost },
        )

    override suspend fun getLlmUsageByProvider(): Map<String, LlmProviderStats> =
        llmLogs.groupBy { it.provider }.mapValues { (provider, logs) ->
            LlmProviderStats(
                provider = provider,
                requestCount = logs.size,
                totalTokens = logs.sumOf { (it.promptTokens + it.completionTokens).toLong() },
                totalCost = logs.sumOf { it.cost },
            )
        }
}
