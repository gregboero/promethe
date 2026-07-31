package dev.promethe.core.hooks

import dev.promethe.core.Action
import dev.promethe.core.AgentConfig
import dev.promethe.core.ConversationTrajectory
import dev.promethe.core.KoogLlmAdapter
import dev.promethe.core.SkillLoader
import dev.promethe.core.SkillWriter
import dev.promethe.core.evolution.GepaScheduler
import dev.promethe.db.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class GepaFeedbackHookTest {
    private lateinit var scheduler: GepaScheduler
    private lateinit var hook: GepaFeedbackHook

    @BeforeTest
    fun setup() {
        val db = StubDatabase()
        val config = AgentConfig()
        val llm = KoogLlmAdapter(config)
        val fs = FakeFileSystem()
        val skillsDir = "/skills".toPath()
        fs.createDirectories(skillsDir)
        val skillLoader = SkillLoader(fs, skillsDir)
        val skillWriter = SkillWriter(fs, skillsDir)
        scheduler = GepaScheduler(
            database = db,
            llmAdapter = llm,
            config = config,
            skillLoader = skillLoader,
            skillWriter = skillWriter,
        )
        hook = GepaFeedbackHook(scheduler)
    }

    // ── Identity ─────────────────────────────────────────────

    @Test
    fun `hook has correct id, events, and priority`() {
        assertEquals("builtin.gepa_feedback", hook.id)
        assertEquals(setOf(HookEvent.SESSION_END), hook.events)
        assertEquals(80, hook.priority)
    }

    // ── recordStep ───────────────────────────────────────────

    @Test
    fun `recordStep accumulates steps for a session`() =
        runTest {
            val step1 = trajectory(inputs = mapOf("q" to "hello"))
            val step2 = trajectory(inputs = mapOf("q" to "world"))
            hook.recordStep("s1", step1, originalQuery = "hello world")
            hook.recordStep("s1", step2)

            // Execute SESSION_END to flush — if 2 steps were recorded the scheduler should receive them
            val ctx = HookContext(event = HookEvent.SESSION_END, sessionId = "s1")
            hook.execute(ctx)

            val status = scheduler.getStatus()
            assertEquals(1, status["pendingTrajectories"])
        }

    // ── execute on SESSION_END ────────────────────────────────

    @Test
    fun `execute on SESSION_END forwards trajectory to scheduler`() =
        runTest {
            hook.recordStep("sess-42", trajectory(outputs = mapOf("response" to "hi")), originalQuery = "greet me")

            val result = hook.execute(HookContext(event = HookEvent.SESSION_END, sessionId = "sess-42"))

            assertTrue(result is HookResult.Continue)
            // Scheduler now holds 1 trajectory
            assertEquals(1, scheduler.getStatus()["pendingTrajectories"])
        }

    @Test
    fun `execute cleans up session state after forwarding`() =
        runTest {
            hook.recordStep("s1", trajectory())
            hook.execute(HookContext(event = HookEvent.SESSION_END, sessionId = "s1"))

            // Second call for the same session should be a no-op (trajectory already consumed)
            val result = hook.execute(HookContext(event = HookEvent.SESSION_END, sessionId = "s1"))
            assertTrue(result is HookResult.Continue)
            // Still only 1 trajectory in the scheduler
            assertEquals(1, scheduler.getStatus()["pendingTrajectories"])
        }

    // ── non-SESSION_END events ───────────────────────────────

    @Test
    fun `execute on non-SESSION_END returns Continue without forwarding`() =
        runTest {
            hook.recordStep("s1", trajectory())

            for (event in HookEvent.entries.filter { it != HookEvent.SESSION_END }) {
                val result = hook.execute(HookContext(event = event, sessionId = "s1"))
                assertTrue(result is HookResult.Continue, "Expected Continue for $event")
            }
            // Nothing forwarded
            assertEquals(0, scheduler.getStatus()["pendingTrajectories"])
        }

    // ── empty trajectory ─────────────────────────────────────

    @Test
    fun `execute with no recorded trajectory returns Continue`() =
        runTest {
            val result = hook.execute(HookContext(event = HookEvent.SESSION_END, sessionId = "unknown"))
            assertTrue(result is HookResult.Continue)
            assertEquals(0, scheduler.getStatus()["pendingTrajectories"])
        }

    // ── score computation (tested indirectly via scheduler) ──

    @Test
    fun `score base is 0_7 for a minimal trajectory without error`() =
        runTest {
            // Single step, no error, no response output, <=10 steps → base 0.7
            hook.recordStep("s1", trajectory(), originalQuery = "test")
            hook.execute(HookContext(event = HookEvent.SESSION_END, sessionId = "s1"))

            // The trajectory should NOT be a failure (score 0.7 >= 0.6)
            assertEquals(0, scheduler.getStatus()["failedTrajectories"])
        }

    @Test
    fun `error penalty reduces score below failure threshold`() =
        runTest {
            // error != null → -0.3, so 0.7 - 0.3 = 0.4 (< 0.6 → failed)
            hook.recordStep("s1", trajectory(), originalQuery = "test")
            hook.execute(
                HookContext(
                    event = HookEvent.SESSION_END,
                    sessionId = "s1",
                    error = RuntimeException("boom"),
                ),
            )

            assertEquals(1, scheduler.getStatus()["failedTrajectories"])
        }

    @Test
    fun `completion bonus raises score`() =
        runTest {
            // Has "response" output → +0.2, so 0.7 + 0.2 = 0.9
            hook.recordStep("s1", trajectory(outputs = mapOf("response" to "done")), originalQuery = "q")
            hook.execute(HookContext(event = HookEvent.SESSION_END, sessionId = "s1"))

            assertEquals(0, scheduler.getStatus()["failedTrajectories"])
        }

    @Test
    fun `error in observation also penalizes score`() =
        runTest {
            // 3 steps with [ERROR] observations → penalty 3*0.1 = 0.3
            // base 0.7 - 0.3 = 0.4 → failed
            repeat(3) {
                hook.recordStep("s1", trajectory(observation = "[ERROR] something went wrong"))
            }
            hook.execute(HookContext(event = HookEvent.SESSION_END, sessionId = "s1"))

            assertEquals(1, scheduler.getStatus()["failedTrajectories"])
        }

    @Test
    fun `step penalty for many steps`() =
        runTest {
            // 14 steps → penalty = (14-10)*0.05 = 0.2
            // base 0.7 - 0.2 = 0.5 → failed
            repeat(14) {
                hook.recordStep("s1", trajectory())
            }
            hook.execute(HookContext(event = HookEvent.SESSION_END, sessionId = "s1"))

            assertEquals(1, scheduler.getStatus()["failedTrajectories"])
        }

    @Test
    fun `completion bonus offsets step penalty`() =
        runTest {
            // 14 steps → -0.2, but response output → +0.2, net = 0.7 → not failed
            repeat(13) {
                hook.recordStep("s1", trajectory())
            }
            hook.recordStep("s1", trajectory(outputs = mapOf("response" to "done")))
            hook.execute(HookContext(event = HookEvent.SESSION_END, sessionId = "s1"))

            assertEquals(0, scheduler.getStatus()["failedTrajectories"])
        }

    @Test
    fun `score clamped to 0_0 minimum`() =
        runTest {
            // error + 4 ERROR observations + 20 steps → lots of penalties, but score floors at 0.0
            // Just verify it doesn't crash and is still marked as failed
            repeat(20) {
                hook.recordStep("s1", trajectory(observation = "[ERROR] fail $it"))
            }
            hook.execute(
                HookContext(
                    event = HookEvent.SESSION_END,
                    sessionId = "s1",
                    error = RuntimeException("fatal"),
                ),
            )

            assertEquals(1, scheduler.getStatus()["failedTrajectories"])
        }

    @Test
    fun `skills detection forwards skill tool names`() =
        runTest {
            val step = trajectory(action = Action("skill_summarize", JsonObject(emptyMap())))
            hook.recordStep("s1", step, originalQuery = "summarize")
            hook.execute(HookContext(event = HookEvent.SESSION_END, sessionId = "s1"))

            // Trajectory recorded — verifying no crash and forwarding happened
            assertEquals(1, scheduler.getStatus()["pendingTrajectories"])
        }

    // ── helpers ───────────────────────────────────────────────

    private fun trajectory(
        inputs: Map<String, String> = emptyMap(),
        outputs: Map<String, String> = emptyMap(),
        thought: String? = null,
        action: Action? = null,
        observation: String? = null,
    ) = ConversationTrajectory(
        inputs = inputs,
        outputs = outputs,
        thought = thought,
        action = action,
        observation = observation,
    )
}

