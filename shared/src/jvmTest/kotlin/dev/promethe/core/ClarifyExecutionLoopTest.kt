package dev.promethe.core

import ai.koog.agents.core.tools.ToolDescriptor
import dev.promethe.api.ReasoningEffort
import dev.promethe.core.tools.builtin.ClarifyTool
import dev.promethe.db.DatabaseFactory
import io.ktor.client.HttpClient
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ClarifyExecutionLoopTest {
    @AfterTest
    fun cleanup() =
        runTest {
            ToolRegistry.clear()
        }

    @Test
    fun `clarify tool completes the run without another llm call`() =
        runTest {
            val profileDirectory = Files.createTempDirectory("promethe-clarify-loop")
            val httpClient = HttpClient()
            try {
                ToolRegistry.register(ClarifyTool())
                val config =
                    AgentConfig(
                        provider = "openrouter",
                        modelName = "test-model",
                        profileDirectory = profileDirectory.toString(),
                    )
                val database = DatabaseFactory.createInMemory()
                val adapter = ClarifyScriptedAdapter(config)
                val skillsDirectory = getProfileDirectoryPath(config) / "skills"
                val agent =
                    AIAgent(
                        config = config,
                        database = database,
                        llmAdapter = adapter,
                        profileManager = ProfileManager(null),
                        actionExecutor = ActionExecutor(config, httpClient),
                        skillLoader = SkillLoader(getFileSystem(), skillsDirectory),
                        trajectoryEvaluator = TrajectoryEvaluator(adapter, config),
                        skillWriter = SkillWriter(getFileSystem(), skillsDirectory),
                    )
                val service = AgentExecutionService(agent, database)

                val response =
                    service.executeToCompletion(
                        AgentExecutionRequest(
                            sessionId = "discord-clarify-session",
                            text = "Help me choose a channel rule",
                            origin = AgentExecutionOrigin.CHANNEL,
                        ),
                    )

                assertEquals(
                    "Which channel should I monitor?\n\n1. This channel\n2. All channels",
                    response,
                )
                assertEquals(1, adapter.callCount)
                assertFalse("[CLARIFY]" in response)
                val messages = database.getMessagesForSession("discord-clarify-session")
                assertTrue(messages.any { it.role == "assistant" && it.content == response })
            } finally {
                httpClient.close()
                profileDirectory.toFile().deleteRecursively()
            }
        }
}

private class ClarifyScriptedAdapter(
    config: AgentConfig,
) : KoogLlmAdapter(config) {
    var callCount: Int = 0
        private set

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
        callCount++
        check(callCount == 1) { "Clarification must not trigger another LLM call" }
        return LlmResponse(
            content = "I need one detail before continuing.",
            promptTokens = 10,
            completionTokens = 4,
            model = model,
            provider = provider,
            toolCalls =
                listOf(
                    NativeToolCall(
                        id = "clarify-1",
                        toolName = "clarify",
                        args =
                            """{"question":"Which channel should I monitor?","suggestions":["This channel","All channels"]}""",
                    ),
                ),
        )
    }
}
