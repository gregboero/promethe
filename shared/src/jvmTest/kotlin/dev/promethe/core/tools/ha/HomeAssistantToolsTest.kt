package dev.promethe.core.tools.ha

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class HomeAssistantToolsTest {
    @Test
    fun testHaListEntitiesNotConfigured() =
        runTest {
            val client = HttpClient(MockEngine { _ -> respond("") })
            val tool = HaListEntitiesTool(client)
            val result = tool.execute(HaListEntitiesArgs())
            assertTrue(result.contains("[ERROR] HA_URL and HA_TOKEN not configured"), "Should return config error: $result")
        }

    @Test
    fun testHaGetStateNotConfigured() =
        runTest {
            val client = HttpClient(MockEngine { _ -> respond("") })
            val tool = HaGetStateTool(client)
            val result = tool.execute(HaGetStateArgs(entityId = "light.living_room"))
            assertTrue(result.contains("[ERROR] HA_URL and HA_TOKEN not configured"), "Should return config error: $result")
        }

    @Test
    fun testHaListServicesNotConfigured() =
        runTest {
            val client = HttpClient(MockEngine { _ -> respond("") })
            val tool = HaListServicesTool(client)
            val result = tool.execute(HaListServicesArgs())
            assertTrue(result.contains("[ERROR] HA_URL and HA_TOKEN not configured"), "Should return config error: $result")
        }

    @Test
    fun testHaCallServiceNotConfigured() =
        runTest {
            val client = HttpClient(MockEngine { _ -> respond("") })
            val tool = HaCallServiceTool(client)
            val result = tool.execute(HaCallServiceArgs(domain = "light", service = "turn_on", entityId = "light.living_room"))
            assertTrue(result.contains("[ERROR] HA_URL and HA_TOKEN not configured"), "Should return config error: $result")
        }
}
