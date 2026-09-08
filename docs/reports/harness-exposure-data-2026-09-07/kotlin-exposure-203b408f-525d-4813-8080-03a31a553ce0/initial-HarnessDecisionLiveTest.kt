package dev.promethe.core

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.serialization.typeToken
import dev.promethe.api.ReasoningEffort
import dev.promethe.api.ToolIntentStatus
import dev.promethe.core.sandbox.JvmSandboxHelperProcessFactory
import dev.promethe.core.sandbox.NativeSandboxManager
import dev.promethe.db.DatabaseFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue

@Serializable
data class HarnessPageArguments(
    val page: Int,
)

/** Three arms, unseen values, identical tasks. Never run by ordinary jvmTest. */
class HarnessDecisionLiveTest {
    @Test fun `compare free mutation against original and fixed processor`() =
        runBlocking {
            assumeTrue(System.getenv("PROMETHE_HARNESS_LIVE") == "true")
            val output = Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_REPORT_DIR"))).toAbsolutePath()
            val kotlin = System.getenv("PROMETHE_HARNESS_LANGUAGE") == "kotlin"
            val exposure = System.getenv("PROMETHE_HARNESS_EXPOSURE") == "true"
            require(!exposure || kotlin) { "Exposure experiment requires Kotlin" }
            val repetitions = if (kotlin) 3 else 2
            val pageCount = if (exposure) 8 else 4
            val families = if (exposure) listOf("long_json", "small_mixed") else listOf("large_json", "small_mixed", "schema_shift")
            val seedBase = if (exposure) 99371 else 7391
            val iterationLimit = if (exposure) 16 else 10
            val fixedSource = if (kotlin) KOTLIN_FIXED_SOURCE else FIXED_SOURCE
            val batch = "${if (exposure) {
                "kotlin-exposure"
            } else if (kotlin) {
                "kotlin-decision"
            } else {
                "decision"
            }}-${UUID.randomUUID()}"
            val directory = Files.createDirectories(output.resolve(batch))
            // Deliberately reuse the earlier campaign ceiling, never a new budget for a retry.
            val budget = System.getenv("PROMETHE_HARNESS_BUDGET_DB")?.let(Path::of) ?: output.resolve("campaign-budget.sqlite")
            if (kotlin) check(budget.isAbsolute && Files.isRegularFile(budget) && System.getenv("PROMETHE_HARNESS_BUDGET_DB") != null) { "Kotlin continuation requires the existing shared budget" }
            val store = HarnessStore(budget)
            // Persist the design before any paid request, including an interrupted campaign.
            Files.writeString(
                directory.resolve("protocol.json"),
                buildJsonObject {
                    put("batch", batch)
                    put(
                        "protocolVersion",
                        if (exposure) {
                            5
                        } else if (kotlin) {
                            4
                        } else {
                            3
                        },
                    )
                    put("language", if (kotlin) "KOTLIN" else "JAVASCRIPT")
                    put("compilationCache", kotlin)
                    put("actionParser", "balanced-object-with-string-escapes")
                    put("model", "gpt-5.6-terra")
                    put("reasoningEffort", "none")
                    put("taskCount", repetitions * families.size)
                    put("plannedRuns", repetitions * families.size * 3)
                    put("repetitions", repetitions)
                    put("pagesPerTask", pageCount)
                    put("seedFormula", "$seedBase + repeat * 101 + familyIndex")
                    put("maxIterations", iterationLimit)
                    if (exposure) {
                        put("exposureRule", "two-distinct-observations-at-least1024Bytes-and-at-least4-remaining; sticky until session end")
                        put("modes", JsonArray(listOf("original", "free", "conditional").map(::JsonPrimitive)))
                        put("fixedArmIncluded", false)
                    }
                    put("campaignCapMicroUsd", 5_000_000)
                    put("fixedSource", fixedSource)
                    put("fixedSourceHash", SessionHarness.sha256(fixedSource))
                    put("fixedSourceModelGenerationIncluded", false)
                    put("presentationGuard", "below256Bytes-or-no-byte-saving")
                    put("fixedSetupIncluded", !exposure)
                    if (exposure) put("fixedSourceUsedOnlyForPreflight", true)
                    put("postLoopSkillSynthesisIncluded", true)
                }.toString(),
            )
            val credentials = requireNotNull(CredentialsStore.load())
            require(credentials.llmProvider == "openai" && credentials.llmModel == "gpt-5.6-terra")
            val api = CampaignApi(store, credentials.llmApiKeys["openai"].orEmpty().ifBlank { credentials.llmApiKey }, output)
            val results = mutableListOf<JsonElement>()
            NativeSandboxManager(Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_HELPER"))), JvmSandboxHelperProcessFactory).use { sandbox ->
                val status = sandbox.selfTest()
                check(status.available && status.selfTestPassed)
                val kotlinMetrics = mutableListOf<KotlinHarnessMetrics>()
                val scratch = Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_SCRATCH")))
                val native: HarnessRunner = if (kotlin) HarnessKotlinRunner(sandbox, Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_KOTLIN_DIST"))), Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_JAVA_RUNTIME"))), scratch, metrics = { kotlinMetrics.add(it) }) else HarnessNodeRunner(sandbox, Path.of(requireNotNull(System.getenv("PROMETHE_HARNESS_NODE"))), scratch)
                try {
                    check(native.execute(if (kotlin) "observation.text" else "return observation.text;", HarnessObservation("json_query", "preflight"), batch) == "preflight")
                    if (kotlin) {
                        val preflight = SessionHarness(store, native, FileArtifactStore(directory.resolve("preflight-artifacts")))
                        try {
                            val request = ToolExecutionRequest("json_query", buildJsonObject {}, batch)
                            val revision = Json.decodeFromString<HarnessRevision>(preflight.command(request, "propose", HarnessArguments(source = fixedSource, toolName = "json_query")))
                            check(preflight.command(request, "evaluate", HarnessArguments(revision = revision.id)).contains("passed=true")) { "Fixed processor must pass native fixtures before paid calls" }
                        } finally {
                            preflight.endSession(batch)
                        }
                    }
                } finally {
                    native.endSession(batch)
                }
                try {
                    for (repeat in 1..repetitions) {
                        for ((familyIndex, family) in families.withIndex()) {
                            val random = Random(seedBase + repeat * 101 + familyIndex)
                            val answers = List(pageCount) { random.nextInt(2000, 9000) }
                            val pages = answers.mapIndexed { page, answer ->
                                val noise = (1..if (exposure) 1200 else 2500).map { ('a'.code + random.nextInt(26)).toChar() }.joinToString("")
                                when {
                                    family == "small_mixed" && page % 2 == 0 -> "id,answer\n$page,$answer"
                                    family == "small_mixed" -> "DATA answer=$answer"
                                    family == "schema_shift" && page >= 2 -> """{"result":{"value":$answer},"diagnostics":"$noise"}"""
                                    else -> """{"noise":"$noise","answer":$answer}"""
                                }
                            }
                            // Rotate order so one mode does not systematically receive the warmest run.
                            Files.writeString(directory.resolve("$family-$repeat-corpus.json"), JsonArray(pages.map(::JsonPrimitive)).toString())
                            val modes = (if (exposure) listOf("original", "free", "conditional") else listOf("original", "fixed", "free")).let { it.drop((repeat + familyIndex) % 3) + it.take((repeat + familyIndex) % 3) }
                            for (mode in modes) {
                                val session = "$batch-$family-$repeat-$mode"
                                val artifacts = FileArtifactStore(directory.resolve("artifacts"))
                                kotlinMetrics.clear()
                                var nativeCalls = 0
                                var nativeNanos = 0L
                                val runner = object : HarnessRunner {
                                    override val language get() = native.language

                                    override suspend fun endSession(sessionId: String) = native.endSession(sessionId)

                                    override suspend fun execute(
                                        source: String,
                                        observation: HarnessObservation,
                                        sessionId: String,
                                    ): String {
                                        val start = System.nanoTime()
                                        nativeCalls++
                                        return try {
                                            native.execute(source, observation, sessionId)
                                        } finally {
                                            nativeNanos += System.nanoTime() - start
                                        }
                                    }

                                    override suspend fun executeBatch(
                                        source: String,
                                        observations: List<HarnessObservation>,
                                        sessionId: String,
                                    ): List<String> {
                                        // Keep the historical JavaScript protocol; Kotlin validates one batch per fresh worker.
                                        if (!kotlin) return observations.map { execute(source, it, sessionId) }
                                        val start = System.nanoTime()
                                        nativeCalls++
                                        return try {
                                            native.executeBatch(source, observations, sessionId)
                                        } finally {
                                            nativeNanos += System.nanoTime() - start
                                        }
                                    }
                                }
                                val harness = SessionHarness(store, runner, artifacts)
                                val database = DatabaseFactory.createInMemory()
                                val config = AgentConfig(provider = "openai", modelName = "gpt-5.6-terra", profileDirectory = Files.createDirectories(directory.resolve(session)).toString())
                                var modelCalls = 0
                                val exposureCalls = mutableListOf<JsonElement>()
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
                                        check(++modelCalls <= iterationLimit + 2)
                                        if (exposure) {
                                            exposureCalls.add(
                                                buildJsonObject {
                                                    put("call", modelCalls)
                                                    put("stepId", context?.stepId)
                                                    put("harnessPrompt", systemPrompt.contains("harness_inspect"))
                                                    put("registeredHarnessTools", ToolRegistry.listTools().count { it.name.startsWith("harness_") })
                                                },
                                            )
                                        }
                                        val content = api.complete(systemPrompt, "$session-call-$modelCalls", messages)
                                        return LlmResponse(content, api.lastInputTokens, api.lastOutputTokens, model, provider)
                                    }
                                }
                                val client = io.ktor.client.HttpClient()
                                val readHashes = mutableListOf<String>()
                                val readPages = mutableListOf<Int>()
                                val observedByteSizes = mutableListOf<Int>()
                                val exposurePolicy = HarnessExposurePolicy()
                                var exposedAfterReads: Int? = null

                                suspend fun exposeHarnessTools() {
                                    if (exposedAfterReads != null) return
                                    ToolRegistry.register(HarnessInspectTool(harness))
                                    ToolRegistry.register(HarnessProposeTool(harness))
                                    ToolRegistry.register(HarnessEvaluateTool(harness))
                                    ToolRegistry.register(HarnessActivateTool(harness))
                                    ToolRegistry.register(HarnessRollbackTool(harness))
                                    ToolRegistry.register(HarnessDisableTool(harness))
                                    exposedAfterReads = readPages.toSet().size
                                }
                                var final = ""
                                var failure: String? = null
                                val started = System.nanoTime()
                                try {
                                    ToolRegistry.register(object : SimpleTool<HarnessPageArguments>(typeToken<HarnessPageArguments>(), "json_query", "Read one numbered page (0 through ${pageCount - 1}) of this task. Returns an answer field, CSV answer column, DATA answer value, or result.value, plus optional irrelevant diagnostics. Read each page once.") {
                                        override suspend fun execute(args: HarnessPageArguments): String =
                                            pages[
                                                args.page.also {
                                                    require(it in 0 until pageCount)
                                                    readPages.add(it)
                                                },
                                            ].also {
                                                readHashes.add(SessionHarness.sha256(it))
                                                if (exposure && readPages.count { page -> page == args.page } == 1) {
                                                    observedByteSizes.add(it.encodeToByteArray().size)
                                                    if (mode == "conditional" && exposurePolicy.shouldExpose(observedByteSizes, pageCount - readPages.toSet().size)) exposeHarnessTools()
                                                }
                                            }
                                    })
                                    if (mode == "free") {
                                        exposeHarnessTools()
                                    }
                                    if (mode == "fixed") {
                                        val request = ToolExecutionRequest("json_query", buildJsonObject {}, session, runId = session, stepId = "setup")
                                        harness.beginStep(session, session, "setup")
                                        val revision = Json.decodeFromString<HarnessRevision>(harness.command(request, "propose", HarnessArguments(source = fixedSource, toolName = "json_query")))
                                        check(harness.command(request, "evaluate", HarnessArguments(revision = revision.id)).contains("passed=true"))
                                        check(harness.command(request, "activate", HarnessArguments(revision = revision.id)).startsWith("Pending"))
                                    }
                                    val gate = object : ApprovalGate {
                                        override suspend fun check(
                                            toolName: String,
                                            args: String,
                                            sessionId: String,
                                        ) = ApprovalGate.ApprovalResult(toolName == "json_query", "synthetic fixture only")
                                    }
                                    val executor = ActionExecutor(config, client, approvalGate = HarnessLabApprovalGate(gate), toolIntentLedger = PersistentToolIntentLedger(database), artifactStore = artifacts, observationProcessor = if (mode == "original") null else harness)
                                    val skills = getProfileDirectoryPath(config) / "skills"
                                    val agent = AIAgent(config, database, adapter, ProfileManager(null), executor, SkillLoader(getFileSystem(), skills), TrajectoryEvaluator(adapter, config), SkillWriter(getFileSystem(), skills), maxIterations = iterationLimit)
                                    // No instruction to inspect, generate code, activate or complete a mutation cycle.
                                    val task = "Read pages ${if (exposure) "0 through 7" else "0, 1, 2 and 3"} using json_query. For a tool call, output JSON {\"action\":{\"tool_name\":\"json_query\",\"args\":{\"page\":0}}} with the appropriate tool name and arguments. After reading all ${if (exposure) "eight" else "four"} pages, your final answer must be only a JSON array of the ${if (exposure) "eight" else "four"} answer values in page order. The answer is in answer or result.value, the CSV answer column, or DATA answer=. Ignore diagnostics. Accuracy and reading every page are required. Avoid unnecessary cost and delay. Optional harness tools, if available, are experiments you may use or ignore at your discretion; changing a processor costs extra calls and validation time."
                                    final = AgentExecutionService(agent, database).executeToCompletion(AgentExecutionRequest(sessionId = session, runId = session, text = task))
                                } catch (error: Exception) {
                                    failure = error.javaClass.simpleName // Never save arbitrary credential/transport messages.
                                } finally {
                                    harness.endSession(session)
                                    client.close()
                                    ToolRegistry.clear()
                                }
                                val elapsed = (System.nanoTime() - started) / 1_000_000
                                val events = store.transaction { db ->
                                    db.prepareStatement("SELECT event FROM harness_events WHERE session=? ORDER BY sequence").use { q ->
                                        q.setString(1, session)
                                        q.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
                                    }
                                }
                                val succeeded = database.getToolIntentsByStatus(setOf(ToolIntentStatus.SUCCEEDED)).filter { it.toolName == "json_query" }
                                val actual = runCatching { Json.parseToJsonElement(final.trim()).jsonArray.map { it.jsonPrimitive.int } }.getOrNull()
                                val record = buildJsonObject {
                                    put("session", session)
                                    put("family", family)
                                    put("repeat", repeat)
                                    put("mode", mode)
                                    put("correct", actual == answers && readPages.toSet() == (0 until pageCount).toSet())
                                    put("completed", failure == null)
                                    put("failure", failure)
                                    put("expected", JsonArray(answers.map(::JsonPrimitive)))
                                    put("final", final)
                                    put("modelCalls", modelCalls)
                                    if (exposure) {
                                        put("exposedAfterReads", exposedAfterReads?.let(::JsonPrimitive) ?: JsonNull)
                                        put("harnessPromptCalls", exposureCalls.count { it.jsonObject.getValue("harnessPrompt").jsonPrimitive.boolean })
                                        put("exposureCalls", JsonArray(exposureCalls))
                                        put("toolRegistryCleaned", ToolRegistry.listTools().isEmpty())
                                    }
                                    put("nativeCalls", nativeCalls)
                                    put("nativeMillis", nativeNanos / 1_000_000)
                                    if (kotlin) {
                                        put("cacheHits", kotlinMetrics.count { it.cacheHit })
                                        put("compilations", kotlinMetrics.count { !it.cacheHit })
                                        put("workerProcesses", kotlinMetrics.sumOf { it.workerProcesses })
                                        put("compilationMillis", kotlinMetrics.sumOf { it.compilationMillis })
                                        put("evaluationMillis", kotlinMetrics.sumOf { it.evaluationMillis })
                                        put("cacheCleaned", (native as HarnessKotlinRunner).cachedEntries() == 0)
                                    }
                                    put("totalMillis", elapsed)
                                    put("pagesRead", readHashes.size)
                                    put("distinctPagesRead", readPages.toSet().size)
                                    put("rawLedgerPreserved", readHashes.isNotEmpty() && succeeded.map { it.resultHash }.sortedBy { it } == readHashes.sorted())
                                    put("sessionCleaned", harness.revisionKey(session) == "original")
                                    put("proposals", events.count { it == "proposed" })
                                    put("activations", events.count { it == "activated" })
                                    put("processed", events.count { it.startsWith("processed:") })
                                    put("bypassedSmall", events.count { it == "bypassed_small" })
                                    put("bypassedNoSaving", events.count { it == "bypassed_no_saving" })
                                    put("events", JsonArray(events.map(::JsonPrimitive)))
                                }
                                results.add(record)
                                Files.writeString(directory.resolve("results.json"), JsonArray(results).toString())
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
                                check(mode != "original" || readPages.toSet() == (0 until pageCount).toSet()) { "Baseline did not read all pages; stop and inspect the protocol before spending on further runs" }
                            }
                        }
                    }
                } finally {
                    Files.writeString(directory.resolve("results.json"), JsonArray(results).toString())
                }
            }
            // Bad decisions/answers are findings, not a reason to discard or silently retry a cohort.
            assertTrue(results.size == repetitions * families.size * 3, "Incomplete cohort; retain evidence and the shared budget")
        }

    companion object {
        const val KOTLIN_FIXED_SOURCE = """val text = observation.text
val value = try { Json.parseToJsonElement(text) } catch (_: Exception) { null }
when {
    value is JsonObject && value["answer"] is JsonPrimitive -> (value["answer"] as JsonPrimitive).content
    text.startsWith("id,answer\n") -> text.substringAfter('\n').substringAfter(',')
    text.startsWith("DATA answer=") -> text.removePrefix("DATA answer=")
    else -> text
}"""

        const val FIXED_SOURCE = """const t = observation.text;
try { const v = JSON.parse(t); if (v && Object.prototype.hasOwnProperty.call(v, 'answer')) return String(v.answer); } catch (_) {}
if (t.startsWith('id,answer\n')) return t.split('\n')[1].split(',')[1];
if (t.startsWith('DATA answer=')) return t.slice('DATA answer='.length);
return t;"""
    }
}
