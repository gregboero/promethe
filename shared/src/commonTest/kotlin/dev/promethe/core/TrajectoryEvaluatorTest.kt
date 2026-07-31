package dev.promethe.core

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrajectoryEvaluatorTest {
    @Test
    fun testShouldSynthesize_happyPath() {
        val evaluator = TrajectoryEvaluator(KoogLlmAdapter(AgentConfig()), AgentConfig())
        val trajectory =
            listOf(
                ConversationTrajectory(
                    inputs = mapOf("query" to "test"),
                    outputs = emptyMap(),
                    action = Action("write_file", JsonObject(emptyMap())),
                    observation = "File written",
                ),
                ConversationTrajectory(
                    inputs = mapOf("query" to "test"),
                    outputs = emptyMap(),
                    action = Action("read_file", JsonObject(emptyMap())),
                    observation = "File content...",
                ),
                ConversationTrajectory(
                    inputs = mapOf("query" to "test"),
                    outputs = emptyMap(),
                    action = Action("http_fetch", JsonObject(emptyMap())),
                    observation = "200 OK",
                ),
                ConversationTrajectory(
                    inputs = mapOf("query" to "test"),
                    outputs = mapOf("response" to "Done!"),
                    thought = "Task complete",
                ),
            )
        assertTrue(evaluator.shouldSynthesize(trajectory))
    }

    @Test
    fun testShouldSynthesize_withErrors_returnsFalse() {
        val evaluator = TrajectoryEvaluator(KoogLlmAdapter(AgentConfig()), AgentConfig())
        val trajectory =
            listOf(
                ConversationTrajectory(
                    inputs = mapOf("query" to "test"),
                    outputs = emptyMap(),
                    action = Action("write_file", JsonObject(emptyMap())),
                    observation = "[ERROR] Access denied",
                ),
                ConversationTrajectory(
                    inputs = mapOf("query" to "test"),
                    outputs = emptyMap(),
                    action = Action("read_file", JsonObject(emptyMap())),
                    observation = "File content...",
                ),
                ConversationTrajectory(
                    inputs = mapOf("query" to "test"),
                    outputs = emptyMap(),
                    action = Action("http_fetch", JsonObject(emptyMap())),
                    observation = "200 OK",
                ),
                ConversationTrajectory(
                    inputs = mapOf("query" to "test"),
                    outputs = mapOf("response" to "Failed"),
                    thought = "Giving up",
                ),
            )
        assertFalse(evaluator.shouldSynthesize(trajectory))
    }

    @Test
    fun testShouldSynthesize_tooFewSteps_returnsFalse() {
        val evaluator = TrajectoryEvaluator(KoogLlmAdapter(AgentConfig()), AgentConfig())
        val trajectory =
            listOf(
                ConversationTrajectory(
                    inputs = mapOf("query" to "hello"),
                    outputs = mapOf("response" to "Hi!"),
                    thought = "Simple greeting",
                ),
            )
        assertFalse(evaluator.shouldSynthesize(trajectory))
    }

    @Test
    fun testValidateSkillContent() {
        val evaluator = TrajectoryEvaluator(KoogLlmAdapter(AgentConfig()), AgentConfig())

        val valid =
            """
            # Skill: File Creation
            ## Objective
            Create files in workspace.
            ## Steps
            1. Use write_file
            """.trimIndent()
        assertTrue(evaluator.validateSkillContent(valid))

        val invalid = "Just some random text without structure"
        assertFalse(evaluator.validateSkillContent(invalid))
    }
}
