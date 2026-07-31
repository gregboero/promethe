package dev.promethe.core

import dev.promethe.core.tools.builtin.FakePrometheDatabase
import dev.promethe.db.AgentProfileRow
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class AgentManagementToolsTest {
    // ══════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════

    private fun freshDb() = FakePrometheDatabase()

    private fun systemProfile(
        id: String,
        name: String = id,
    ) = AgentProfileRow(
        id = id,
        name = name,
        isSystem = true,
        ephemeral = false,
        createdAt = 1000L,
    )

    private fun customProfile(
        id: String,
        name: String = id,
    ) = AgentProfileRow(
        id = id,
        name = name,
        isSystem = false,
        ephemeral = false,
        createdAt = 1000L,
    )

    private fun ephemeralProfile(
        id: String,
        name: String = id,
        createdAt: Long = 1000L,
    ) = AgentProfileRow(
        id = id,
        name = name,
        isSystem = false,
        ephemeral = true,
        createdAt = createdAt,
    )

    // ══════════════════════════════════════════════════════════════
    // ListAgentsTool
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `list agents returns empty when no profiles`() =
        runTest {
            val db = freshDb()
            val tool = ListAgentsTool(db)
            val result = tool.execute(ListAgentsArgs())
            assertTrue(result.contains("No agent profiles found"), "Expected empty message, got: $result")
        }

    @Test
    fun `list agents returns all profiles`() =
        runTest {
            val db = freshDb()
            db.insertAgentProfile(systemProfile("sys"))
            db.insertAgentProfile(customProfile("custom"))
            db.insertAgentProfile(ephemeralProfile("temp"))

            val tool = ListAgentsTool(db)
            val result = tool.execute(ListAgentsArgs())
            assertTrue(result.contains("Available agents (3)"), "Expected 3 agents, got: $result")
            assertTrue(result.contains("sys"), result)
            assertTrue(result.contains("custom"), result)
            assertTrue(result.contains("temp"), result)
        }

    @Test
    fun `list agents filters system only`() =
        runTest {
            val db = freshDb()
            db.insertAgentProfile(systemProfile("sys"))
            db.insertAgentProfile(customProfile("custom"))
            db.insertAgentProfile(ephemeralProfile("temp"))

            val tool = ListAgentsTool(db)
            val result = tool.execute(ListAgentsArgs(filter = "system"))
            assertTrue(result.contains("Available agents (1)"), result)
            assertTrue(result.contains("sys"), result)
            assertFalse(result.contains("• custom"), result)
            assertFalse(result.contains("• temp"), result)
        }

    @Test
    fun `list agents filters ephemeral only`() =
        runTest {
            val db = freshDb()
            db.insertAgentProfile(systemProfile("sys"))
            db.insertAgentProfile(customProfile("custom"))
            db.insertAgentProfile(ephemeralProfile("temp"))

            val tool = ListAgentsTool(db)
            val result = tool.execute(ListAgentsArgs(filter = "ephemeral"))
            assertTrue(result.contains("Available agents (1)"), result)
            assertTrue(result.contains("temp"), result)
            assertFalse(result.contains("• sys"), result)
        }

    @Test
    fun `list agents filters custom only`() =
        runTest {
            val db = freshDb()
            db.insertAgentProfile(systemProfile("sys"))
            db.insertAgentProfile(customProfile("custom"))
            db.insertAgentProfile(ephemeralProfile("temp"))

            val tool = ListAgentsTool(db)
            val result = tool.execute(ListAgentsArgs(filter = "custom"))
            assertTrue(result.contains("Available agents (1)"), result)
            assertTrue(result.contains("custom"), result)
            assertFalse(result.contains("• sys"), result)
            assertFalse(result.contains("• temp"), result)
        }

    @Test
    fun `list agents shows correct badges`() =
        runTest {
            val db = freshDb()
            db.insertAgentProfile(systemProfile("sys"))
            db.insertAgentProfile(customProfile("my-agent"))
            db.insertAgentProfile(ephemeralProfile("temp"))

            val tool = ListAgentsTool(db)
            val result = tool.execute(ListAgentsArgs())
            assertTrue(result.contains("🔒 system"), "Expected system badge, got: $result")
            assertTrue(result.contains("⏳ ephemeral"), "Expected ephemeral badge, got: $result")
            assertTrue(result.contains("custom"), "Expected custom badge, got: $result")
        }

    // ══════════════════════════════════════════════════════════════
    // CreateAgentTool
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `create agent succeeds with minimal args`() =
        runTest {
            val db = freshDb()
            val tool = CreateAgentTool(db)
            val result = tool.execute(CreateAgentArgs(id = "my-agent"))
            assertTrue(result.contains("Agent Created"), "Expected success, got: $result")
            assertTrue(result.contains("my-agent"), result)
            assertNotNull(db.getAgentProfile("my-agent"), "Profile should be persisted")
        }

    @Test
    fun `create agent rejects blank id`() =
        runTest {
            val db = freshDb()
            val tool = CreateAgentTool(db)
            val result = tool.execute(CreateAgentArgs(id = "   "))
            assertTrue(result.contains("[ERROR]"), "Expected error for blank id, got: $result")
        }

    @Test
    fun `create agent rejects duplicate id`() =
        runTest {
            val db = freshDb()
            db.insertAgentProfile(customProfile("existing"))
            val tool = CreateAgentTool(db)
            val result = tool.execute(CreateAgentArgs(id = "existing"))
            assertTrue(result.contains("[ERROR]"), "Expected error for duplicate, got: $result")
            assertTrue(result.contains("already exists"), result)
        }

    @Test
    fun `create agent normalizes id`() =
        runTest {
            val db = freshDb()
            val tool = CreateAgentTool(db)
            val result = tool.execute(CreateAgentArgs(id = "My Cool Agent!"))
            assertTrue(result.contains("Agent Created"), "Expected success, got: $result")
            // Normalization: lowercase, spaces→dashes, strip special chars
            val profile = db.getAgentProfile("my-cool-agent")
            assertNotNull(profile, "Profile should be stored with normalized id 'my-cool-agent'")
        }

    @Test
    fun `create agent sets ephemeral flag`() =
        runTest {
            val db = freshDb()
            val tool = CreateAgentTool(db)
            val result = tool.execute(CreateAgentArgs(id = "temp-agent", ephemeral = true))
            assertTrue(result.contains("EPHEMERAL"), "Expected ephemeral mode, got: $result")
            // Ephemeral agents get a UUID suffix appended — extract actual ID from result
            val idMatch = Regex("""ID:\s*([a-z0-9_-]+)""").find(result)
            val actualId = idMatch?.groupValues?.get(1)
            assertNotNull(actualId, "Should be able to extract generated ID from: $result")
            val profile = db.getAgentProfile(actualId)
            assertNotNull(profile, "Profile should exist with generated id '$actualId'")
            assertTrue(profile.ephemeral, "Profile should be ephemeral")
        }

    @Test
    fun `create agent inherits from parent profile`() =
        runTest {
            val db = freshDb()
            db.insertAgentProfile(
                AgentProfileRow(
                    id = "parent",
                    name = "Parent Agent",
                    provider = "anthropic",
                    model = "claude-sonnet-4-20250514",
                    systemPrompt = "You are an expert.",
                    tools = "search,read_file",
                    maxIterations = 20,
                    temperature = 0.5,
                ),
            )

            val tool = CreateAgentTool(db)
            tool.execute(CreateAgentArgs(id = "child", parentProfileId = "parent"))
            val child = db.getAgentProfile("child")!!
            assertEquals("anthropic", child.provider, "Should inherit provider")
            assertEquals("claude-sonnet-4-20250514", child.model, "Should inherit model")
            assertEquals("You are an expert.", child.systemPrompt, "Should inherit systemPrompt")
            assertEquals("search,read_file", child.tools, "Should inherit tools")
            assertEquals(20, child.maxIterations, "Should inherit maxIterations")
            assertEquals(0.5, child.temperature, "Should inherit temperature")
        }

    @Test
    fun `create agent overrides parent fields`() =
        runTest {
            val db = freshDb()
            db.insertAgentProfile(
                AgentProfileRow(
                    id = "parent",
                    name = "Parent",
                    provider = "openai",
                    model = "gpt-4o",
                    temperature = 0.3,
                    maxIterations = 15,
                ),
            )

            val tool = CreateAgentTool(db)
            tool.execute(
                CreateAgentArgs(
                    id = "child",
                    parentProfileId = "parent",
                    provider = "google",
                    model = "gemini-pro",
                    temperature = 0.8,
                    maxIterations = 5,
                ),
            )
            val child = db.getAgentProfile("child")!!
            assertEquals("google", child.provider, "Should override provider")
            assertEquals("gemini-pro", child.model, "Should override model")
            assertEquals(0.8, child.temperature, "Should override temperature")
            assertEquals(5, child.maxIterations, "Should override maxIterations")
        }

    @Test
    fun `create agent returns error for nonexistent parent`() =
        runTest {
            val db = freshDb()
            val tool = CreateAgentTool(db)
            val result = tool.execute(CreateAgentArgs(id = "orphan", parentProfileId = "ghost"))
            assertTrue(result.contains("[ERROR]"), "Expected error, got: $result")
            assertTrue(result.contains("not found"), result)
        }

    @Test
    fun `create agent is never system`() =
        runTest {
            val db = freshDb()
            val tool = CreateAgentTool(db)
            tool.execute(CreateAgentArgs(id = "sneaky"))
            val profile = db.getAgentProfile("sneaky")!!
            assertFalse(profile.isSystem, "Created agents must never be system agents")
        }

    // ══════════════════════════════════════════════════════════════
    // PromoteAgentTool
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `promote ephemeral agent to permanent`() =
        runTest {
            val db = freshDb()
            db.insertAgentProfile(ephemeralProfile("temp-worker", "Temp Worker"))

            val tool = PromoteAgentTool(db)
            val result = tool.execute(PromoteAgentArgs(id = "temp-worker"))
            assertTrue(result.contains("Agent Promoted"), "Expected promotion, got: $result")
            assertTrue(result.contains("PERMANENT"), result)
            val profile = db.getAgentProfile("temp-worker")!!
            assertFalse(profile.ephemeral, "Should no longer be ephemeral")
        }

    @Test
    fun `promote already permanent agent is noop`() =
        runTest {
            val db = freshDb()
            db.insertAgentProfile(customProfile("permanent-agent"))

            val tool = PromoteAgentTool(db)
            val result = tool.execute(PromoteAgentArgs(id = "permanent-agent"))
            assertTrue(result.contains("[INFO]"), "Expected info message, got: $result")
            assertTrue(result.contains("already a permanent"), result)
        }

    @Test
    fun `promote nonexistent agent returns error`() =
        runTest {
            val db = freshDb()
            val tool = PromoteAgentTool(db)
            val result = tool.execute(PromoteAgentArgs(id = "ghost"))
            assertTrue(result.contains("[ERROR]"), "Expected error, got: $result")
            assertTrue(result.contains("not found"), result)
        }

    @Test
    fun `promote updates name if provided`() =
        runTest {
            val db = freshDb()
            db.insertAgentProfile(ephemeralProfile("temp-x", "Old Name"))

            val tool = PromoteAgentTool(db)
            tool.execute(PromoteAgentArgs(id = "temp-x", newName = "Shiny New Name"))
            val profile = db.getAgentProfile("temp-x")!!
            assertEquals("Shiny New Name", profile.name, "Name should be updated")
            assertFalse(profile.ephemeral, "Should be promoted")
        }

    // ══════════════════════════════════════════════════════════════
    // CleanupEphemeralAgentsTool
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `cleanup removes old ephemeral agents`() =
        runTest {
            val db = freshDb()
            // Created 48 hours ago
            val oldTime = System.currentTimeMillis() - 48 * 3600_000L
            db.insertAgentProfile(ephemeralProfile("old-temp", createdAt = oldTime))

            val tool = CleanupEphemeralAgentsTool(db)
            val result = tool.execute(CleanupEphemeralArgs(olderThanHours = 24))
            assertTrue(result.contains("Cleanup Complete"), "Expected cleanup, got: $result")
            assertTrue(result.contains("old-temp"), result)
            assertNull(db.getAgentProfile("old-temp"), "Old ephemeral should be deleted")
        }

    @Test
    fun `cleanup preserves recent ephemeral agents`() =
        runTest {
            val db = freshDb()
            // Created 1 hour ago
            val recentTime = System.currentTimeMillis() - 1 * 3600_000L
            db.insertAgentProfile(ephemeralProfile("recent-temp", createdAt = recentTime))

            val tool = CleanupEphemeralAgentsTool(db)
            val result = tool.execute(CleanupEphemeralArgs(olderThanHours = 24))
            assertTrue(result.contains("[INFO]"), "Expected info (nothing to clean), got: $result")
            assertNotNull(db.getAgentProfile("recent-temp"), "Recent ephemeral should be preserved")
        }

    @Test
    fun `cleanup preserves system agents`() =
        runTest {
            val db = freshDb()
            val oldTime = System.currentTimeMillis() - 48 * 3600_000L
            // System agent, even if old
            db.insertAgentProfile(systemProfile("sys-agent").copy(createdAt = oldTime))

            val tool = CleanupEphemeralAgentsTool(db)
            tool.execute(CleanupEphemeralArgs(olderThanHours = 24))
            assertNotNull(db.getAgentProfile("sys-agent"), "System agents should never be cleaned up")
        }

    @Test
    fun `cleanup preserves permanent custom agents`() =
        runTest {
            val db = freshDb()
            val oldTime = System.currentTimeMillis() - 48 * 3600_000L
            db.insertAgentProfile(customProfile("my-custom").copy(createdAt = oldTime))

            val tool = CleanupEphemeralAgentsTool(db)
            tool.execute(CleanupEphemeralArgs(olderThanHours = 24))
            assertNotNull(db.getAgentProfile("my-custom"), "Permanent custom agents should never be cleaned up")
        }

    @Test
    fun `cleanup returns info when nothing to clean`() =
        runTest {
            val db = freshDb()
            val tool = CleanupEphemeralAgentsTool(db)
            val result = tool.execute(CleanupEphemeralArgs())
            assertTrue(result.contains("[INFO]"), "Expected info message, got: $result")
            assertTrue(result.contains("No expired ephemeral"), result)
        }
}
