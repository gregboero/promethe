package dev.promethe.core

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
            val sessionDeny = async { gate.checkMandatory("shell", """{"executable":"git"}""", "session-a") }
            runCurrent()
            gate.respond(
                gate.listPending().single().id,
                approved = false,
                scope = ApprovalGate.ApprovalScope.SESSION,
            )
            assertFalse(sessionDeny.await().allowed)

            val persistentAllow = async { gate.checkMandatory("shell", """{"executable":"git"}""", "session-b") }
            runCurrent()
            gate.respond(
                gate.listPending().single().id,
                approved = true,
                scope = ApprovalGate.ApprovalScope.PERSISTENT,
                localOwner = true,
            )
            assertTrue(persistentAllow.await().allowed)

            val denied = gate.checkMandatory("shell", """{"executable":"git"}""", "session-a")
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
            val pending = async { gate.checkMandatory("shell", "{}", "session-a") }
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

    private fun gate(
        approvalMode: String = "all",
        clock: () -> Long = System::currentTimeMillis,
    ): ToolApprovalGate =
        ToolApprovalGate(
            config =
                AgentConfig(
                    approvalMode = approvalMode,
                    approvalTimeoutMs = 10_000,
                ),
            clock = clock,
        )
}
