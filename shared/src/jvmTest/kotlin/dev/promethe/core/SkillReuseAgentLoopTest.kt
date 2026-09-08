package dev.promethe.core

import ai.koog.agents.core.tools.ToolDescriptor
import dev.promethe.api.*
import dev.promethe.db.DatabaseFactory
import io.ktor.client.HttpClient
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Real agent/context assembly with a deterministic provider; no claim of model quality improvement. */
class SkillReuseAgentLoopTest {
    @Test fun `evaluated skill is reused on two independent agent tasks after restart`() =
        runBlocking<Unit> {
            val directory = Files.createTempDirectory("skill-reuse")
            val http = HttpClient()
            try {
                val config = AgentConfig(provider = "openai", modelName = "fixture", profileDirectory = directory.toString())
                val skillsPath = getProfileDirectoryPath(config) / "skills"
                val fs = getFileSystem()
                val writer = SkillWriter(fs, skillsPath)
                val loader = SkillLoader(fs, skillsPath)
                writer.write(SkillEntry("invoice", description = "Calculate invoice totals", content = "INVOICE_RULE_V1: multiply the invoice integer by two. Return only the number."))
                val draft = loader.listSkills().single()
                writer.configureEvaluations("invoice", draft.contract.revisionHash!!, listOf(SkillEvaluationSuite("doubling", listOf(SkillEvaluationCase("one", "invoice 2", "4"), SkillEvaluationCase("two", "invoice 3", "6")))))
                val eval = SkillEvaluationService(
                    loader,
                    writer.governance,
                    SkillEvaluationSubject { skill, input ->
                        check(skill.content.contains("INVOICE_RULE_V1"))
                        (input.substringAfterLast(' ').toInt() * 2).toString()
                    },
                    "fixture-doubling-v1",
                ).evaluate("invoice", loader.listSkills().single().contract.revisionHash!!)
                assertEquals(SkillEvaluationStatus.PASSED, eval.status)
                for (lifecycle in listOf(SkillLifecycle.CANDIDATE, SkillLifecycle.ACTIVE)) {
                    val current = loader.listSkills().single()
                    assertNotNull(
                        writer.update(
                            current.copy(
                                contract = current.contract.copy(
                                    lifecycle = lifecycle,
                                    evaluatedRunId = eval.id,
                                    reviewedRevisionHash = current.contract.revisionHash,
                                    reviewedContentHash = current.contract.contentHash,
                                    reviewNote = "Verified distinct tasks and exact answers",
                                    reviewedAt = "2026-09-08",
                                ),
                            ),
                            expectedRevisionHash = current.contract.revisionHash,
                        ),
                    )
                }
                val reusedInputs = mutableListOf<String>()
                repeat(2) { task ->
                    val database = DatabaseFactory.createInMemory()
                    val reopenedLoader = SkillLoader(fs, skillsPath)
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
                            val input = messages.lastOrNull { it.first == "user" }?.second.orEmpty()
                            val output = if (systemPrompt.contains("INVOICE_RULE_V1") && input.contains("invoice")) {
                                reusedInputs += input
                                ((task + 5) * 2).toString()
                            } else {
                                "0"
                            }
                            return LlmResponse(output, 1, 1, model, provider)
                        }
                    }
                    val agent = AIAgent(config, database, adapter, ProfileManager(null), ActionExecutor(config, http), reopenedLoader, TrajectoryEvaluator(adapter, config), SkillWriter(fs, skillsPath))
                    val answer = AgentExecutionService(agent, database).executeToCompletion(AgentExecutionRequest(sessionId = "reuse-$task", text = "invoice ${task + 5}"))
                    assertEquals(((task + 5) * 2).toString(), answer)
                }
                assertEquals(2, reusedInputs.size)
                assertTrue(reusedInputs[0] != reusedInputs[1])
                assertEquals(1, SkillLoader(fs, skillsPath).listExecutableSkills().count { it.name == "invoice" })
            } finally {
                http.close()
                directory.toFile().deleteRecursively()
            }
        }
}
