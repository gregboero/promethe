package dev.promethe.core

import dev.promethe.db.DatabaseFactory
import dev.promethe.db.PrometheDatabaseApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ToolApprovalGateTest {
    @Test
    fun `command arguments containing credentials are denied without creating a request`() =
        runTest {
            val gate = gate()
            LiveProviderKeys.replace(mapOf("openai" to "sk-live-private-value"))

            try {
                val knownSecret =
                    gate.checkMandatory(
                        "shell",
                        """{"executable":"curl","arguments":["https://example.test","sk-live-private-value"]}""",
                        "session-a",
                    )
                val assignedSecret =
                    gate.checkMandatory(
                        "execute_command",
                        """{"executable":"client","arguments":["--api-key=another-private-value"]}""",
                        "session-a",
                    )

                assertFalse(knownSecret.allowed)
                assertFalse(assignedSecret.allowed)
                assertFalse(knownSecret.reason.contains("sk-live-private-value"))
                assertTrue(gate.listPending().isEmpty())
            } finally {
                LiveProviderKeys.replace(emptyMap())
            }
        }

    @Test
    fun `credential shaped values inside arrays are redacted from approval display`() =
        runTest {
            val gate = gate()
            val result =
                async {
                    gate.checkMandatory(
                        "custom_external_tool",
                        """{"arguments":["--token=private-value"]}""",
                        "session-a",
                    )
                }
            runCurrent()

            val request = gate.listPending().single()
            assertFalse(request.args.contains("private-value"))
            assertTrue(request.args.contains("***"))
            gate.respond(request.id, approved = false)
            assertFalse(result.await().allowed)
        }

    @Test
    fun `once approval accepts only the pending invocation`() =
        runTest {
            val gate = gate()
            val result = async { gate.checkMandatory("shell", """{"executable":"git","token":"secret"}""", "session-a") }
            runCurrent()

            val request = gate.listPending().single()
            assertFalse(request.args.contains("secret"))
            assertTrue(request.args.contains("***"))
            assertEquals(
                ToolApprovalGate.ResponseResult.ACCEPTED,
                gate.respond(request.id, approved = true, scope = ApprovalGate.ApprovalScope.ONCE),
            )
            assertTrue(result.await().allowed)

            val repeated = async { gate.checkMandatory("shell", """{"executable":"git","token":"secret"}""", "session-a") }
            runCurrent()
            assertEquals(1, gate.listPending().size)
            gate.respond(gate.listPending().single().id, approved = false)
            assertFalse(repeated.await().allowed)
        }

    @Test
    fun `session grant expires and a modified action has a different fingerprint`() =
        runTest {
            var now = 10_000L
            val gate = gate(clock = { now })
            val initial = async { gate.checkMandatory("shell", """{"arguments":["status"],"executable":"git"}""", "session-a") }
            runCurrent()
            val request = gate.listPending().single()
            assertEquals(
                ToolApprovalGate.ResponseResult.ACCEPTED,
                gate.respond(
                    request.id,
                    approved = true,
                    scope = ApprovalGate.ApprovalScope.SESSION,
                    expiresInMs = 1_000,
                ),
            )
            assertTrue(initial.await().allowed)

            val canonicalEquivalent =
                gate.checkMandatory("shell", """{"executable":"git","arguments":["status"]}""", "session-a")
            assertTrue(canonicalEquivalent.allowed)

            val modified = async { gate.checkMandatory("shell", """{"executable":"git","arguments":["commit"]}""", "session-a") }
            runCurrent()
            val modifiedRequest = gate.listPending().single()
            assertNotEquals(request.fingerprint, modifiedRequest.fingerprint)
            gate.respond(modifiedRequest.id, approved = false)
            assertFalse(modified.await().allowed)

            now += 1_001
            val expired = async { gate.checkMandatory("shell", """{"arguments":["status"],"executable":"git"}""", "session-a") }
            runCurrent()
            assertEquals(1, gate.listPending().size)
            gate.respond(gate.listPending().single().id, approved = false)
            assertFalse(expired.await().allowed)
        }

    @Test
    fun `csv approval fingerprint includes every argument`() =
        runTest {
            val gate = gate()
            val originalArgs =
                """{"action":"write","path":"reports/output.csv","data":"name,value\nalpha,1","maxRows":100}"""
            val initial = async { gate.checkMandatory("csv", originalArgs, "session-a") }
            runCurrent()
            val originalRequest = gate.listPending().single()
            gate.respond(originalRequest.id, approved = false)
            assertFalse(initial.await().allowed)

            val variants =
                listOf(
                    """{"action":"read","path":"reports/output.csv","data":"name,value\nalpha,1","maxRows":100}""",
                    """{"action":"write","path":"reports/other.csv","data":"name,value\nalpha,1","maxRows":100}""",
                    """{"action":"write","path":"reports/output.csv","data":"name,value\nbeta,2","maxRows":100}""",
                    """{"action":"write","path":"reports/output.csv","data":"name,value\nalpha,1","maxRows":10}""",
                )

            variants.forEach { args ->
                val pending = async { gate.checkMandatory("csv", args, "session-a") }
                runCurrent()
                val request = gate.listPending().single()
                assertNotEquals(originalRequest.fingerprint, request.fingerprint)
                gate.respond(request.id, approved = false)
                assertFalse(pending.await().allowed)
            }
        }

    @Test
    fun `deny wins over a broader persistent allow`() =
        runTest {
            val gate = gate()
            val args = """{"action":"listen_channel","targetId":"123"}"""
            val sessionDeny = async { gate.checkMandatory("discord_policy", args, "session-a") }
            runCurrent()
            gate.respond(
                gate.listPending().single().id,
                approved = false,
                scope = ApprovalGate.ApprovalScope.SESSION,
            )
            assertFalse(sessionDeny.await().allowed)

            val persistentAllow = async { gate.checkMandatory("discord_policy", args, "session-b") }
            runCurrent()
            gate.respond(
                gate.listPending().single().id,
                approved = true,
                scope = ApprovalGate.ApprovalScope.PERSISTENT,
                localOwner = true,
            )
            assertTrue(persistentAllow.await().allowed)

            val denied = gate.checkMandatory("discord_policy", args, "session-a")
            assertFalse(denied.allowed)
            assertTrue(denied.reason.startsWith("denied by"))
        }

    @Test
    fun `revocation removes a reusable grant`() =
        runTest {
            val gate = gate()
            val initial = async { gate.checkMandatory("shell", "{}", "session-a") }
            runCurrent()
            gate.respond(
                gate.listPending().single().id,
                approved = true,
                scope = ApprovalGate.ApprovalScope.SESSION,
            )
            val grantId = initial.await().approvalId!!
            assertEquals(ToolApprovalGate.RevocationResult.REVOKED, gate.revoke(grantId))

            val afterRevocation = async { gate.checkMandatory("shell", "{}", "session-a") }
            runCurrent()
            assertEquals(1, gate.listPending().size)
            gate.respond(gate.listPending().single().id, approved = false)
            assertFalse(afterRevocation.await().allowed)
        }

    @Test
    fun `persistent grants require a local owner and mandatory checks ignore auto mode`() =
        runTest {
            val gate = gate(approvalMode = "auto")
            val pending =
                async {
                    gate.checkMandatory(
                        "discord_policy",
                        """{"action":"listen_channel","targetId":"123"}""",
                        "session-a",
                    )
                }
            runCurrent()
            val requestId = gate.listPending().single().id
            assertEquals(
                ToolApprovalGate.ResponseResult.PERSISTENT_REQUIRES_LOCAL_OWNER,
                gate.respond(
                    requestId,
                    approved = true,
                    scope = ApprovalGate.ApprovalScope.PERSISTENT,
                    localOwner = false,
                ),
            )
            assertEquals(1, gate.listPending().size)
            gate.respond(requestId, approved = false)
            assertFalse(pending.await().allowed)
        }

    @Test
    fun `permanent configuration grant survives restart until local revocation`() =
        runTest {
            val directory = createTempDirectory("promethe-approval-grant").toFile()
            val databaseUrl = "jdbc:sqlite:${java.io.File(directory, "promethe.db").absolutePath}"
            val args = """{"action":"listen_channel","targetId":"123"}"""
            try {
                val firstGate = gate(database = DatabaseFactory.create(databaseUrl))
                val initial = async { firstGate.checkMandatory("discord_policy", args, "session-a") }
                runCurrent()
                assertEquals(
                    ToolApprovalGate.ResponseResult.ACCEPTED,
                    firstGate.respond(
                        firstGate.listPending().single().id,
                        approved = true,
                        scope = ApprovalGate.ApprovalScope.PERSISTENT,
                        localOwner = true,
                    ),
                )
                assertTrue(initial.await().allowed)
                assertEquals(Long.MAX_VALUE, firstGate.listGrants().single().expiresAt)

                val restartedGate = gate(database = DatabaseFactory.create(databaseUrl))
                assertTrue(restartedGate.checkMandatory("discord_policy", args, "session-b").allowed)
                val grantId = restartedGate.listGrants().single().id
                assertEquals(
                    ToolApprovalGate.RevocationResult.REVOKED,
                    restartedGate.revoke(grantId, localOwner = true),
                )

                val afterRevocationGate = gate(database = DatabaseFactory.create(databaseUrl))
                val afterRevocation = async { afterRevocationGate.checkMandatory("discord_policy", args, "session-c") }
                runCurrent()
                assertEquals(1, afterRevocationGate.listPending().size)
                afterRevocationGate.respond(afterRevocationGate.listPending().single().id, approved = false)
                assertFalse(afterRevocation.await().allowed)
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun `only exact configuration changes can be persisted by an integration approver`() =
        runTest {
            val gate = gate()
            val requested = mutableListOf<ToolApprovalGate.ApprovalRequest>()
            val subscription = gate.addRequestListener(requested::add)

            try {
                val configChange =
                    async {
                        gate.checkMandatory(
                            "discord_policy",
                            """{"action":"listen_channel","targetId":"123"}""",
                            "session-local",
                        )
                    }
                runCurrent()
                val configRequest = gate.listPending().single()
                assertTrue(configRequest.persistentAllowed)
                assertEquals(configRequest.id, requested.single().id)
                assertEquals(
                    ToolApprovalGate.ResponseResult.ACCEPTED,
                    gate.respondFromIntegrationApprover(
                        configRequest.id,
                        approved = true,
                        scope = ApprovalGate.ApprovalScope.PERSISTENT,
                    ),
                )
                assertTrue(configChange.await().allowed)

                val shell = async { gate.checkMandatory("shell", """{"executable":"git"}""", "session-local") }
                runCurrent()
                val shellRequest = gate.listPending().single()
                assertFalse(shellRequest.persistentAllowed)
                assertEquals(
                    ToolApprovalGate.ResponseResult.PERSISTENT_REQUIRES_LOCAL_OWNER,
                    gate.respondFromIntegrationApprover(
                        shellRequest.id,
                        approved = true,
                        scope = ApprovalGate.ApprovalScope.PERSISTENT,
                    ),
                )
                gate.respond(shellRequest.id, approved = false)
                assertFalse(shell.await().allowed)

                val localAgent = async { gate.checkMandatory("codex_delegate", "{}", "discord-1-2") }
                runCurrent()
                val localAgentRequest = gate.listPending().single()
                assertEquals(
                    ToolApprovalGate.ResponseResult.LOCAL_OWNER_REQUIRED,
                    gate.respondFromIntegrationApprover(
                        localAgentRequest.id,
                        approved = true,
                        scope = ApprovalGate.ApprovalScope.ONCE,
                    ),
                )
                gate.respond(localAgentRequest.id, approved = false)
                assertFalse(localAgent.await().allowed)
            } finally {
                subscription.close()
            }
        }

    private fun gate(
        approvalMode: String = "all",
        clock: () -> Long = System::currentTimeMillis,
        database: PrometheDatabaseApi? = null,
    ): ToolApprovalGate =
        ToolApprovalGate(
            config =
                AgentConfig(
                    approvalMode = approvalMode,
                    approvalTimeoutMs = 10_000,
                ),
            clock = clock,
            database = database,
        )
}