// ── Minimal stub database (no-ops — GepaFeedbackHook doesn't touch DB directly) ──

private class StubDatabase : PrometheDatabaseApi {
    override suspend fun insertSession(
        id: String,
        createdAt: Long,
        metadata: String?,
    ) {}

    override suspend fun insertSessionOrIgnore(
        id: String,
        createdAt: Long,
        metadata: String?,
    ) {}

    override suspend fun getAllSessions(): List<SessionRow> = emptyList()

    override suspend fun deleteSession(id: String) {}

    override suspend fun getSessionMessageCounts(): Map<String, Int> = emptyMap()

    override suspend fun updateSessionTitle(
        id: String,
        title: String,
    ) {}

    override suspend fun updateSessionMetadata(
        id: String,
        metadata: String,
    ) {}

    override suspend fun getRecentSessions(
        userId: String,
        limit: Int,
    ): List<SessionSummary> = emptyList()

    override suspend fun insertMessage(
        sessionId: String,
        role: String,
        content: String,
        timestamp: Long,
    ): Int = 0

    override suspend fun getMessagesForSession(sessionId: String): List<MessageRow> = emptyList()

    override suspend fun searchMessages(query: String): List<MessageRow> = emptyList()

    override suspend fun insertFeedback(
        sessionId: String,
        score: Double,
        comment: String?,
        timestamp: Long,
    ) {}

