package dev.promethe.core

import dev.promethe.api.ReasoningEffort
import ai.koog.agents.core.tools.ToolDescriptor
import dev.promethe.api.AgentRunStatus
import dev.promethe.db.DatabaseFactory
import io.ktor.client.HttpClient
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class AgentRunTracingTest {
    @Test
    fun `complete agent run exports linked llm usage and tool spans`() =
        runTest {
            val exporter = InMemorySpanExporter.create()
            val provider =
                SdkTracerProvider
                    .builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build()
            val previousTelemetry = Tracing.telemetry
            val profileDirectory = Files.createTempDirectory("promethe-tracing-test")
            val httpClient = HttpClient()
            Tracing.telemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build()

            try {
                val config =
                    AgentConfig(
                        provider = "openrouter",
                        modelName = "nousresearch/hermes-3-llama-3.1-405b",
                        profileDirectory = profileDirectory.toString(),
                        tracingBackend = "none",
                    )
                val database = DatabaseFactory.createInMemory()
                val adapter = TracedScriptedAdapter(config)
                val actionExecutor = ActionExecutor(config, httpClient)
                val skillsDirectory = getProfileDirectoryPath(config) / "skills"
                val agent =
                    AIAgent(
                        config = config,
                        database = database,
                        llmAdapter = adapter,
                        profileManager = ProfileManager(null),
                        actionExecutor = actionExecutor,
                        skillLoader = SkillLoader(getFileSystem(), skillsDirectory),
                        trajectoryEvaluator = TrajectoryEvaluator(adapter, config),
                        skillWriter = SkillWriter(getFileSystem(), skillsDirectory),
                    )
                val service =
                    AgentExecutionService(
                        agent = agent,
                        database = database,
                        executionIdGenerator = ExecutionIdGenerator { "generated-run-id" },
                    )

                val events =
                    service.execute(
                        AgentExecutionRequest(
                            sessionId = "trace-session",
                            text = "Delete a protected file, then explain the result.",
                            runId = "trace-run-0001",
                        ),
                    ).toList()

                assertTrue(events.last() is AgentExecutionEvent.Completed, events.joinToString())
                val persistedRun = assertNotNull(database.getAgentRun("trace-run-0001"))
                assertEquals(AgentRunStatus.SUCCEEDED, persistedRun.status)
                assertEquals(3, persistedRun.stepCount)
                assertEquals("trace-run-0001-step-0003", persistedRun.lastStepId)

                val failedEvents =
                    service.execute(
                        AgentExecutionRequest(
                            sessionId = "trace-session",
                            text = " ",
                            runId = "trace-failed-0001",
                        ),
                    ).toList()
                assertTrue(failedEvents.last() is AgentExecutionEvent.Failed)
                val failedRun = assertNotNull(database.getAgentRun("trace-failed-0001"))
                assertEquals(AgentRunStatus.FAILED, failedRun.status)
                assertEquals("empty_input", failedRun.errorCode)

                val spans = exporter.finishedSpanItems
                val runSpan = spans.single { it.name == "agent.run" }
                val llmSpans = spans.filter { it.name == "gen_ai.chat" }
                val toolSpan = spans.single { it.name == "agent.tool.execute" }

                assertEquals(2, llmSpans.size)
                assertEquals("trace-run-0001", runSpan.attributes.string("promethe.run.id"))
                assertEquals(runSpan.spanId, toolSpan.parentSpanId)
                assertEquals("file_delete", toolSpan.attributes.string("tool.name"))
                assertEquals("trace-run-0001-step-0001", toolSpan.attributes.string("promethe.step.id"))
                llmSpans.forEach { span ->
                    assertEquals("trace-run-0001", span.attributes.string("promethe.run.id"))
                    assertNotNull(span.attributes.long("gen_ai.usage.prompt_tokens"))
                    assertNotNull(span.attributes.long("gen_ai.usage.completion_tokens"))
                    assertNotNull(span.attributes.double("gen_ai.usage.cost"))
                }

                val cancellingAdapter = CancellingAdapter(config)
                val cancellingAgent =
                    AIAgent(
                        config = config,
                        database = database,
                        llmAdapter = cancellingAdapter,
                        profileManager = ProfileManager(null),
                        actionExecutor = actionExecutor,
                        skillLoader = SkillLoader(getFileSystem(), skillsDirectory),
                        trajectoryEvaluator = TrajectoryEvaluator(cancellingAdapter, config),
                        skillWriter = SkillWriter(getFileSystem(), skillsDirectory),
                    )
                val cancellingService = AgentExecutionService(cancellingAgent, database)
                val collection =
                    launch {
                        cancellingService.execute(
                            AgentExecutionRequest(
                                sessionId = "trace-session",
                                text = "Wait for cancellation.",
                                runId = "trace-cancelled-0001",
                            ),
                        ).collect()
                    }
                cancellingAdapter.started.await()
                collection.cancelAndJoin()
                val cancelledRun = assertNotNull(database.getAgentRun("trace-cancelled-0001"))
                assertEquals(AgentRunStatus.CANCELLED, cancelledRun.status)
            } finally {
                Tracing.telemetry = previousTelemetry
                httpClient.close()
                provider.shutdown()
                profileDirectory.toFile().deleteRecursively()
            }
        }
}

private class CancellingAdapter(
    config: AgentConfig,
) : KoogLlmAdapter(config) {
    val started = CompletableDeferred<Unit>()

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
        started.complete(Unit)
        awaitCancellation()
    }
}

private class TracedScriptedAdapter(
    config: AgentConfig,
) : KoogLlmAdapter(config) {
    private var callCount = 0

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
    ): LlmResponse =
        Tracing.span(
            name = "gen_ai.chat",
            attributes =
                buildMap {
                    put("gen_ai.system", provider)
                    put("gen_ai.request.model", model)
                    context?.runId?.let { put("promethe.run.id", it) }
                    context?.stepId?.let { put("promethe.step.id", it) }
                },
        ) {
            callCount++
            val response =
                if (callCount == 1) {
                    LlmResponse(
                        content = "I need to use a protected operation.",
                        promptTokens = 20,
                        completionTokens = 8,
                        model = model,
                        provider = provider,
                        toolCalls =
                            listOf(
                                NativeToolCall(
                                    id = "tool-call-1",
                                    toolName = "file_delete",
                                    args = "{\"path\":\"protected.txt\"}",
                                ),
                            ),
                    )
                } else {
                    LlmResponse(
                        content = "The protected operation was blocked pending human approval.",
                        promptTokens = 28,
                        completionTokens = 11,
                        model = model,
                        provider = provider,
                    )
                }
            setAttribute("gen_ai.usage.prompt_tokens", response.promptTokens)
            setAttribute("gen_ai.usage.completion_tokens", response.completionTokens)
            setAttribute("gen_ai.usage.cost", response.estimateCost(3.0, 15.0))
            response
        }
}

private fun io.opentelemetry.api.common.Attributes.string(key: String): String? = get(AttributeKey.stringKey(key))

private fun io.opentelemetry.api.common.Attributes.long(key: String): Long? = get(AttributeKey.longKey(key))

private fun io.opentelemetry.api.common.Attributes.double(key: String): Double? = get(AttributeKey.doubleKey(key))
