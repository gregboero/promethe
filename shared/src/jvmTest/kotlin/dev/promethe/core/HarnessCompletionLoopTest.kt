package dev.promethe.core

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.serialization.typeToken
import dev.promethe.api.AgentRunStatus
import dev.promethe.api.ReasoningEffort
import dev.promethe.core.tools.builtin.ClarifyTool
import dev.promethe.db.DatabaseFactory
import io.ktor.client.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class HarnessCompletionLoopTest {
    @Test fun `partial final fails before success and synthesis despite three successful tools`() = exercise(3, "[1]", true, "task_missing_page_reads")

    @Test fun `empty final fails without retry`() = exercise(0, "", true, "task_empty_final_response")

    @Test fun `complete wrong values pass completion independently of correctness`() = exercise(4, "[999,999,999,999]", true, null)

    @Test fun `ordinary tasks keep optional validation disabled`() = exercise(0, "done", false, null)

    @Test fun `clarification cannot bypass an explicit completion contract`() = exercise(0, "Which page?", true, "task_missing_page_reads", clarify = true)

    @Test fun `native clarification also respects explicit completion contract`() = exercise(0, "Which page?", true, "task_missing_page_reads", clarify = true, native = true)

    @Test fun `iteration exhaustion cannot end a guarded task successfully`() = exercise(10, "unused", true, "task_no_validated_final_response")

    private fun exercise(
        readCount: Int,
        final: String,
        guarded: Boolean,
        expectedCode: String?,
        clarify: Boolean = false,
        native: Boolean = false,
    ) = runTest {
        val directory = Files.createTempDirectory("harness-completion-loop")
        val client = HttpClient()
        try {
            ToolRegistry.clear()
            val config = AgentConfig(provider = "openrouter", modelName = "test-model", profileDirectory = directory.toString())
            val database = DatabaseFactory.createInMemory()
            val reads = mutableListOf<Int>()
            if (clarify) ToolRegistry.register(ClarifyTool())
            val store = HarnessStore(directory.resolve("harness.sqlite"))
            val harness = SessionHarness(store, HarnessRunner { _, observation, _ -> observation.text }, FileArtifactStore(directory.resolve("artifacts")))
            ToolRegistry.register(object : SimpleTool<HarnessPageArguments>(typeToken<HarnessPageArguments>(), "json_query", "Read fixture") {
                override suspend fun execute(args: HarnessPageArguments): String {
                    reads.add(args.page)
                    return "answer=${args.page}"
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
                    if (native) return LlmResponse("", 1, 1, model, provider, toolCalls = listOf(NativeToolCall("clarify-1", "clarify", """{"question":"Which page?"}""")))
                    val response = if (calls <= readCount) {
                        """{"action":{"tool_name":"json_query","args":{"page":${calls - 1}}}}"""
                    } else if (clarify) {
                        """{"action":{"tool_name":"clarify","args":{"question":"Which page?"}}}"""
                    } else {
                        final
                    }
                    return LlmResponse(response, 1, 1, model, provider)
                }
            }
            val gate = object : ApprovalGate {
                override suspend fun check(
                    toolName: String,
                    args: String,
                    sessionId: String,
                ) = ApprovalGate.ApprovalResult(toolName in setOf("json_query", "clarify"), "fixture")
            }
            val executor = ActionExecutor(config, client, approvalGate = gate, observationProcessor = harness)
            val skills = getProfileDirectoryPath(config) / "skills"
            val validator = if (guarded) AgentCompletionValidator { HarnessTaskCompletion.requireComplete(it, reads, 4) } else null
            val agent = AIAgent(config, database, adapter, ProfileManager(null), executor, SkillLoader(getFileSystem(), skills), TrajectoryEvaluator(adapter, config), SkillWriter(getFileSystem(), skills), completionValidator = validator)
            val events = AgentExecutionService(agent, database).execute(AgentExecutionRequest(sessionId = "completion-test", runId = "completion-run", text = "Read four pages")).toList()
            val run = requireNotNull(database.getAgentRun("completion-run"))
            if (expectedCode != null) {
                assertTrue(events.none { it is AgentExecutionEvent.Completed })
                assertEquals(expectedCode, events.filterIsInstance<AgentExecutionEvent.Failed>().single().code)
                assertEquals(AgentRunStatus.FAILED, run.status)
                assertEquals(expectedCode, run.errorCode)
                assertEquals(minOf(readCount + 1, 10), calls) // No retry or post-loop synthesis.
            } else {
                assertEquals(final, events.filterIsInstance<AgentExecutionEvent.Completed>().single().response)
                assertEquals(readCount + 1 + if (readCount >= 3) 1 else 0, calls)
            }
            assertEquals((0 until readCount).toList(), reads)
            assertEquals("original", harness.revisionKey("completion-test"))
            if (readCount < 10) assertTrue(database.getMessagesForSession("completion-test").any { it.role == "assistant" && it.content == final })
        } finally {
            ToolRegistry.clear()
            client.close()
            check(directory.toAbsolutePath().startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()))
            directory.toFile().deleteRecursively()
        }
    }
}
