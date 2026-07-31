package dev.promethe.core

import ai.koog.a2a.model.AgentCard
import ai.koog.a2a.model.AgentCapabilities
import ai.koog.a2a.model.AgentSkill
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AgentA2ARegistryTest {
    private fun testCard(
        name: String,
        vararg skillIds: String,
    ): AgentCard =
        AgentCard(
            name = name,
            description = "$name description",
            url = "http://localhost:9090",
            version = "1.0.0",
            skills = skillIds.map { id ->
                AgentSkill(
                    id = id,
                    name = id.replaceFirstChar { it.uppercase() },
                    description = "$id skill",
                    tags = listOf(id),
                    inputModes = listOf("text/plain"),
                    outputModes = listOf("text/plain"),
                )
            },
            capabilities = AgentCapabilities(
                streaming = false,
                pushNotifications = false,
                stateTransitionHistory = false,
            ),
            defaultInputModes = listOf("text/plain"),
            defaultOutputModes = listOf("text/plain"),
        )

    @Test
    fun `registry starts empty`() {
        val registry = AgentA2ARegistry()
        assertEquals(emptyList(), registry.listAgentIds())
    }

    @Test
    fun `registerRemote adds agent`() =
        runTest {
            val registry = AgentA2ARegistry()
            registry.registerRemote("agent-1", "http://localhost:9091", testCard("Agent One"))
            assertContains(registry.listAgentIds(), "agent-1")
        }

    @Test
    fun `getAgentCard returns card for registered agent`() =
        runTest {
            val registry = AgentA2ARegistry()
            val card = testCard("Agent One", "chat")
            registry.registerRemote("agent-1", "http://localhost:9091", card)

            val retrieved = registry.getAgentCard("agent-1")
            assertNotNull(retrieved)
            assertEquals("Agent One", retrieved.name)
        }

    @Test
    fun `getAgentCard returns null for unknown agent`() =
        runTest {
            val registry = AgentA2ARegistry()
            assertNull(registry.getAgentCard("nonexistent"))
        }

    @Test
    fun `unregister removes agent`() =
        runTest {
            val registry = AgentA2ARegistry()
            registry.registerRemote("agent-1", "http://localhost:9091", testCard("Agent One"))
            registry.unregister("agent-1")
            assertFalse(registry.listAgentIds().contains("agent-1"))
            assertNull(registry.getAgentCard("agent-1"))
        }

    @Test
    fun `listAgentCards returns all cards`() =
        runTest {
            val registry = AgentA2ARegistry()
            registry.registerRemote("a1", "http://host:1", testCard("Alpha"))
            registry.registerRemote("a2", "http://host:2", testCard("Beta"))

            val cards = registry.listAgentCards()
            assertEquals(2, cards.size)
            assertTrue(cards.any { it.name == "Alpha" })
            assertTrue(cards.any { it.name == "Beta" })
        }

    @Test
    fun `findBySkill returns matching agents`() =
        runTest {
            val registry = AgentA2ARegistry()
            registry.registerRemote("a1", "http://host:1", testCard("Alpha", "chat", "code"))
            registry.registerRemote("a2", "http://host:2", testCard("Beta", "search"))

            val chatAgents = registry.findBySkill("chat")
            assertEquals(1, chatAgents.size)
            assertEquals("a1", chatAgents.first().first)

            val searchAgents = registry.findBySkill("search")
            assertEquals(1, searchAgents.size)
            assertEquals("a2", searchAgents.first().first)

            val noneAgents = registry.findBySkill("nonexistent")
            assertTrue(noneAgents.isEmpty())
        }

    @Test
    fun `sendTask throws for unknown agent`() =
        runTest {
            val registry = AgentA2ARegistry()
            val ex = assertFailsWith<IllegalArgumentException> {
                registry.sendTask("ghost", "hello")
            }
            assertTrue(ex.message!!.contains("ghost"))
        }
}
