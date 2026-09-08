package dev.promethe.core

import dev.promethe.core.sandbox.JvmSandboxHelperProcessFactory
import dev.promethe.core.sandbox.NativeSandboxManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue

/** Explicit opt-in experiment. Ordinary tests make no provider calls. */
class HarnessLiveCampaignTest {
    @Test fun `real model drives existing agent mutation tools`() =
        runBlocking {
            assumeTrue(System.getenv("PROMETHE_HARNESS_LIVE") == "true")
            val output = Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_REPORT_DIR"))).toAbsolutePath()
            val credentials = requireNotNull(CredentialsStore.load())
            require(credentials.llmProvider == "openai" && credentials.llmModel == "gpt-5.6-terra")
            val budget = System.getenv("PROMETHE_HARNESS_BUDGET_DB")?.let(Path::of) ?: output.resolve("campaign-budget.sqlite")
            if (System.getenv("PROMETHE_HARNESS_LANGUAGE") == "kotlin") {
                check(System.getenv("PROMETHE_HARNESS_BUDGET_DB") != null && budget.isAbsolute && Files.isRegularFile(budget)) { "Kotlin continuation requires the existing shared campaign budget file" }
            }
            val store = HarnessStore(budget)
            val api = CampaignApi(store, credentials.llmApiKeys["openai"].orEmpty().ifBlank { credentials.llmApiKey }, output)
            val session = "agent-live-${UUID.randomUUID()}"
            NativeSandboxManager(Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_HELPER"))), JvmSandboxHelperProcessFactory).use { sandbox ->
                val status = sandbox.selfTest()
                check(status.available && status.selfTestPassed)
                val kotlin = System.getenv("PROMETHE_HARNESS_LANGUAGE") == "kotlin"
                val kotlinMetrics = mutableListOf<KotlinHarnessMetrics>()
                val scratch = Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_SCRATCH")))
                val runner: HarnessRunner = if (kotlin) HarnessKotlinRunner(sandbox, Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_KOTLIN_DIST"))), Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_JAVA_RUNTIME"))), scratch, metrics = { kotlinMetrics.add(it) }) else HarnessNodeRunner(sandbox, Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_NODE"))), scratch)
                check(runner.execute(if (kotlin) "observation.text" else "return observation.text;", HarnessObservation("json_query", "preflight"), session) == "preflight")
                kotlinMetrics.clear() // Keep preflight separate from actual agent observations.
                val artifacts = FileArtifactStore(output.resolve("artifacts"))
                val harness = SessionHarness(store, runner, artifacts)
                val raw = buildJsonObject {
                    put("answer", 1037)
                    put("noise", "irrelevant ".repeat(500))
                }.toString()
                val profile = Files.createDirectories(output.resolve(session))
                val config = AgentConfig(provider = "openai", modelName = "gpt-5.6-terra", profileDirectory = profile.toString())
                var calls = 0
                val adapter = object : KoogLlmAdapter(config) {
                    override suspend fun completeWithProfile(
                        systemPrompt: String,
                        messages: List<Pair<String, String>>,
                        provider: String,
                        model: String,
                        temperature: Double,
                        tools: List<ai.koog.agents.core.tools.ToolDescriptor>,
                        context: LlmRequestContext?,
                        pendingToolTurn: PendingToolTurn?,
                        reasoningEffort: dev.promethe.api.ReasoningEffort,
                    ): LlmResponse {
                        check(++calls <= 16)
                        val content = api.complete(systemPrompt, "agent-loop-$session-$calls", messages)
                        return LlmResponse(content, api.lastInputTokens, api.lastOutputTokens, model, provider)
                    }
                }
                val client = io.ktor.client.HttpClient()
                val database = dev.promethe.db.DatabaseFactory.createInMemory()
                val gate = object : ApprovalGate {
                    override suspend fun check(
                        toolName: String,
                        args: String,
                        sessionId: String,
                    ) = ApprovalGate.ApprovalResult(toolName == "json_query", "synthetic read fixture only")
                }
                try {
                    ToolRegistry.register(HarnessInspectTool(harness))
                    ToolRegistry.register(HarnessProposeTool(harness))
                    ToolRegistry.register(HarnessEvaluateTool(harness))
                    ToolRegistry.register(HarnessActivateTool(harness))
                    ToolRegistry.register(HarnessRollbackTool(harness))
                    ToolRegistry.register(HarnessDisableTool(harness))
                    ToolRegistry.register(object : ai.koog.agents.core.tools.SimpleTool<HarnessArguments>(ai.koog.serialization.typeToken<HarnessArguments>(), "json_query", "Read the synthetic observation for this experiment. No arguments are needed.") {
                        override suspend fun execute(args: HarnessArguments) = raw
                    })
                    val executor = ActionExecutor(config, client, approvalGate = HarnessLabApprovalGate(gate), toolIntentLedger = PersistentToolIntentLedger(database), artifactStore = artifacts, observationProcessor = harness)
                    val skills = getProfileDirectoryPath(config) / "skills"
                    val agent = AIAgent(config = config, database = database, llmAdapter = adapter, profileManager = ProfileManager(null), actionExecutor = executor, skillLoader = SkillLoader(getFileSystem(), skills), trajectoryEvaluator = TrajectoryEvaluator(adapter, config), skillWriter = SkillWriter(getFileSystem(), skills))
                    val final = AgentExecutionService(agent, database).executeToCompletion(AgentExecutionRequest(sessionId = session, text = "Synthetic harness experiment. First inspect the harness. Propose a ${runner.language} observation processor for toolName json_query that passes all the formats in inspect. Evaluate it, activate the validated revision, then call json_query and answer with only the extracted number. For each tool, output only JSON {\"action\":{\"tool_name\":\"...\",\"args\":{...}}}. Use only the listed harness tools and json_query. Source contract: ${harness.sourceDescription}. Do not stop until you have read the observation after activation."))
                    val processed = store.transaction { db ->
                        db.prepareStatement("SELECT COUNT(*) FROM harness_events WHERE session=? AND event LIKE 'processed:%'").use { q ->
                            q.setString(1, session)
                            q.executeQuery().use {
                                it.next()
                                it.getInt(1)
                            }
                        }
                    }
                    val intent = database.getToolIntentsByStatus(setOf(dev.promethe.api.ToolIntentStatus.SUCCEEDED)).firstOrNull { it.toolName == "json_query" }
                    val rawPreserved = intent?.resultHash == SessionHarness.sha256(raw)
                    val result = buildJsonObject {
                        put("session", session)
                        put("language", runner.language.name)
                        if (runner is HarnessKotlinRunner) {
                            put("cacheHits", kotlinMetrics.count { it.cacheHit })
                            put("compilations", kotlinMetrics.count { !it.cacheHit })
                            put("workerProcesses", kotlinMetrics.sumOf { it.workerProcesses })
                            put("cacheCleaned", runner.cachedEntries() == 0)
                        }
                        put("model", "gpt-5.6-terra")
                        put("calls", calls)
                        put("final", final)
                        put("processedObservations", processed)
                        put("rawLedgerHashPreserved", rawPreserved)
                        put("sessionCleaned", harness.revisionKey(session) == "original")
                        put("adapter", "live Chat Completions with the existing AIAgent JSON action protocol")
                    }
                    Files.writeString(output.resolve("agent-loop-result.json"), result.toString())
                    assertTrue(final.trim() == "1037" && processed > 0 && rawPreserved)
                    assertTrue(harness.revisionKey(session) == "original")
                    if (runner is HarnessKotlinRunner) assertTrue(runner.cachedEntries() == 0 && kotlinMetrics.any { it.cacheHit })
                } finally {
                    val transcript = JsonArray(
                        database.getMessagesForSession(session).map { message ->
                            buildJsonObject {
                                put("role", message.role)
                                put("content", message.content)
                            }
                        },
                    )
                    Files.writeString(output.resolve("$session-transcript.json"), transcript.toString())
                    ToolRegistry.clear()
                    client.close()
                    harness.endSession(session)
                }
            }
        }

    @Test fun `paired native mutation campaign with durable five dollar ceiling`() =
        runBlocking {
            assumeTrue("Set PROMETHE_HARNESS_LIVE=true for the explicitly funded campaign", System.getenv("PROMETHE_HARNESS_LIVE") == "true")
            val output = Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_REPORT_DIR"))).toAbsolutePath()
            Files.createDirectories(output)
            NativeSandboxManager(Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_HELPER"))), JvmSandboxHelperProcessFactory).use { sandbox ->
                val status = sandbox.selfTest()
                Files.writeString(output.resolve("native-preflight.json"), Json.encodeToString(status))
                assumeTrue("Native isolation required: ${status.message}", status.available && status.selfTestPassed)
                val store = HarnessStore(output.resolve("campaign-budget.sqlite"))
                val credentials = requireNotNull(CredentialsStore.load()) { "Configure a provider in Promethe first" }
                require(credentials.llmProvider == "openai" && credentials.llmModel == "gpt-5.6-terra") { "This experiment prices only the configured OpenAI gpt-5.6-terra model" }
                val key = credentials.llmApiKeys["openai"].orEmpty().ifBlank { credentials.llmApiKey }
                require(key.isNotBlank()) { "No OpenAI credential" }
                val api = CampaignApi(store, key, output)
                val scratch = Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_SCRATCH")))
                val runner = HarnessNodeRunner(sandbox, Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_NODE"))), scratch)
                // Even a configured backend must actually run the chosen Node binary before any charge.
                try {
                    check(runner.execute("return observation.text;", HarnessObservation("read_file", "preflight"), "preflight") == "preflight")
                } catch (error: HarnessExecutionException) {
                    Files.writeString(
                        output.resolve("node-launch-error.json"),
                        buildJsonObject {
                            put("message", error.message)
                            put("stderr", error.stderrPreview)
                        }.toString(),
                    )
                    throw error
                }
                val canary = Files.writeString(scratch.resolve("outside-invocation.txt"), "PRIVATE_CANARY")
                try {
                    val path = Json.encodeToString(canary.toString())
                    val denied = runner.execute("try { process.getBuiltinModule('fs').readFileSync($path); return 'LEAK'; } catch (e) { return e.code; }", HarnessObservation("read_file", ""), "preflight")
                    check(denied == "ERR_ACCESS_DENIED") { "Observation processor can read outside its invocation" }
                    val timeout = runCatching { runner.execute("while (true) {}", HarnessObservation("read_file", ""), "preflight") }
                    check((timeout.exceptionOrNull() as? HarnessExecutionException)?.timedOut == true) { "Infinite loop did not report a native timeout" }
                    Files.writeString(output.resolve("processor-preflight.json"), """{"nodeRan":true,"outsideReadDenied":true,"infiniteLoopTerminated":true,"nativeNetworkSelfTest":true}""")
                } finally {
                    Files.deleteIfExists(canary)
                }
                // Preserve the original September 6 comparison protocol when explicitly replayed.
                val harness = SessionHarness(store, runner, FileArtifactStore(output.resolve("artifacts")), preferSmallerObservations = false)
                val results = mutableListOf<JsonElement>()
                try {
                    for (family in listOf("json", "csv", "log")) {
                        for (repeat in 1..3) {
                            val session = "${UUID.randomUUID()}-$family-$repeat"
                            val request = ToolExecutionRequest("read_file", buildJsonObject {}, session, runId = session, stepId = "generate")
                            val answer = 1000 + repeat * 37
                            val raw = when (family) {
                                "json" -> buildJsonObject {
                                    put("answer", answer)
                                    put("noise", "irrelevant ".repeat(700))
                                }.toString()

                                "csv" -> "id,answer\n1,$answer"

                                else -> "DATA answer=$answer"
                            }
                            harness.beginStep(session, session, "generate")
                            try {
                                val baseline = api.complete("Return only the answer value in the following tool observation.\n$raw", "$session-baseline")
                                val instructions = harness.command(request, "inspect", HarnessArguments())
                                val generated = api.complete("Write only a JavaScript function body, no fences or explanation. $instructions", "$session-generation")
                                Files.writeString(output.resolve("$session-source.js"), generated)
                                val proposal = harness.command(request, "propose", HarnessArguments(source = generated))
                                val revision = Json.decodeFromString<HarnessRevision>(proposal)
                                val evaluation = harness.command(request, "evaluate", HarnessArguments(revision = revision.id))
                                var candidateAnswer: String? = null
                                var presented = raw
                                if (evaluation.contains("passed=true")) {
                                    check(harness.command(request, "activate", HarnessArguments(revision = revision.id)).startsWith("Pending"))
                                    harness.beginStep(session, session, "observe")
                                    presented = harness.process(request.copy(stepId = "observe"), raw).text
                                    candidateAnswer = api.complete("Return only the answer value in the following tool observation.\n$presented", "$session-mutation")
                                }
                                results.add(
                                    buildJsonObject {
                                        put("family", family)
                                        put("repeat", repeat)
                                        put("revision", revision.id)
                                        put("baselineCorrect", baseline.trim() == answer.toString())
                                        put("mutationCorrect", candidateAnswer?.trim() == answer.toString())
                                        put("evaluation", evaluation)
                                        put("rawBytes", raw.encodeToByteArray().size)
                                        put("presentedBytes", presented.encodeToByteArray().size)
                                    },
                                )
                            } finally {
                                harness.endSession(session)
                            }
                        }
                    }
                } finally {
                    Files.writeString(output.resolve("paired-results.json"), JsonArray(results).toString())
                }
                assertTrue(results.size == 9)
            }
        }
}

