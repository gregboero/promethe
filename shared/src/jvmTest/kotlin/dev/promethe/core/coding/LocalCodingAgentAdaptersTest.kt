package dev.promethe.core.coding

import dev.promethe.core.ApprovalGate
import dev.promethe.api.CapabilityAuthentication
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalCodingAgentAdaptersTest {
    private val workspace = Path.of(System.getProperty("user.dir")).toAbsolutePath()
    private val approvalGate =
        object : ApprovalGate {
            override suspend fun check(
                toolName: String,
                args: String,
                sessionId: String,
            ) = ApprovalGate.ApprovalResult(true, "approved")
        }

    @Test
    fun `codex authentication is read through app server`() =
        runBlocking {
            val process =
                FakeProcess(
                    listOf(
                        """{"id":1,"result":{}}""",
                        """{"id":2,"result":{"data":[{"id":"promethe-readonly"}]}}""",
                        """{"id":3,"result":{"account":{"type":"chatgpt","email":null,"planType":"plus"},"requiresOpenaiAuth":true}}""",
                    ),
                )
            val factory = CapturingFactory(process)

            val status =
                CodexLocalAdapter(workspace, approvalGate, factory).authenticationStatus(
                    TrustedExecutable(Path.of("codex"), "hash"),
                )

            assertEquals(CapabilityAuthentication.AUTHENTICATED, status)
            assertEquals(listOf("app-server"), factory.arguments)
            assertTrue(process.written.any { it.contains("\"experimentalApi\":true") })
            assertTrue(process.written.any { it.contains("\"method\":\"permissionProfile/list\"") })
            assertTrue(process.written.any { it.contains("\"method\":\"account/read\"") })
        }

    @Test
    fun `codex uses app server stdin and relays action approval`() =
        runBlocking {
            val process =
                FakeProcess(
                    listOf(
                        """{"id":1,"result":{}}""",
                        """{"id":2,"result":{"config":{"mcp_servers":{"github":{"enabled":true}},"plugins":{"sample":{"enabled":true}},"apps":{"calendar":{"enabled":true}}}}}""",
                        """{"id":3,"result":{"thread":{"id":"thread-1"}}}""",
                        """{"id":4,"result":{"turn":{"id":"turn-1"}}}""",
                        """{"id":99,"method":"item/commandExecution/requestApproval","params":{"command":"git status"}}""",
                        """{"method":"item/completed","params":{"threadId":"thread-1","turnId":"turn-1","item":{"type":"agentMessage","text":"Done"}}}""",
                        """{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed"}}}""",
                    ),
                )
            val factory = CapturingFactory(process)
            val result =
                CodexLocalAdapter(workspace, approvalGate, factory).execute(
                    TrustedExecutable(Path.of("codex"), "hash"),
                    LocalCodingAgentRequest("Fix the parser", LocalCodingAccessMode.WORKSPACE_WRITE, "session-1"),
                )

            assertEquals(listOf("app-server"), factory.arguments)
            assertFalse(factory.arguments.contains("Fix the parser"))
            assertEquals("thread-1", result.externalSessionId)
            assertEquals("Done", result.summary)
            assertTrue(process.written.any { it.contains("\"method\":\"turn/start\"") && it.contains("Fix the parser") })
            assertTrue(
                process.written.any {
                    it.contains("\"method\":\"turn/start\"") &&
                        it.contains("\"permissions\":\"promethe-workspace\"") &&
                        it.contains("\"approvalPolicy\":\"on-request\"")
                },
            )
            assertTrue(
                process.written.any {
                    it.contains("\"method\":\"thread/start\"") &&
                        it.contains("\"github\":{\"enabled\":false}") &&
                        it.contains("\"sample\":{\"enabled\":false}") &&
                        it.contains("\"calendar\":{\"enabled\":false}")
                },
            )
            assertTrue(process.written.any { it.contains("\"id\":99") && it.contains("\"decision\":\"accept\"") })
        }

    @Test
    fun `codex reapplies read only permissions when resuming a thread`() =
        runBlocking {
            val process =
                FakeProcess(
                    listOf(
                        """{"id":1,"result":{}}""",
                        """{"id":2,"result":{"config":{}}}""",
                        """{"id":3,"result":{"thread":{"id":"thread-existing"}}}""",
                        """{"id":4,"result":{"turn":{"id":"turn-2"}}}""",
                        """{"method":"item/completed","params":{"threadId":"thread-existing","turnId":"turn-2","item":{"type":"agentMessage","text":"Reviewed"}}}""",
                        """{"method":"turn/completed","params":{"threadId":"thread-existing","turn":{"id":"turn-2","status":"completed"}}}""",
                    ),
                )
            val factory = CapturingFactory(process)

            CodexLocalAdapter(workspace, approvalGate, factory).execute(
                TrustedExecutable(Path.of("codex"), "hash"),
                LocalCodingAgentRequest(
                    task = "Review only",
                    accessMode = LocalCodingAccessMode.READ_ONLY,
                    prometheSessionId = "session-read",
                    externalSessionId = "thread-existing",
                ),
            )

            val resume = process.written.single { it.contains("\"method\":\"thread/resume\"") }
            val turn = process.written.single { it.contains("\"method\":\"turn/start\"") }
            assertTrue(resume.contains("\"permissions\":\"promethe-readonly\""))
            assertTrue(resume.contains("\"approvalPolicy\":\"on-request\""))
            assertTrue(turn.contains("\"permissions\":\"promethe-readonly\""))
            assertFalse(resume.contains("\"sandbox\""))
        }

    @Test
    fun `claude uses stream json and never puts the task in process arguments`() =
        runBlocking {
            val process =
                FakeProcess(
                    listOf(
                        """{"type":"assistant","message":{"content":[{"type":"text","text":"Editing"}]}}""",
                        """{"type":"result","is_error":false,"result":"Finished","session_id":"claude-session"}""",
                    ),
                )
            val factory = CapturingFactory(process)
            val result =
                ClaudeCodeLocalAdapter(workspace, approvalGate, factory).execute(
                    TrustedExecutable(Path.of("claude"), "hash"),
                    LocalCodingAgentRequest("Add a regression test", LocalCodingAccessMode.WORKSPACE_WRITE, "session-2"),
                )

            assertTrue(factory.arguments.containsAll(listOf("-p", "stream-json", "--permission-prompt-tool")))
            assertTrue(factory.arguments.contains("mcp__promethe_approval__approve"))
            assertTrue(factory.arguments.contains("--strict-mcp-config"))
            assertTrue(factory.arguments.contains("--no-chrome"))
            assertEquals("", factory.arguments[factory.arguments.indexOf("--setting-sources") + 1])
            assertEquals("1", factory.environment["CLAUDE_CODE_DISABLE_AUTO_MEMORY"])
            assertFalse(factory.arguments.contains("Add a regression test"))
            assertTrue(process.written.single().contains("Add a regression test"))
            assertEquals("claude-session", result.externalSessionId)
            assertEquals("Finished", result.summary)
        }

    @Test
    fun `dangerous local agent flags are absent from adapters`() =
        runBlocking {
            val process =
                FakeProcess(
                    listOf(
                        """{"type":"result","is_error":false,"result":"ok","session_id":"s"}""",
                    ),
                )
            val factory = CapturingFactory(process)
            ClaudeCodeLocalAdapter(workspace, approvalGate, factory).execute(
                TrustedExecutable(Path.of("claude"), "hash"),
                LocalCodingAgentRequest("Review", LocalCodingAccessMode.READ_ONLY, "session-3"),
            )
            val rendered = factory.arguments.joinToString(" ")
            assertFalse(rendered.contains("dangerously-skip-permissions"))
            assertFalse(rendered.contains("yolo"))
            assertFalse(rendered.contains("danger-full-access"))
            assertFalse(factory.arguments.contains("--mcp-config"))
            assertFalse(factory.arguments.contains("--strict-mcp-config"))
        }

    private class CapturingFactory(
        private val process: TrustedLocalAgentProcess,
    ) : TrustedLocalAgentProcessFactory {
        var arguments: List<String> = emptyList()
        var environment: Map<String, String> = emptyMap()

        override fun start(
            executable: TrustedExecutable,
            arguments: List<String>,
            workingDirectory: Path,
            extraEnvironment: Map<String, String>,
        ): TrustedLocalAgentProcess {
            this.arguments = arguments
            environment = extraEnvironment
            return process
        }
    }

    private class FakeProcess(
        lines: List<String>,
    ) : TrustedLocalAgentProcess {
        private val pending = ArrayDeque(lines)
        val written = mutableListOf<String>()

        override suspend fun sendLine(line: String) {
            written += line
        }

        override suspend fun readLine(timeoutMillis: Long): String? = pending.removeFirstOrNull()

        override suspend fun awaitExit(timeoutMillis: Long): Int = 0

        override fun stderr(): String = ""

        override fun cancel() = Unit
    }
}
