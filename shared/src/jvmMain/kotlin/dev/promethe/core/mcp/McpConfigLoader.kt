package dev.promethe.core.mcp

import dev.promethe.core.McpBridge
import dev.promethe.core.PromethePrettyJson
import kotlinx.serialization.Serializable
import dev.promethe.core.config.ConfigProvider
import dev.promethe.core.security.SecretCipher
import dev.promethe.db.PrometheDatabaseApi
import java.io.File

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * Loads MCP server configurations from multiple sources, in priority order:
 *
 * 1. Environment variable `MCP_SERVERS` (JSON array of [McpBridge.McpServerConfig])
 * 2. Encrypted database configuration (the UI source of truth)
 *
 * Legacy mcp.json files are imported once into the database, then ignored.
 *
 * Earlier sources take priority: once a server ID is seen from a higher-priority
 * source, later sources will not override it.
 */
object McpConfigLoader {
    /**
     * Intermediate model matching the JSON file format where server configs
     * are keyed by their ID inside an `mcpServers` object.
     */
    @Serializable
    private data class McpConfigFile(
        val mcpServers: Map<String, McpServerEntry> = emptyMap(),
    )

    /**
     * A single server entry inside an `mcpServers` map.
     * The `id` and `name` are derived from the map key.
     */
    @Serializable
    private data class McpServerEntry(
        val transport: String,
        val command: String = "",
        val url: String = "",
        val env: Map<String, String> = emptyMap(),
        val headers: Map<String, String> = emptyMap(),
        val enabled: Boolean = true,
    )

    private val json = PromethePrettyJson

    /**
     * Load MCP server configs from all sources, merging by server ID.
     * First source wins — later sources do not override an already-seen ID.
     */
    suspend fun loadConfigs(
        database: PrometheDatabaseApi? = null,
        cipher: SecretCipher? = null,
    ): List<McpBridge.McpServerConfig> {
        val merged = linkedMapOf<String, McpBridge.McpServerConfig>()

        // Source 1: Environment variable
        loadFromEnv()?.let { configs ->
            logger.info { "Loaded ${configs.size} server(s) from MCP_SERVERS env var" }
            configs.forEach { merged.putIfAbsent(it.id, it) }
        }

        if (database != null) {
            val store = McpConfigurationStore(database, cipher)
            if (database.getSetting(LEGACY_IMPORT_MARKER) != LEGACY_IMPORT_VERSION) {
                if (cipher == null) {
                    logger.warn { "Skipping one-time MCP legacy import because PROMETHE_MASTER_KEY is not configured" }
                } else {
                    val legacy = legacyFileConfigs().distinctBy { it.id }
                    for (config in legacy) store.upsert(config)
                    database.upsertSetting(LEGACY_IMPORT_MARKER, LEGACY_IMPORT_VERSION)
                    if (legacy.isNotEmpty()) logger.info { "Imported ${legacy.size} legacy MCP server(s) into encrypted storage" }
                }
            }
            if (cipher != null) {
                store.load().let { configs ->
                    logger.info { "Loaded ${configs.size} server(s) from encrypted database" }
                    configs.forEach { merged.putIfAbsent(it.id, it) }
                }
            } else if (database.getMcpServerConfigs().isNotEmpty()) {
                throw IllegalStateException("PROMETHE_MASTER_KEY is required to load persisted MCP configurations")
            }
        }

        if (merged.isEmpty()) {
            logger.info { "No MCP server configurations found in any source" }
        } else {
            logger.info { "Total: ${merged.size} MCP server(s) configured" }
        }

        return merged.values.toList()
    }

    /**
     * Persist a list of server configs to the given file path using the
     * canonical `mcpServers` map format.
     */
    fun saveConfig(
        configs: List<McpBridge.McpServerConfig>,
        path: String,
    ) {
        val entries = configs.associate { config ->
            config.id to McpServerEntry(
                transport = config.transport,
                command = config.command,
                url = config.url,
                env = config.env,
                headers = config.headers,
                enabled = config.enabled,
            )
        }
        val wrapper = McpConfigFile(mcpServers = entries)
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(McpConfigFile.serializer(), wrapper))
        logger.info { "Saved ${configs.size} server config(s) to ${file.absolutePath}" }
    }

    // ── Private loaders ──────────────────────────────────────────

    /**
     * Parse the `MCP_SERVERS` environment variable as a JSON array
     * of [McpBridge.McpServerConfig].
     */
    private fun loadFromEnv(): List<McpBridge.McpServerConfig>? {
        val raw = ConfigProvider.get().get("MCP_SERVERS")
        if (raw.isNullOrBlank()) return null

        return try {
            json.decodeFromString<List<McpBridge.McpServerConfig>>(raw)
        } catch (e: Exception) {
            logger.warn { "Failed to parse MCP_SERVERS env var: ${e.message}" }
            null
        }
    }

    /** IDs defined in MCP_SERVERS are process-owned and cannot be edited by the UI. */
    fun environmentConfigIds(): Set<String> = loadFromEnv().orEmpty().mapTo(linkedSetOf()) { it.id }

    /**
     * Load configs from a JSON file using the `mcpServers` map format.
     * Returns null if the file doesn't exist or is malformed.
     */
    private fun loadFromFile(file: File): List<McpBridge.McpServerConfig>? {
        if (!file.exists()) return null

        return try {
            val content = file.readText()

            // Try the canonical McpConfigFile format first
            val configFile = json.decodeFromString(McpConfigFile.serializer(), content)
            configFile.mcpServers.map { (id, entry) ->
                McpBridge.McpServerConfig(
                    id = id,
                    name = id,
                    transport = entry.transport,
                    command = entry.command,
                    url = entry.url,
                    env = entry.env,
                    headers = entry.headers,
                    enabled = entry.enabled,
                )
            }
        } catch (e: Exception) {
            logger.warn { "Failed to parse ${file.absolutePath}: ${e.message}" }
            null
        }
    }

    private fun legacyFileConfigs(): List<McpBridge.McpServerConfig> =
        listOf(
            File("mcp.json"),
            File(System.getProperty("user.home"), ".promethe/mcp.json"),
        ).flatMap { file -> loadFromFile(file).orEmpty() }

    private const val LEGACY_IMPORT_MARKER = "mcp.legacy_import.version"
    private const val LEGACY_IMPORT_VERSION = "v1"
}
