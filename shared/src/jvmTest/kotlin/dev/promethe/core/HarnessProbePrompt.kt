package dev.promethe.core

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.serialization.typeToken
import dev.promethe.api.ReasoningEffort
import dev.promethe.db.DatabaseFactory
import java.nio.file.Files
import java.nio.file.Path

/** Capture the original arm's first turn locally, with no credentials, model or tool execution. */
internal suspend fun captureHarnessProbePrompt(
    directory: Path,
    task: String,
): Pair<String, List<Pair<String, String>>> {
    val client = io.ktor.client.HttpClient()
    var captured: Pair<String, List<Pair<String, String>>>? = null
    var calls = 0
    try {
        ToolRegistry.clear()
        ToolRegistry.register(object : SimpleTool<HarnessPageArguments>(typeToken<HarnessPageArguments>(), "json_query", "Read one numbered page (0 through 7) of this task. Returns an answer field, CSV answer column, DATA answer value, or result.value, plus optional irrelevant diagnostics. Read each page once.") {
            override suspend fun execute(args: HarnessPageArguments): String = error("Probe must never execute tools")
        })
        val config = AgentConfig(provider = "openai", modelName = "gpt-5.6-terra", profileDirectory = Files.createDirectories(directory.resolve("empty-profile")).toString())
        val database = DatabaseFactory.createInMemory()
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
                captured = systemPrompt to messages
                throw AgentExecutionException("probe_captured", "Offline capture complete")
            }
        }
        val skills = getProfileDirectoryPath(config) / "skills"
        val agent = AIAgent(config, database, adapter, ProfileManager(null), ActionExecutor(config, client), SkillLoader(getFileSystem(), skills), TrajectoryEvaluator(adapter, config), SkillWriter(getFileSystem(), skills))
        val failure = runCatching { AgentExecutionService(agent, database).executeToCompletion(AgentExecutionRequest("probe-capture", task)) }.exceptionOrNull()
        check((failure as? AgentExecutionException)?.code == "probe_captured" && calls == 1)
        return requireNotNull(captured)
    } finally {
        ToolRegistry.clear()
        client.close()
    }
}
