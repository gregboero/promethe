package dev.promethe.core

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.serialization.typeToken
import dev.promethe.api.ReasoningEffort
import dev.promethe.db.DatabaseFactory
import io.ktor.client.HttpClient
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class HarnessAgentLoopTest {
    @Test fun `agent changes observation processor using native calls`() = exerciseLoop(true)

    @Test fun `agent preserves JSON actions between mutation steps`() = exerciseLoop(false)

    private fun exerciseLoop(nativeCalls: Boolean) =
        runTest {
            val directory = Files.createTempDirectory("harness-agent")
            val client = HttpClient()
            try {
                val store = HarnessStore(directory.resolve("harness.sqlite"))
                val runner = HarnessRunner { _, observation, _ ->
                    when {
                        observation.text.startsWith("{") -> Json.parseToJsonElement(observation.text).jsonObject.getValue("answer").jsonPrimitive.content
                        observation.text.startsWith("id,answer") -> observation.text.substringAfterLast(',')
                        observation.text.startsWith("DATA answer=") -> observation.text.substringAfter('=')
                        else -> observation.text
                    }
                }
                val harness = SessionHarness(store, runner, FileArtifactStore(directory.resolve("artifacts")), preferSmallerObservations = false)
                ToolRegistry.register(HarnessInspectTool(harness))
                ToolRegistry.register(HarnessProposeTool(harness))
                ToolRegistry.register(HarnessEvaluateTool(harness))
                ToolRegistry.register(HarnessActivateTool(harness))
                assertEquals(listOf("harness_inspect", "harness_propose", "harness_evaluate", "harness_activate"), ToolRegistry.listTools().map { it.descriptor.name })
                ToolRegistry.register(object : SimpleTool<HarnessArguments>(typeToken<HarnessArguments>(), "json_query", "Synthetic JSON fixture") {
                    override suspend fun execute(args: HarnessArguments) = "{\"answer\":83,\"noise\":\"irrelevant\"}"
                })
                val config = AgentConfig(provider = "openrouter", modelName = "test-model", profileDirectory = directory.toString())
                val database = DatabaseFactory.createInMemory()
                val adapter = object : KoogLlmAdapter(config) {
                    var count = 0

                    override suspend fun completeWithProfile(
                        systemPrompt: String,
                        messages: List<Pair<String, String>>,
                        provider: String,
                        model: String,
                        temperature: Double,
                        tools: List<ToolDescriptor>,
                        context: LlmRequestContext?,
                        pendingToolTurn: PendingToolTurn?,
                        reasoningEffort: ReasoningEffort,
                    ): LlmResponse {
                        count++
                        if (!nativeCalls && count in 2..6) {
                            assertEquals(count - 1, messages.count { it.first == "assistant" && it.second.contains("tool_name") })
                        }

                        fun id() =
                            store.transaction { db ->
                                db.createStatement().use { statement ->
                                    statement.executeQuery("SELECT id FROM harness_revisions").use {
                                        it.next()
                                        it.getString(1)
                                    }
                                }
                            }
                        val call = when (count) {
                            1 -> {
                                NativeToolCall("inspect", "harness_inspect", "{}")
                            }

                            2 -> {
                                NativeToolCall("propose", "harness_propose", """{"source":"injected test runner","toolName":"json_query"}""")
                            }

                            3 -> {
                                NativeToolCall("evaluate", "harness_evaluate", """{"revision":"${id()}"}""")
                            }

                            4 -> {
                                NativeToolCall("activate", "harness_activate", """{"revision":"${id()}"}""")
                            }

                            5 -> {
                                assertEquals(id(), harness.revisionKey("loop-session"))
                                NativeToolCall("read", "json_query", "{}")
                            }

                            else -> {
                                null
                            }
                        }
                        if (count == 6) {
                            assertTrue(messages.any { it.second.contains("83\n[harness revision=") } || pendingToolTurn?.result?.contains("83\n[harness revision=") == true, "Transformed observation missing: messages=$messages; pending=$pendingToolTurn")
                        }
                        check(count <= 7) // The existing post-loop trajectory review makes one further call.
                        val content = when {
                            call == null -> "83"
                            nativeCalls -> ""
                            else -> """{"action":{"tool_name":"${call.toolName}","args":${call.args}}}"""
                        }
                        return LlmResponse(content = if (!nativeCalls && call != null) "$content trailing model marker" else content, promptTokens = 1, completionTokens = 1, provider = provider, model = model, toolCalls = if (nativeCalls) listOfNotNull(call) else emptyList())
                    }
                }
                val deny = object : ApprovalGate {
                    override suspend fun check(
                        toolName: String,
                        args: String,
                        sessionId: String,
                    ) = ApprovalGate.ApprovalResult(toolName == "json_query", "synthetic read fixture only")
                }
                val skills = getProfileDirectoryPath(config) / "skills"
                val executor = ActionExecutor(config, client, approvalGate = HarnessLabApprovalGate(deny), observationProcessor = harness)
                val agent = AIAgent(config = config, database = database, llmAdapter = adapter, profileManager = ProfileManager(null), actionExecutor = executor, skillLoader = SkillLoader(getFileSystem(), skills), trajectoryEvaluator = TrajectoryEvaluator(adapter, config), skillWriter = SkillWriter(getFileSystem(), skills))
                val response = AgentExecutionService(agent, database).executeToCompletion(AgentExecutionRequest(sessionId = "loop-session", text = "Exercise the processor lifecycle on the synthetic fixture."))
                assertEquals("83", response)
                assertEquals(7, adapter.count)
                assertEquals("original", harness.revisionKey("loop-session"))
            } finally {
                ToolRegistry.clear()
                client.close()
                directory.toFile().deleteRecursively()
            }
        }
}
