package dev.promethe.core

import dev.promethe.db.DatabaseFactory
import dev.promethe.db.PrometheDatabaseApi
import dev.promethe.core.memory.EmbeddedMemoryProvider
import dev.promethe.core.hooks.HookManager
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import java.time.LocalDateTime
import java.time.ZoneOffset

class TaskSchedulerTest {
    @Test
    fun testMatchesCron() {
        // Thursday 2026-06-11 14:35:00 UTC (dayOfWeek = 4)
        val ldt = LocalDateTime.of(2026, 6, 11, 14, 35, 0)
        val timestampMs = ldt.toEpochSecond(ZoneOffset.UTC) * 1000

        // Test wildcards
        assertTrue(TaskScheduler.matchesCron("* * * * *", timestampMs))

        // Test exact minutes matching
        assertTrue(TaskScheduler.matchesCron("35 * * * *", timestampMs))
        assertFalse(TaskScheduler.matchesCron("34 * * * *", timestampMs))

        // Test steps
        assertTrue(TaskScheduler.matchesCron("*/5 * * * *", timestampMs))
        assertFalse(TaskScheduler.matchesCron("*/6 * * * *", timestampMs))

        // Test lists
        assertTrue(TaskScheduler.matchesCron("30,35,40 * * * *", timestampMs))
        assertFalse(TaskScheduler.matchesCron("30,40 * * * *", timestampMs))

        // Test day of week (4 = Thursday)
        assertTrue(TaskScheduler.matchesCron("* * * * 4", timestampMs))
        assertFalse(TaskScheduler.matchesCron("* * * * 5", timestampMs))
    }

    @Test
    fun testComputeNextRun() {
        val ldt = LocalDateTime.of(2026, 6, 11, 14, 35, 0)
        val timestampMs = ldt.toEpochSecond(ZoneOffset.UTC) * 1000

        val nextRun = TaskScheduler.computeNextRun("*/5 * * * *", timestampMs)
        assertNotNull(nextRun)

        // Next execution should be at 14:40:00
        val nextLdt = LocalDateTime.ofEpochSecond(nextRun / 1000, 0, ZoneOffset.UTC)
        assertEquals(14, nextLdt.hour)
        assertEquals(40, nextLdt.minute)
    }

    @Test
    fun testSchedulerLifecycle() =
        runTest {
            val db = DatabaseFactory.createInMemory()
            val agent = createDummyAgent(db)
            val scheduler = TaskScheduler(db)

            // Schedule
            scheduler.schedule("job-1", "*/5 * * * *", "Run self tests")
            val jobs = scheduler.listJobs()
            assertEquals(1, jobs.size)
            assertEquals("job-1", jobs[0].id)
            assertEquals("*/5 * * * *", jobs[0].cronExpression)
            assertEquals("Run self tests", jobs[0].description)

            // Cancel
            assertTrue(scheduler.cancel("job-1"))
            assertEquals(0, scheduler.listJobs().size)
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
