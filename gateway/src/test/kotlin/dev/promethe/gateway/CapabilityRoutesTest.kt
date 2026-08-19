package dev.promethe.gateway

import dev.promethe.api.CapabilityListResponse
import dev.promethe.api.CapabilityAvailability
import dev.promethe.api.CapabilityMaturity
import dev.promethe.api.ProviderRegistry
import dev.promethe.core.ToolRegistry
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CapabilityRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun ApplicationTestBuilder.configureApp() {
        application { capabilityTestModule() }
    }

    private fun Application.capabilityTestModule() {
        install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
        routing { capabilityRoutes() }
    }

    @Test
    fun `catalog contains all public families and all 19 channels`() =
        testApplication {
            configureApp()

            val response = client.get("/api/v1/capabilities")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = json.decodeFromString<CapabilityListResponse>(response.bodyAsText())
            val ids = body.capabilities.map { it.id }.toSet()

            assertTrue(ids.containsAll(listOf("protocol.a2a", "protocol.mcp", "protocol.acp", "protocol.openai")))
            assertTrue(ProviderRegistry.providers.all { "provider.llm.${it.key}" in ids })
            assertTrue(body.capabilities.any { it.id.startsWith("provider.media.") })
            assertTrue(body.capabilities.any { it.id.startsWith("provider.voice.") })
            assertEquals(19, body.capabilities.count { it.category == "channel" })
            assertEquals(
                setOf(
                    "telegram",
                    "discord",
                    "slack",
                    "whatsapp",
                    "signal",
                    "matrix",
                    "email",
                    "sms",
                    "teams",
                    "mattermost",
                    "dingtalk",
                    "feishu",
                    "wecom",
                    "line",
                    "qq",
                    "weixin",
                    "bluebubbles",
                    "ntfy",
                    "homeassistant",
                ),
                body.capabilities.filter { it.category == "channel" }.map { it.id.removePrefix("channel.") }.toSet(),
            )
        }

    @Test
    fun `catalog uses conservative maturity and mirrors dynamic tools`() =
        testApplication {
            configureApp()

            val response = client.get("/api/v1/capabilities")
            val body = json.decodeFromString<CapabilityListResponse>(response.bodyAsText())
            val channels = body.capabilities.filter { it.category == "channel" }
            val tools = ToolRegistry.listTools()
            val betaChannels = setOf("telegram", "discord", "slack", "whatsapp", "signal", "matrix", "sms")

            assertTrue(
                channels.filter { it.id.removePrefix("channel.") in betaChannels }
                    .all { it.maturity.name == "BETA" },
            )
            assertTrue(
                channels.filter { it.id.removePrefix("channel.") !in betaChannels }
                    .all { it.maturity.name == "LAB" },
            )
            assertTrue(
                body.capabilities.filter { it.id.startsWith("client.") || it.id == "plugins.jvm" }
                    .all { it.maturity.name == "LAB" },
            )
            val plugins = body.capabilities.single { it.id == "plugins.jvm" }
            assertEquals(CapabilityAvailability.DISABLED, plugins.availability)

            val mcp = body.capabilities.single { it.id == "protocol.mcp" }
            assertEquals(CapabilityMaturity.BETA, mcp.maturity)
            if (tools.none { it.name.startsWith("mcp_") }) {
                assertEquals(CapabilityAvailability.MISSING_CONFIGURATION, mcp.availability)
            }
            val toolCapabilities = body.capabilities.filter { it.category == "tool" }
            assertEquals(tools.map { "tool.${it.name}" }.toSet(), toolCapabilities.map { it.id }.toSet())
            assertTrue(toolCapabilities.all { it.toolContract != null })
            assertTrue(toolCapabilities.all { it.risk == it.toolContract?.catalogRisk?.name })
        }

    @Test
    fun `catalog exposes configuration names but never secret values`() =
        testApplication {
            configureApp()

            val raw = client.get("/api/v1/capabilities").bodyAsText()
            val body = json.decodeFromString<CapabilityListResponse>(raw)
            assertNotNull(body)
            assertFalse(raw.contains("sk-"))
            assertFalse(raw.contains("Bearer ", ignoreCase = true))
            assertTrue(body.capabilities.flatMap { it.requiredConfiguration }.all { it.matches(Regex("[A-Z0-9_]+")) })
        }
}
