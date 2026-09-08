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

/** Real application tool registration, ActionExecutor and AIAgent; scripted provider and runner. */
class AdaptiveHarnessAgentLoopTest {
    @Test fun `application agent creates evaluated adaptation and next task reuses it`() =
        runTest {
            val directory = Files.createTempDirectory("adaptive-agent")
            val client = HttpClient()
            try {
                val store = HarnessStore(directory.resolve("harness.sqlite"))
                val runner = object : HarnessRunner {
                    override val language = HarnessLanguage.KOTLIN

                    override suspend fun execute(
                        source: String,
                        observation: HarnessObservation,
                        sessionId: String,
                    ) = AdaptiveSessionHarness.expected(observation.text)
                }
                val base = SessionHarness(store, runner, FileArtifactStore(directory.resolve("artifacts")))
                val harness = AdaptiveSessionHarness(base, store, runner, "test-runtime")
                registerHarnessTools(harness)
                assertEquals(HarnessLabApprovalGate.TOOLS, ToolRegistry.listTools().map { it.descriptor.name }.toSet())
                ToolRegistry.register(object : SimpleTool<HarnessArguments>(typeToken<HarnessArguments>(), "json_query", "Synthetic answer fixture; revision is page index") {
                    override suspend fun execute(args: HarnessArguments) = "{\"answer\":${111 + args.revision.toInt()},\"noise\":\"${"x".repeat(10_000)}\"}"
                })
                val config = AgentConfig(provider = "openrouter", modelName = "test-model", profileDirectory = directory.toString())
                val database = DatabaseFactory.createInMemory()
                var task = 0
                var count = 0
                var seenTransformed = 0
                val adapter = object : KoogLlmAdapter(config) {
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
                        assertTrue(count <= 13)
                        if (pendingToolTurn?.result?.contains("[harness revision=") == true || messages.any { it.second.contains("[harness revision=") }) seenTransformed++

                        fun revision() =
                            store.transaction { db ->
                                db.prepareStatement("SELECT id FROM harness_revisions WHERE session=? ORDER BY rowid DESC LIMIT 1").use {
                                    it.setString(1, "adaptive-loop-$task")
                                    it.executeQuery().use { rows ->
                                        check(rows.next())
                                        rows.getString(1)
                                    }
                                }
                            }
                        val call = when {
                            count <= 2 -> NativeToolCall("read-$count", "json_query", """{"revision":"${count - 1}"}""")
                            count == 3 -> NativeToolCall("decide", "harness_adapt", """{"contractId":"answer-extraction-v1","toolName":"json_query","remainingObservations":4,"maxAddedLatencyMillis":300000}""")
                            task == 0 && count == 4 -> NativeToolCall("propose", "harness_propose", """{"source":"injected runner","toolName":"json_query"}""")
                            task == 0 && count == 5 -> NativeToolCall("evaluate", "harness_evaluate", """{"revision":"${revision()}"}""")
                            task == 0 && count == 6 -> NativeToolCall("activate", "harness_activate", """{"revision":"${revision()}"}""")
                            count in (if (task == 0) 7..10 else 4..7) -> NativeToolCall("read-$count", "json_query", """{"revision":"${count - if (task == 0) 5 else 2}"}""")
                            else -> null
                        }
                        return LlmResponse(content = if (call == null) "[111,112,113,114,115,116]" else "", promptTokens = 1, completionTokens = 1, provider = provider, model = model, toolCalls = listOfNotNull(call))
                    }
                }
                val gate = object : ApprovalGate {
                    override suspend fun check(
                        toolName: String,
                        args: String,
                        sessionId: String,
                    ) = ApprovalGate.ApprovalResult(toolName == "json_query", "local fixture only")
                }
                val executor = ActionExecutor(config, client, approvalGate = HarnessLabApprovalGate(gate), observationProcessor = harness)
                val skills = getProfileDirectoryPath(config) / "skills"
                val agent = AIAgent(config = config, database = database, llmAdapter = adapter, profileManager = ProfileManager(null), actionExecutor = executor, skillLoader = SkillLoader(getFileSystem(), skills), trajectoryEvaluator = TrajectoryEvaluator(adapter, config), skillWriter = SkillWriter(getFileSystem(), skills), maxIterations = AdaptiveSessionHarness.AGENT_ITERATIONS)
                repeat(2) {
                    task = it
                    count = 0
                    seenTransformed = 0
                    val response = AgentExecutionService(agent, database).executeToCompletion(AgentExecutionRequest(sessionId = "adaptive-loop-$task", text = "Return just the six answer values from the synthetic pages."))
                    assertEquals("[111,112,113,114,115,116]", response)
                    assertTrue(seenTransformed > 0)
                    assertEquals("original", harness.revisionKey("adaptive-loop-$task"))
                }
                val events = store.transaction { db ->
                    db.createStatement().use { statement -> statement.executeQuery("SELECT event FROM harness_events ORDER BY sequence").use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } } }
                }
                assertTrue(events.any { it.contains("\"choice\":\"CREATE\"") })
                assertTrue(events.any { it.contains("\"choice\":\"REUSE\"") })
                assertEquals(2, events.count { it.startsWith("adaptation_summary:") })
                assertEquals(1, HarnessAdaptationLibrary(store).entries(SessionHarness.sha256("")).size)
                registerHarnessTools(base)
                assertTrue(ToolRegistry.listTools().none { it.descriptor.name == "harness_adapt" })
            } finally {
                ToolRegistry.clear()
                client.close()
                directory.toFile().deleteRecursively()
            }
        }
}
