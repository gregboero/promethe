package dev.promethe.core

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.serialization.typeToken
import dev.promethe.api.ReasoningEffort
import dev.promethe.db.DatabaseFactory
import io.ktor.client.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class HarnessExposureLoopTest {
    @Test fun `default loop still stops at ten steps`() = exercise(null, 8192)

    @Test fun `longer loop sees tools only after repeated large observations`() = exercise(16, 8192)

    @Test fun `moderate observations cannot amortize the tool exposure`() = exercise(16, 1200)

    @Test fun `small observations never expose mutation tools`() = exercise(16, 16)

    @Test fun `iteration budget rejects zero and excessive limits`() {
        assertFailsWith<IllegalArgumentException> { exercise(0, 1200) }
        assertFailsWith<IllegalArgumentException> { exercise(33, 1200) }
    }

    private fun exercise(
        limit: Int?,
        bytes: Int,
    ) = runTest {
        val directory = Files.createTempDirectory("harness-exposure-loop")
        val client = HttpClient()
        try {
            ToolRegistry.clear()
            val config = AgentConfig(provider = "openrouter", modelName = "test-model", profileDirectory = directory.toString())
            val database = DatabaseFactory.createInMemory()
            val store = HarnessStore(directory.resolve("harness.sqlite"))
            val harness = SessionHarness(store, HarnessRunner { _, observation, _ -> observation.text }, FileArtifactStore(directory.resolve("artifacts")))
            val sizes = mutableListOf<Int>()
            var exposed = false
            ToolRegistry.register(object : SimpleTool<HarnessPageArguments>(typeToken<HarnessPageArguments>(), "json_query", "Read a synthetic page") {
                override suspend fun execute(args: HarnessPageArguments): String {
                    assertEquals(sizes.size, args.page)
                    sizes.add(bytes)
                    if (!exposed && HarnessExposurePolicy().shouldExpose(sizes, 10 - sizes.size)) {
                        ToolRegistry.register(HarnessInspectTool(harness))
                        exposed = true
                    }
                    return "x".repeat(bytes)
                }
            })
            var calls = 0
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
                    calls++
                    if (calls <= 11) {
                        assertEquals(bytes >= 8192 && calls >= 3, systemPrompt.contains("harness_inspect"))
                    }
                    val content = if (calls <= 10) """{"action":{"tool_name":"json_query","args":{"page":${calls - 1}}}}""" else "done"
                    return LlmResponse(content, 1, 1, model, provider)
                }
            }
            val gate = object : ApprovalGate {
                override suspend fun check(
                    toolName: String,
                    args: String,
                    sessionId: String,
                ) = ApprovalGate.ApprovalResult(toolName == "json_query", "test fixture")
            }
            val executor = ActionExecutor(config, client, approvalGate = gate, observationProcessor = harness)
            val skills = getProfileDirectoryPath(config) / "skills"
            val loader = SkillLoader(getFileSystem(), skills)
            val evaluator = TrajectoryEvaluator(adapter, config)
            val writer = SkillWriter(getFileSystem(), skills)
            val agent = if (limit == null) AIAgent(config, database, adapter, ProfileManager(null), executor, loader, evaluator, writer) else AIAgent(config, database, adapter, ProfileManager(null), executor, loader, evaluator, writer, maxIterations = limit)
            val service = AgentExecutionService(agent, database)
            val request = AgentExecutionRequest(sessionId = "exposure-test", text = "Read ten pages and finish")
            if (limit == null) {
                val failure = assertFailsWith<AgentExecutionException> { service.executeToCompletion(request) }
                assertEquals("The agent completed without producing a final response", failure.message)
            } else {
                assertEquals("done", service.executeToCompletion(request))
            }
            assertEquals(10, sizes.size)
            assertEquals(if (limit == null) 10 else 12, calls)
            assertEquals("original", harness.revisionKey("exposure-test"))
        } finally {
            ToolRegistry.clear()
            client.close()
            check(directory.toAbsolutePath().startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()))
            directory.toFile().deleteRecursively()
        }
    }
}
