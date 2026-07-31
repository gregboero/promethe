package dev.promethe.core.acp

import dev.promethe.core.Log

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable

/**
 * ACP Tool Bridge — converts remote ACP agent capabilities into local Prométhé tools.
 *
 * For each ACP agent registered in the discovery service, this bridge creates
 * a SimpleTool that the agent can invoke just like any other local tool.
 * The tool delegates to the remote agent via AcpClient.
 */
class AcpToolBridge(
    private val httpClient: HttpClient,
    private val apiKey: String = "",
) {
    private val client = AcpClient(httpClient, apiKey)

    /**
     * Create tools for all capabilities of a remote agent.
     *
     * Each capability becomes a SimpleTool named "acp_{agentId}_{capabilityId}".
     */
    fun createToolsForAgent(entry: AcpRegistryEntry): List<SimpleTool<AcpToolArgs>> {
        val card = entry.card ?: return emptyList()
        return card.capabilities.map { capability ->
            AcpRemoteTool(
                agentUrl = entry.url,
                agentName = card.name,
                capability = capability,
                client = client,
            )
        }
    }
}

// ── Tool Args ────────────────────────────────────────────────────────

@Serializable
data class AcpToolArgs(
    @property:LLMDescription("Input text to send to the remote agent capability.")
    val input: String,
    @property:LLMDescription("Optional key-value parameters as JSON (e.g. '{\"key\":\"value\"}').")
    val parameters: String = "{}",
)

// ── Remote Tool ──────────────────────────────────────────────────────

/**
 * A SimpleTool that wraps a remote ACP agent capability.
 * When the LLM calls this tool, it delegates to the remote agent.
 */
class AcpRemoteTool(
    private val agentUrl: String,
    private val agentName: String,
    private val capability: AcpCapability,
    private val client: AcpClient,
) : SimpleTool<AcpToolArgs>(
        argsType = typeToken<AcpToolArgs>(),
        name = "acp_${agentName.lowercase().replace(Regex("[^a-z0-9]"), "_")}_${capability.id}",
        description = "Remote agent '$agentName': ${capability.description}",
    ) {
    private val logger = Log.create("AcpRemoteTool")

    override suspend fun execute(args: AcpToolArgs): String {
        val params = try {
            kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(args.parameters)
        } catch (e: Exception) {
            logger.debug(e) { "Failed to parse ACP parameters as JSON, using empty map" }
            emptyMap()
        }

        val response = client.invoke(
            baseUrl = agentUrl,
            capabilityId = capability.id,
            input = args.input,
            parameters = params,
        )

        return if (response.status == AcpStatus.COMPLETED) {
            response.output
        } else {
            "[ERROR] ACP call failed: ${response.error ?: "Unknown error"}"
        }
    }
}
