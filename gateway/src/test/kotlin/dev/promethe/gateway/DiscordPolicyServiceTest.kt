package dev.promethe.gateway

import dev.promethe.api.DiscordUserRuleEffect
import dev.promethe.api.ToolCallOrigin
import dev.promethe.api.UpsertDiscordChannelRuleRequest
import dev.promethe.api.UpsertDiscordUserRuleRequest
import dev.promethe.core.ToolExecutionRequest
import dev.promethe.core.ToolInvocationContext
import dev.promethe.db.ProjectRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject

class DiscordPolicyServiceTest {
    @Test
    fun `user allow rules persist and restrict deterministic topics`() =
        runBlocking {
            val database = FakeDatabase()
            val service = DiscordPolicyService(database)
            service.reload()
            val unrestricted = discordIdAllowlist("")
            assertTrue(service.allowsUser(unrestricted, "111", "222", "333") { "anything" })

            service.upsertUserRule(
                "<@111>",
                UpsertDiscordUserRuleRequest(allowedTopics = listOf("météo", "public release")),
            )
            assertTrue(service.allowsUser(unrestricted, "111", "222", "333") { "La METEO de demain" })
            assertFalse(service.allowsUser(unrestricted, "111", "222", "333") { "Delete every file" })
            assertFalse(service.allowsUser(unrestricted, "999", "222", "333") { "météo" })

            val reloaded = DiscordPolicyService(database)
            reloaded.reload()
            assertEquals(listOf("météo", "public release"), reloaded.current().userRules.single().allowedTopics)
        }

    @Test
    fun `dynamic deny overrides a static allow-list without denying everyone else`() =
        runBlocking {
            val service = DiscordPolicyService(FakeDatabase())
            service.reload()
            val staticAllowlist = discordIdAllowlist("111 222")
            service.upsertUserRule(
                "111",
                UpsertDiscordUserRuleRequest(effect = DiscordUserRuleEffect.DENY),
            )

            assertFalse(service.allowsUser(staticAllowlist, "111", "guild", "channel") { "hello" })
            assertTrue(service.allowsUser(staticAllowlist, "222", "guild", "channel") { "hello" })
            assertFalse(service.allowsUser(staticAllowlist, "333", "guild", "channel") { "hello" })
        }

    @Test
    fun `channel rules override static capture and select a project`() =
        runBlocking {
            val database = FakeDatabase()
            database.insertProject(
                ProjectRow(
                    id = "project-1",
                    name = "Discord knowledge",
                    workspacePath = "projects/project-1",
                    memoryNamespace = "project:project-1",
                    createdAt = 1,
                    updatedAt = 1,
                ),
            )
            val service = DiscordPolicyService(database)
            service.reload()
            service.upsertChannelRule(
                "<#444>",
                UpsertDiscordChannelRuleRequest(captureKnowledge = true, projectId = "project-1"),
            )

            assertTrue(service.capturesKnowledge(emptySet(), "222", "444"))
            assertEquals("project-1", service.projectIdForChannel("222", "444"))
            assertEquals(setOf("444"), service.effectiveKnowledgeChannelIds(emptySet()))

            service.upsertChannelRule("444", UpsertDiscordChannelRuleRequest(captureKnowledge = false))
            assertFalse(service.capturesKnowledge(setOf("444"), "222", "444"))
            assertTrue(service.effectiveKnowledgeChannelIds(setOf("444")).isEmpty())
        }

    @Test
    fun `policy tool rejects Discord origin and accepts owner A2A origin`() =
        runBlocking {
            val service = DiscordPolicyService(FakeDatabase())
            service.reload()
            var reloads = 0
            val tool = DiscordPolicyTool(service) { reloads++ }
            val args = DiscordPolicyToolArgs(action = "allow_user", targetId = "111")

            val blocked =
                withContext(
                    ToolInvocationContext(
                        ToolExecutionRequest("discord_policy", buildJsonObject {}, "discord-session", ToolCallOrigin.CHANNEL),
                    ),
                ) {
                    tool.execute(args)
                }
            assertTrue(blocked.startsWith("[BLOCKED]"))
            assertTrue(service.current().userRules.isEmpty())

            val accepted =
                withContext(
                    ToolInvocationContext(
                        ToolExecutionRequest("discord_policy", buildJsonObject {}, "owner-session", ToolCallOrigin.A2A),
                    ),
                ) {
                    tool.execute(args)
                }
            assertTrue(accepted.startsWith("Discord policy updated immediately"))
            assertEquals(1, reloads)
            assertEquals("111", service.current().userRules.single().userId)
        }
}
