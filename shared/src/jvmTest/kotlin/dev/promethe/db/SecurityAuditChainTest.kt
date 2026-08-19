package dev.promethe.db

import dev.promethe.api.ToolCallOrigin
import dev.promethe.core.PersistentPolicyAuditSink
import dev.promethe.core.PolicyKernel
import dev.promethe.core.ToolContractRegistry
import dev.promethe.core.ToolExecutionRequest
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SecurityAuditChainTest {
    @Test
    fun `audit entries form a durable verifiable chain`() =
        runTest {
            val directory = createTempDirectory("promethe-audit-chain").toFile()
            val url = "jdbc:sqlite:${java.io.File(directory, "promethe.db").absolutePath}"
            try {
                val database = DatabaseFactory.create(url)
                database.insertSecurityAuditLog(SecurityAuditLogRow("login_succeeded", actor = "owner", createdAt = 10))
                database.insertSecurityAuditLog(SecurityAuditLogRow("oauth_connected", actor = "owner", createdAt = 20))

                val rows = database.listSecurityAuditLogs()
                assertEquals(2, rows.size)
                assertTrue(rows.first().entryHash.isNotBlank())
                assertEquals(rows.first().entryHash, rows.last().previousHash)

                val reopened = DatabaseFactory.create(url)
                val verification = reopened.verifySecurityAuditChain()
                assertTrue(verification.valid)
                assertEquals(2, verification.entries)

                assertFailsWith<SQLException> {
                    DriverManager.getConnection(url).use { connection ->
                        connection.createStatement().use { statement ->
                            statement.executeUpdate("UPDATE security_audit_logs SET actor = 'tampered' WHERE id = 1")
                        }
                    }
                }
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `policy audit stores a fingerprint instead of arguments`() =
        runTest {
            val database = DatabaseFactory.createInMemory()
            val request =
                ToolExecutionRequest(
                    toolName = "send_email",
                    arguments = buildJsonObject { put("apiKey", "super-secret-value") },
                    sessionId = "audit-test",
                    origin = ToolCallOrigin.A2A,
                )
            val contract = ToolContractRegistry.contractFor(request.toolName)
            val decision = PolicyKernel().evaluate(request, contract)

            val recorded = PersistentPolicyAuditSink(database).record(request, contract, decision, createdAt = 30)

            assertTrue(recorded)
            val detail = database.listSecurityAuditLogs().single().detail
            assertTrue("decisionId" in detail)
            assertTrue("invocationHash" in detail)
            assertFalse("super-secret-value" in detail)
        }
}
