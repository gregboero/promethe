package dev.promethe.core

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.serialization.typeToken
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMProvider
import dev.promethe.api.ReasoningEffort
import dev.promethe.db.DatabaseFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*

/** Same real adapter and endpoint in both arms; only formal tool declarations differ. */
internal suspend fun runKoogProtocolTask(
    directory: Path,
    session: String,
    structured: Boolean,
    answers: List<Int>,
    relay: CampaignKoogRelay,
    onFailure: (Exception) -> Unit = {},
): JsonObject {
    val http = io.ktor.client.HttpClient()
    val config = AgentConfig(provider = "openai", modelName = "gpt-5.6-terra", reasoningEffort = ReasoningEffort.NONE, maxTokens = 4096, fallbackChainEnabled = false, profileDirectory = Files.createDirectories(directory.resolve(session)).toString())
    val database = DatabaseFactory.createInMemory()
    val sdk = OpenAILLMClient(apiKey = "local-relay-only", settings = OpenAIClientSettings(baseUrl = relay.baseUrl), httpClientFactory = getHttpClientFactory())
    var calls = 0
    var nativeCalls = 0
    val reads = mutableListOf<Int>()
    var final = ""
    var failure: String? = null
    val adapter = object : KoogLlmAdapter(config, executor = MultiLLMPromptExecutor(LLMProvider.OpenAI to sdk)) {
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
            check(++calls <= 6)
            relay.arm("$session-call-$calls")
            val fixedPrompt = systemPrompt.replace(Regex("(?s)(== CURRENT DATE & TIME ==).*?(== USER PROFILE ==)"), "\$1\n2026-09-07 (fixed experiment date)\n\$2")
            return super.completeWithProfile(fixedPrompt, messages, provider, model, temperature, if (structured) tools.filter { it.name == "json_query" } else emptyList(), context, pendingToolTurn, reasoningEffort).also { nativeCalls += it.toolCalls.size }
        }
    }
    try {
        ToolRegistry.clear()
        ToolRegistry.register(object : SimpleTool<HarnessPageArguments>(typeToken<HarnessPageArguments>(), "json_query", "Read one numbered page, 0 or 1, once. Returns a JSON answer field.") {
            override suspend fun execute(args: HarnessPageArguments): String {
                require(args.page in answers.indices)
                reads.add(args.page)
                return """{"answer":${answers[args.page]}}"""
            }
        })
        val gate = object : ApprovalGate {
            override suspend fun check(
                toolName: String,
                args: String,
                sessionId: String,
            ) = ApprovalGate.ApprovalResult(toolName == "json_query", "Synthetic page only")
        }
        val skills = getProfileDirectoryPath(config) / "skills"
        val agent = AIAgent(
            config,
            database,
            adapter,
            ProfileManager(null),
            ActionExecutor(config, http, approvalGate = gate),
            SkillLoader(getFileSystem(), skills),
            TrajectoryEvaluator(adapter, config),
            SkillWriter(getFileSystem(), skills),
            maxIterations = 6,
            completionValidator = AgentCompletionValidator {
                final = it
                HarnessTaskCompletion.requireComplete(it, reads, 2)
            },
        )
        try {
            final = AgentExecutionService(agent, database).executeToCompletion(AgentExecutionRequest(session, "Read pages 0 and 1 using json_query, once each. After reading both, return only a JSON array of their two answer values in page order."))
        } catch (error: Exception) {
            onFailure(error)
            failure = (error as? AgentExecutionException)?.code ?: error.javaClass.simpleName
        }
        Files.writeString(
            directory.resolve("$session-transcript.json"),
            JsonArray(
                database.getMessagesForSession(session).map {
                    buildJsonObject {
                        put("role", it.role)
                        put("content", it.content)
                    }
                },
            ).toString(),
        )
    } finally {
        ToolRegistry.clear()
        adapter.close()
        http.close()
    }
    val correct = runCatching { Json.parseToJsonElement(final).jsonArray.map { it.jsonPrimitive.int } == answers }.getOrDefault(false)
    return buildJsonObject {
        put("session", session)
        put("mode", if (structured) "structured" else "text")
        put("calls", calls)
        put("nativeCalls", nativeCalls)
        put("readPages", JsonArray(reads.map(::JsonPrimitive)))
        put("completed", failure == null)
        put("failure", failure)
        put("final", final)
        put("correct", correct && reads.toSet() == answers.indices.toSet())
        put("completion", Json.encodeToJsonElement(HarnessTaskCompletion.assess(final, reads, 2)))
        put("registryCleaned", ToolRegistry.listTools().isEmpty())
    }
}
