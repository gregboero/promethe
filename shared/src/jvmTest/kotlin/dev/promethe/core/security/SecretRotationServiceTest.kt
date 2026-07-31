package dev.promethe.core.security

import dev.promethe.db.DatabaseFactory
import dev.promethe.db.McpServerConfigRow
import dev.promethe.db.OAuthConnectionRow
import dev.promethe.db.RemoteOwnerRow
import kotlinx.coroutines.test.runTest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SecretRotationServiceTest {
    @Test
    fun `rotation rewrites OAuth and MCP envelopes with the active key`() =
        runTest {
            val database = DatabaseFactory.createInMemory()
            database.upsertRemoteOwner(RemoteOwnerRow("owner", "owner", "hash", 1, 1))
            val oldKey = encodedKey(1)
            val newKey = encodedKey(2)
            val oldCipher = SecretCipher.fromKeyRing("old", oldKey, emptyMap())
            database.upsertOAuthConnection(OAuthConnectionRow("owner", "github", oldCipher.encrypt("oauth"), Long.MAX_VALUE, 1))
            database.upsertMcpServerConfig(McpServerConfigRow("server", "{}", oldCipher.encrypt("mcp"), 1, 1))
            val rotatingCipher = SecretCipher.fromKeyRing("new", newKey, mapOf("old" to oldKey))

            val result = SecretRotationService(database, rotatingCipher).rotate()

            assertEquals(1, result.oauthConnections)
            assertEquals(1, result.mcpServers)
            val oauth = database.getOAuthConnection("owner", "github")!!
            val mcp = database.getMcpServerConfigs().single()
            assertFalse(rotatingCipher.needsRotation(oauth.encryptedTokens))
            assertFalse(rotatingCipher.needsRotation(mcp.encryptedSecrets))
            assertEquals("oauth", rotatingCipher.decrypt(oauth.encryptedTokens))
            assertEquals("mcp", rotatingCipher.decrypt(mcp.encryptedSecrets))
        }

    private fun encodedKey(seed: Int): String = Base64.getEncoder().encodeToString(ByteArray(32) { index -> (seed + index).toByte() })
}