/** Synthetic text only; bounded request/output, no retries, secrets never persisted. */
internal class CampaignApi(
    private val store: HarnessStore,
    private val key: String,
    private val output: Path,
    private val transport: ((HttpRequest) -> CampaignHttpResponse)? = null,
) {
    var lastInputTokens: Int = 0
        private set
    var lastOutputTokens: Int = 0
        private set
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()

    fun complete(
        prompt: String,
        label: String,
        history: List<Pair<String, String>>? = null,
        omitReasoningEffort: Boolean = false,
    ): String {
        lastInputTokens = 0
        lastOutputTokens = 0
        val payload = buildJsonObject {
            put("model", "gpt-5.6-terra")
            if (!omitReasoningEffort) put("reasoning_effort", "none")
            put("store", false)
            put("max_completion_tokens", 4096)
            put("stream", false)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", if (history == null) "user" else "system")
                        put("content", prompt)
                    },
                )
                history?.forEach { (role, content) ->
                    add(
                        buildJsonObject {
                            put("role", role)
                            put("content", content)
                        },
                    )
                }
            }
        }.toString()
        val bytes = payload.encodeToByteArray().size
        require(bytes <= 64 * 1024) { "Synthetic text request exceeds experiment bound" }
        // UTF-8 bytes conservatively bound text tokens; 8192 covers chat framing.
        // Reserve double input/cache-write and 1.5x output prices, even below 272K.
        val reservation = (bytes + 8192L) * 5 + 4096L * 18
        val id = UUID.randomUUID().toString()
        if (!store.reserve(id, reservation)) throw AgentExecutionException("campaign_budget_exhausted", "Five USD campaign ceiling reached")
        val started = System.nanoTime()
        val request = HttpRequest.newBuilder(URI("https://api.openai.com/v1/chat/completions"))
            .timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build()
        val response = try {
            transport?.invoke(request) ?: client.send(request, HttpResponse.BodyHandlers.ofString()).let {
                CampaignHttpResponse(it.statusCode(), it.body(), it.headers().firstValue("x-request-id").orElse(null))
            }
        } catch (_: Exception) {
            null
        }
        val inspection = response?.let(CampaignResponseDiagnostics::inspect)
        val input = inspection?.inputTokens
        val completion = inspection?.outputTokens
        var errorCode = inspection?.errorCode ?: if (response == null) "campaign_transport_error" else null
        var accounted: Long? = null
        if (response?.status == 200 && input != null && completion != null) {
            val charge = (input * 5 + 1) / 2 + completion * 12
            if (charge <= reservation) {
                store.settle(id, charge)
                accounted = charge
                lastInputTokens = input.toInt()
                lastOutputTokens = completion.toInt()
            } else {
                errorCode = "campaign_usage_exceeds_reservation"
            }
        }
        val record = buildJsonObject {
            put("id", id)
            put("label", label)
            put("model", "gpt-5.6-terra")
            put("receiptVersion", 2)
            put("reasoningEffort", if (omitReasoningEffort) "provider_default" else "none")
            put("requestSha256", SessionHarness.sha256(payload))
            put("requestBytes", bytes)
            put("inputTokens", input?.let(::JsonPrimitive) ?: JsonNull)
            put("outputTokens", completion?.let(::JsonPrimitive) ?: JsonNull)
            put("accountedUpperBoundMicroUsd", accounted?.let(::JsonPrimitive) ?: JsonNull)
            put("accountingState", if (accounted == null) "reserved_uncertain" else "settled")
            put("errorCode", errorCode)
            put("diagnostics", inspection?.diagnostics ?: JsonNull)
            put("reservationMicroUsd", reservation)
            put("elapsedMillis", (System.nanoTime() - started) / 1_000_000)
            if (history != null) {
                put("historyMessages", history.size)
                if (errorCode == null) put("response", inspection?.text)
            }
        }
        Files.writeString(output.resolve("request-$id.json"), record.toString())
        if (errorCode != null) throw AgentExecutionException(errorCode, "Campaign response rejected: $errorCode")
        return requireNotNull(inspection?.text)
    }
}
