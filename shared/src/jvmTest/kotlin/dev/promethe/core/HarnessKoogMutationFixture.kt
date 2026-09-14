package dev.promethe.core

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.serialization.typeToken
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMProvider
import dev.promethe.api.ReasoningEffort
import dev.promethe.api.ToolIntentStatus
import dev.promethe.db.DatabaseFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*

/** Optional Kotlin mutation through the real Koog adapter; the baseline has only synthetic reads. */
internal suspend fun runKoogMutationTask(
    directory: Path,
    session: String,
    freeMutation: Boolean,
    pages: List<String>,
    answers: List<Int>,
    relay: CampaignKoogRelay,
    store: HarnessStore,
    runner: HarnessRunner,
    metrics: List<KotlinHarnessMetrics>,
    taskText: String = KOOG_MUTATION_TASK,
    requireMutation: Boolean = false,
    conditionalExposure: Boolean = false,
    installedSource: String? = null,
    onFailure: (Exception) -> Unit = {},
): JsonObject {
    require(pages.size in 2..8 && answers.size == pages.size)
    require(!conditionalExposure || (freeMutation && !requireMutation))
    require(installedSource == null || (!freeMutation && !requireMutation))
    val started = System.nanoTime()
    val artifacts = FileArtifactStore(directory.resolve("artifacts"))
    var runnerInvocations = 0
    var failedRunnerInvocations = 0
    var runnerNanos = 0L
    val trackedRunner = object : HarnessRunner {
        override val language get() = runner.language

        override suspend fun endSession(sessionId: String) = runner.endSession(sessionId)

        override suspend fun execute(
            source: String,
            observation: HarnessObservation,
            sessionId: String,
        ): String = executeBatch(source, listOf(observation), sessionId).single()

        override suspend fun executeBatch(
            source: String,
            observations: List<HarnessObservation>,
            sessionId: String,
        ): List<String> {
            val start = System.nanoTime()
            runnerInvocations++
            return try {
                runner.executeBatch(source, observations, sessionId)
            } catch (error: Exception) {
                failedRunnerInvocations++
                throw error
            } finally {
                runnerNanos += System.nanoTime() - start
            }
        }
    }
    val harness = SessionHarness(store, trackedRunner, artifacts)
    val readHashes = mutableListOf<String>()
    val callPhases = mutableListOf<JsonObject>()
    val http = io.ktor.client.HttpClient()
    val config = AgentConfig(provider = "openai", modelName = "gpt-5.6-terra", reasoningEffort = ReasoningEffort.NONE, maxTokens = 4096, fallbackChainEnabled = false, profileDirectory = Files.createDirectories(directory.resolve(session)).toString())
    val database = DatabaseFactory.createInMemory()
    val sdk = OpenAILLMClient(apiKey = "local-relay-only", settings = OpenAIClientSettings(baseUrl = relay.baseUrl), httpClientFactory = getHttpClientFactory())
    var calls = 0
    var nativeCalls = 0
    val reads = mutableListOf<Int>()
    val observedSizes = mutableMapOf<Int, Int>()
    var harnessExposed = false
    var exposedAfterReads: Int? = null

    suspend fun exposeHarness() {
        if (harnessExposed) return
        ToolRegistry.register(HarnessInspectTool(harness))
        ToolRegistry.register(HarnessProposeTool(harness))
        ToolRegistry.register(HarnessEvaluateTool(harness))
        ToolRegistry.register(HarnessActivateTool(harness))
        ToolRegistry.register(HarnessRollbackTool(harness))
        ToolRegistry.register(HarnessDisableTool(harness))
        harnessExposed = true
        exposedAfterReads = observedSizes.size
    }
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
            check(++calls <= 18)
            callPhases.add(
                buildJsonObject {
                    put("call", calls)
                    put("agentStep", context?.stepId)
                    put("formalToolCount", tools.size)
                    put("harnessExposed", harnessExposed)
                },
            )
            relay.arm("$session-call-$calls")
            val fixedPrompt = systemPrompt.replace(Regex("(?s)(== CURRENT DATE & TIME ==).*?(== USER PROFILE ==)"), "\$1\n2026-09-07 (fixed experiment date)\n\$2")
            return super.completeWithProfile(fixedPrompt, messages, provider, model, temperature, tools.filter { it.name == "json_query" || (freeMutation && it.name in HarnessLabApprovalGate.TOOLS) }, context, pendingToolTurn, reasoningEffort).also { nativeCalls += it.toolCalls.size }
        }
    }
    try {
        ToolRegistry.clear()
        if (installedSource != null) {
            val request = ToolExecutionRequest("json_query", buildJsonObject {}, session, runId = session)
            val revision = Json.decodeFromString<HarnessRevision>(harness.command(request, "propose", HarnessArguments(source = installedSource, toolName = "json_query")))
            check(harness.command(request, "evaluate", HarnessArguments(revision = revision.id)).contains("passed=true"))
            check(harness.command(request, "activate", HarnessArguments(revision = revision.id)).startsWith("Pending"))
        }
        if (freeMutation && !conditionalExposure) exposeHarness()
        ToolRegistry.register(object : SimpleTool<HarnessPageArguments>(typeToken<HarnessPageArguments>(), "json_query", "Read one numbered page, 0 through ${pages.lastIndex}, once. Returns a JSON answer field and irrelevant diagnostics.") {
            override suspend fun execute(args: HarnessPageArguments): String {
                require(args.page in answers.indices)
                reads.add(args.page)
                observedSizes.putIfAbsent(args.page, pages[args.page].encodeToByteArray().size)
                if (conditionalExposure && HarnessExposurePolicy().shouldExpose(observedSizes.values.toList(), pages.size - observedSizes.size)) exposeHarness()
                return pages[args.page].also { readHashes.add(SessionHarness.sha256(it)) }
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
            ActionExecutor(config, http, approvalGate = HarnessLabApprovalGate(gate), toolIntentLedger = PersistentToolIntentLedger(database), artifactStore = artifacts, observationProcessor = if (freeMutation || installedSource != null) harness else null),
            SkillLoader(getFileSystem(), skills),
            TrajectoryEvaluator(adapter, config),
            SkillWriter(getFileSystem(), skills),
            maxIterations = 16,
            completionValidator = AgentCompletionValidator {
                final = it
                HarnessTaskCompletion.requireComplete(it, reads, pages.size)
                if (requireMutation) {
                    val events = koogMutationEvents(store, session)
                    if (events.none { it == "activated" } || events.count { it.startsWith("processed:") } != pages.size) {
                        throw AgentExecutionException("task_mutation_missing", "Directed control requires activation and transformed observations for every page")
                    }
                }
            },
        )
        try {
            final = AgentExecutionService(agent, database).executeToCompletion(AgentExecutionRequest(sessionId = session, runId = session, text = taskText))
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
        harness.endSession(session)
        ToolRegistry.clear()
        adapter.close()
        http.close()
    }
    val correct = runCatching { Json.parseToJsonElement(final).jsonArray.map { it.jsonPrimitive.int } == answers }.getOrDefault(false)
    val events = koogMutationEvents(store, session)
    val succeeded = database.getToolIntentsByStatus(setOf(ToolIntentStatus.SUCCEEDED)).filter { it.toolName == "json_query" }
    return buildJsonObject {
        put("session", session)
        put(
            "mode",
            if (requireMutation) {
                "directed"
            } else if (installedSource != null) {
                "reused"
            } else if (conditionalExposure) {
                "conditional"
            } else if (freeMutation) {
                "free"
            } else {
                "baseline"
            },
        )
        put("mutationRequired", requireMutation)
        put("exposedAfterReads", exposedAfterReads)
        put("expected", JsonArray(answers.map(::JsonPrimitive)))
        put("pagesPerTask", pages.size)
        put("runnerInvocations", runnerInvocations)
        put("failedRunnerInvocations", failedRunnerInvocations)
        put("runnerMillisIncludingFailures", runnerNanos / 1_000_000)
        put("phaseMetricsCoverage", "successful runner invocations only; failures included in runnerMillisIncludingFailures and totalMillis")
        put("proposals", events.count { it == "proposed" })
        put("activations", events.count { it == "activated" })
        put("processed", events.count { it.startsWith("processed:") })
        put("events", JsonArray(events.map(::JsonPrimitive)))
        put("compilations", metrics.count { !it.cacheHit })
        put("cacheHits", metrics.count { it.cacheHit })
        put("workerProcesses", metrics.sumOf { it.workerProcesses })
        put("compilationMillis", metrics.sumOf { it.compilationMillis })
        put("evaluationMillis", metrics.sumOf { it.evaluationMillis })
        put("stagingMillis", metrics.sumOf { it.stagingMillis })
        put("totalMillis", (System.nanoTime() - started) / 1_000_000)
        put("rawLedgerPreserved", readHashes.isNotEmpty() && succeeded.map { it.resultHash }.sortedBy { it } == readHashes.sorted())
        put("sessionCleaned", harness.revisionKey(session) == "original")
        put("cacheCleaned", (runner as? HarnessKotlinRunner)?.cachedEntries()?.let { it == 0 })
        put("callPhases", JsonArray(callPhases))
        put("calls", calls)
        put("nativeCalls", nativeCalls)
        put("readPages", JsonArray(reads.map(::JsonPrimitive)))
        put("completed", failure == null)
        put("failure", failure)
        put("final", final)
        put("correct", correct && reads.toSet() == answers.indices.toSet())
        put("completion", Json.encodeToJsonElement(HarnessTaskCompletion.assess(final, reads, pages.size)))
        put("registryCleaned", ToolRegistry.listTools().isEmpty())
    }
}

internal const val KOOG_MUTATION_TASK = "Read pages 0 through 3 using json_query, once each. Return only a JSON array of their four answer values in page order. Ignore irrelevant diagnostics. Accuracy and reading every page are required. Avoid unnecessary cost and delay. Optional harness tools, if available, are experiments you may use or ignore; changing a processor costs extra calls, compilation and validation time."

internal fun koogMutationEvents(
    store: HarnessStore,
    session: String,
): List<String> =
    store.transaction { db ->
        db.prepareStatement("SELECT event FROM harness_events WHERE session=? ORDER BY sequence").use { query ->
            query.setString(1, session)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

internal const val KOOG_DIRECTED_TASK = "This is an explicitly requested Kotlin harness lifecycle test. Before reading pages, inspect the harness and its validation examples, write your own Kotlin observation processor for json_query that returns the answer while removing irrelevant diagnostics and preserves unknown formats. Propose the source, evaluate it, and activate a passing revision. Then read pages 0 and 1 exactly once each through the active processor and return only a JSON array of their two answer values in page order. Activation and actual transformation of both pages are required; just reading the original pages does not complete this test. No network or file operations."

internal const val KOOG_LONG_MUTATION_TASK = "Read pages 0 through 7 using json_query, once each. Return only a JSON array of their eight answer values in page order. Ignore irrelevant diagnostics. Accuracy and reading every page are required. Avoid unnecessary cost and delay. Optional harness tools, if available, are experiments you may use or ignore; changing a processor costs extra calls, compilation and validation time."
