package dev.promethe.core.security

import dev.promethe.db.PrometheDatabaseApi
import dev.promethe.db.SecurityAuditLogRow

class SecretRotationService(
    private val database: PrometheDatabaseApi,
    private val cipher: SecretCipher,
) {
    data class Result(
        val oauthConnections: Int,
        val mcpServers: Int,
    )

    suspend fun rotate(): Result {
        val ownerId = database.getRemoteOwner()?.id ?: "owner"
        var oauthCount = 0
        database.getOAuthConnections(ownerId).forEach { connection ->
            if (cipher.needsRotation(connection.encryptedTokens)) {
                database.upsertOAuthConnection(
                    connection.copy(
                        encryptedTokens = cipher.encrypt(cipher.decrypt(connection.encryptedTokens)),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                oauthCount += 1
            }
        }

        var mcpCount = 0
        database.getMcpServerConfigs().forEach { server ->
            if (cipher.needsRotation(server.encryptedSecrets)) {
                database.upsertMcpServerConfig(
                    server.copy(
                        encryptedSecrets = cipher.encrypt(cipher.decrypt(server.encryptedSecrets)),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                mcpCount += 1
            }
        }

        database.insertSecurityAuditLog(
            SecurityAuditLogRow(
                eventType = "master_key_rotated",
                actor = ownerId,
                detail = "oauth_connections=$oauthCount,mcp_servers=$mcpCount",
                createdAt = System.currentTimeMillis(),
            ),
        )
        return Result(oauthConnections = oauthCount, mcpServers = mcpCount)
    }
}