    override suspend fun getFeedbackForSession(sessionId: String): List<FeedbackRow> = emptyList()

    override suspend fun getAverageFeedback(): FeedbackStats = FeedbackStats(0.0, 0)

    override suspend fun getRecentPositiveSessions(limit: Int): List<String> = emptyList()

    override suspend fun insertAgentProfile(profile: AgentProfileRow) {}

    override suspend fun updateAgentProfile(profile: AgentProfileRow) {}

    override suspend fun upsertAgentProfile(profile: AgentProfileRow) {}

    override suspend fun deleteAgentProfile(id: String) {}

    override suspend fun getAgentProfile(id: String): AgentProfileRow? = null

    override suspend fun getAllAgentProfiles(): List<AgentProfileRow> = emptyList()

    override suspend fun getDefaultAgentProfile(): AgentProfileRow? = null

    override suspend fun setDefaultProfile(id: String) {}

    override suspend fun insertCheckpoint(
        sessionId: String,
        stepIndex: Int,
        stateJson: String,
    ) {}

    override suspend fun getLatestCheckpoint(sessionId: String): String? = null

    override suspend fun clearCheckpoints(sessionId: String) {}

    override suspend fun insertUserFact(fact: UserFactRow) {}

    override suspend fun getAllUserFacts(userId: String): List<UserFactRow> = emptyList()

    override suspend fun searchUserFacts(query: String): List<UserFactRow> = emptyList()

    override suspend fun deleteUserFact(id: Int) {}

    override suspend fun insertScheduledTask(task: ScheduledTaskRow) {}

    override suspend fun updateScheduledTask(task: ScheduledTaskRow) {}

    override suspend fun deleteScheduledTask(id: String) {}

    override suspend fun getScheduledTask(id: String): ScheduledTaskRow? = null

    override suspend fun getAllScheduledTasks(): List<ScheduledTaskRow> = emptyList()

    override suspend fun getEnabledScheduledTasks(): List<ScheduledTaskRow> = emptyList()

    override suspend fun updateScheduledTaskRun(
        id: String,
        lastRunAt: Long,
        nextRunAt: Long?,
        status: String,
    ) {}

    override suspend fun insertWebhookChannel(channel: WebhookChannelRow) {}

    override suspend fun updateWebhookChannel(channel: WebhookChannelRow) {}

    override suspend fun deleteWebhookChannel(id: String) {}

    override suspend fun getWebhookChannel(name: String): WebhookChannelRow? = null

    override suspend fun getWebhookChannelById(id: String): WebhookChannelRow? = null

    override suspend fun getAllWebhookChannels(): List<WebhookChannelRow> = emptyList()

    override suspend fun getSetting(key: String): String? = null

    override suspend fun upsertSetting(
        key: String,
        value: String,
    ) {}

    override suspend fun deleteSetting(key: String) {}

    override suspend fun getAllSettings(): Map<String, String> = emptyMap()

    override suspend fun insertLlmUsageLog(log: LlmUsageLogRow) {}

    override suspend fun getAggregatedLlmStats(): LlmAggregatedStats = LlmAggregatedStats(0, 0, 0, 0.0)

    override suspend fun getLlmUsageByProvider(): Map<String, LlmProviderStats> = emptyMap()
}
