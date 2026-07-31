package dev.promethe.core.mcp

import dev.promethe.core.McpBridge
import dev.promethe.core.security.SecretCipher
import dev.promethe.db.McpServerConfigRow
import dev.promethe.db.PrometheDatabaseApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Encrypts credentials while keeping non-secret MCP connection metadata queryable. */
class McpConfigurationStore(
    private val database: PrometheDatabaseApi,
    private val cipher: SecretCipher?,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun load(): List<McpBridge.McpServerConfig> {
        val rows = database.getMcpServerConfigs()
        if (rows.isEmpty()) return emptyList()
        val activeCipher = cipher ?: throw IllegalStateException("PROMETHE_MASTER_KEY is required to decrypt persisted MCP configurations")
        return rows.map { row ->
            val publicConfig = json.decodeFromString(McpBridge.McpServerConfig.serializer(), row.configJson)
            val secrets = json.decodeFromString(McpSecrets.serializer(), activeCipher.decrypt(row.encryptedSecrets))
            publicConfig.copy(env = secrets.env, headers = secrets.headers)
        }
    }

    suspend fun upsert(config: McpBridge.McpServerConfig) {
        validate(config)
        val activeCipher = cipher ?: throw IllegalStateException("PROMETHE_MASTER_KEY is required before MCP configuration can be stored")
        val now = System.currentTimeMillis()
        val existing = database.getMcpServerConfigs().firstOrNull { it.id == config.id }
        database.upsertMcpServerConfig(
            McpServerConfigRow(
                id = config.id,
                configJson = json.encodeToString(McpBridge.McpServerConfig.serializer(), config.copy(env = emptyMap(), headers = emptyMap())),
                encryptedSecrets = activeCipher.encrypt(json.encodeToString(McpSecrets.serializer(), McpSecrets(config.env, config.headers))),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    suspend fun delete(id: String) {
        database.deleteMcpServerConfig(id)
    }

    fun validate(config: McpBridge.McpServerConfig) {
        require(config.id.matches(ID_PATTERN)) { "MCP server id must contain 1-128 letters, digits, '.', '_' or '-'" }
        require(config.name.isNotBlank()) { "MCP server name is required" }
        require(config.transport in SUPPORTED_TRANSPORTS) { "Unsupported MCP transport '${config.transport}'" }
        when (config.transport) {
            "stdio" -> require(config.command.isNotBlank()) { "stdio MCP servers require a command" }
            "sse", "streamable-http" -> require(config.url.startsWith("http://") || config.url.startsWith("https://")) { "HTTP MCP servers require an http(s) URL" }
        }
    }

    @Serializable
    private data class McpSecrets(
        val env: Map<String, String> = emptyMap(),
        val headers: Map<String, String> = emptyMap(),
    )

    private companion object {
        val ID_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
        val SUPPORTED_TRANSPORTS = setOf("stdio", "sse", "streamable-http")
    }
}
