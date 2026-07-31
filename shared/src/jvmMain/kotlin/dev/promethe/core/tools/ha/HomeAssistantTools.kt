package dev.promethe.core.tools.ha

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import dev.promethe.core.PrometheJson
import dev.promethe.core.config.ConfigProvider
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

private val haJson = PrometheJson

private fun haConfig(): Pair<String, String>? {
    val url = ConfigProvider.get().get("HA_URL")?.takeIf { it.isNotBlank() } ?: return null
    val token = ConfigProvider.get().get("HA_TOKEN")?.takeIf { it.isNotBlank() } ?: return null
    return url.trimEnd('/') to token
}

// ── ha_list_entities ────────────────────────────────────────────

@Serializable
data class HaListEntitiesArgs(
    @property:LLMDescription("Optional domain filter: 'light', 'switch', 'sensor', 'climate', etc.")
    val domain: String = "",
)

class HaListEntitiesTool(
    private val httpClient: HttpClient,
) : SimpleTool<HaListEntitiesArgs>(
        argsType = typeToken<HaListEntitiesArgs>(),
        name = "ha_list_entities",
        description = "List all Home Assistant entities (lights, switches, sensors, etc.).",
    ) {
    override suspend fun execute(args: HaListEntitiesArgs): String {
        val (baseUrl, token) = haConfig() ?: return "[ERROR] HA_URL and HA_TOKEN not configured"
        return try {
            val response = httpClient.get("$baseUrl/api/states") {
                header("Authorization", "Bearer $token")
            }
            val states = haJson.parseToJsonElement(response.bodyAsText()).jsonArray
            val filtered = if (args.domain.isNotBlank()) {
                states.filter { it.jsonObject["entity_id"]?.jsonPrimitive?.content?.startsWith("${args.domain}.") == true }
            } else {
                states.toList()
            }

            val sb = StringBuilder("Found ${filtered.size} entities:\n")
            filtered.take(100).forEach { e ->
                val id = e.jsonObject["entity_id"]?.jsonPrimitive?.content ?: ""
                val state = e.jsonObject["state"]?.jsonPrimitive?.content ?: ""
                val name = e.jsonObject["attributes"]?.jsonObject?.get("friendly_name")?.jsonPrimitive?.content ?: ""
                sb.appendLine("- $id ($name): $state")
            }
            sb.toString()
        } catch (e: Exception) {
            "[ERROR] HA API: ${e.message}"
        }
    }
}

// ── ha_get_state ────────────────────────────────────────────────

@Serializable
data class HaGetStateArgs(
    @property:LLMDescription("Entity ID, e.g. 'light.living_room', 'sensor.temperature'.")
    val entityId: String,
)

class HaGetStateTool(
    private val httpClient: HttpClient,
) : SimpleTool<HaGetStateArgs>(
        argsType = typeToken<HaGetStateArgs>(),
        name = "ha_get_state",
        description = "Get the current state and attributes of a Home Assistant entity.",
    ) {
    override suspend fun execute(args: HaGetStateArgs): String {
        val (baseUrl, token) = haConfig() ?: return "[ERROR] HA_URL and HA_TOKEN not configured"
        return try {
            val response = httpClient.get("$baseUrl/api/states/${args.entityId}") {
                header("Authorization", "Bearer $token")
            }
            if (response.status == HttpStatusCode.NotFound) {
                return "[ERROR] Entity '${args.entityId}' not found"
            }
            response.bodyAsText()
        } catch (e: Exception) {
            "[ERROR] HA API: ${e.message}"
        }
    }
}

// ── ha_list_services ────────────────────────────────────────────

@Serializable
data class HaListServicesArgs(
    @property:LLMDescription("Optional domain filter: 'light', 'switch', 'climate', etc.")
    val domain: String = "",
)

class HaListServicesTool(
    private val httpClient: HttpClient,
) : SimpleTool<HaListServicesArgs>(
        argsType = typeToken<HaListServicesArgs>(),
        name = "ha_list_services",
        description = "List all available Home Assistant services.",
    ) {
    override suspend fun execute(args: HaListServicesArgs): String {
        val (baseUrl, token) = haConfig() ?: return "[ERROR] HA_URL and HA_TOKEN not configured"
        return try {
            val response = httpClient.get("$baseUrl/api/services") {
                header("Authorization", "Bearer $token")
            }
            val services = haJson.parseToJsonElement(response.bodyAsText()).jsonArray
            val filtered = if (args.domain.isNotBlank()) {
                services.filter { it.jsonObject["domain"]?.jsonPrimitive?.content == args.domain }
            } else {
                services.toList()
            }

            val sb = StringBuilder()
            filtered.forEach { svc ->
                val domain = svc.jsonObject["domain"]?.jsonPrimitive?.content ?: ""
                val names = svc.jsonObject["services"]?.jsonObject?.keys?.joinToString(", ") ?: ""
                sb.appendLine("$domain: $names")
            }
            sb.toString().ifBlank { "No services found" }
        } catch (e: Exception) {
            "[ERROR] HA API: ${e.message}"
        }
    }
}

// ── ha_call_service ─────────────────────────────────────────────

@Serializable
data class HaCallServiceArgs(
    @property:LLMDescription("Service domain, e.g. 'light', 'switch', 'climate'.")
    val domain: String,
    @property:LLMDescription("Service name, e.g. 'turn_on', 'turn_off', 'toggle'.")
    val service: String,
    @property:LLMDescription("Target entity ID.")
    val entityId: String,
    @property:LLMDescription("Optional JSON data, e.g. '{\"brightness\": 255}'.")
    val data: String = "{}",
)

class HaCallServiceTool(
    private val httpClient: HttpClient,
) : SimpleTool<HaCallServiceArgs>(
        argsType = typeToken<HaCallServiceArgs>(),
        name = "ha_call_service",
        description = "Call a Home Assistant service (e.g. turn on light, set temperature).",
    ) {
    override suspend fun execute(args: HaCallServiceArgs): String {
        val (baseUrl, token) = haConfig() ?: return "[ERROR] HA_URL and HA_TOKEN not configured"
        return try {
            // Build payload merging entity_id + extra data
            val extraFields = try {
                haJson.parseToJsonElement(args.data).jsonObject
                    .entries.joinToString(",") { "\"${it.key}\":${it.value}" }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse extra service data JSON for ${args.domain}.${args.service}" }
                ""
            }

            val payload = buildString {
                append("{\"entity_id\":\"${args.entityId}\"")
                if (extraFields.isNotBlank()) append(",$extraFields")
                append("}")
            }

            val response = httpClient.post("$baseUrl/api/services/${args.domain}/${args.service}") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }

            if (response.status.isSuccess()) {
                "✅ Service ${args.domain}.${args.service} called on ${args.entityId}"
            } else {
                "[ERROR] HTTP ${response.status.value}: ${response.bodyAsText().take(300)}"
            }
        } catch (e: Exception) {
            "[ERROR] HA service call: ${e.message}"
        }
    }
}
