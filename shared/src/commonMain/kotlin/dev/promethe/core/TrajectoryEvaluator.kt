package dev.promethe.core

import dev.promethe.core.Log

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class TrajectoryEvaluator(
    private val llmAdapter: KoogLlmAdapter,
    private val config: AgentConfig,
) {
    private val logger = Log.create("TrajectoryEvaluator")

    /**
     * Détermine si une trajectoire mérite une synthèse de skill.
     * Conditions :
     * 1. Au moins 3 étapes avec un outil exécuté (action et observation non nulles)
     * 2. La dernière étape est une réponse finale (pas une erreur)
     * 3. Aucune observation ne contient "[ERROR]"
     */
    fun shouldSynthesize(trajectory: List<ConversationTrajectory>): Boolean {
        val toolSteps = trajectory.count { it.action != null && it.observation != null }
        val hasErrors =
            trajectory.any {
                it.observation?.contains("[ERROR]") == true
            }
        val hasResponse = trajectory.any { it.outputs.containsKey("response") }

        return toolSteps >= 3 && !hasErrors && hasResponse
    }

    /**
     * Génère un SKILL.md à partir de la trajectoire.
     * Utilise le LLM pour synthétiser les étapes en procédure réutilisable.
     */
    suspend fun synthesize(
        trajectory: List<ConversationTrajectory>,
        originalQuery: String,
    ): SkillEntry? =
        withContext(ioDispatcher) {
            // Construire le résumé de trajectoire pour le LLM
            val trajectoryText =
                trajectory
                    .mapIndexed { i, step ->
                        buildString {
                            append("Step ${i + 1}:\n")
                            step.thought?.let { append("  Thought: $it\n") }
                            step.action?.let { append("  Action: ${it.toolName}(${it.args})\n") }
                            step.observation?.let { append("  Observation: $it\n") }
                            step.outputs["response"]?.let { append("  Response: $it\n") }
                        }
                    }.joinToString("\n")

            val synthesisPrompt =
                """
                You are a skill extraction engine. Analyze the following successful 
                agent execution trajectory and produce a reusable SKILL.md document.
                
                ## Original User Query
                $originalQuery
                
                ## Execution Trajectory
                $trajectoryText
                
                ## Instructions
                Generate a Markdown document with this exact structure:
                
                # Skill: <concise skill name>
                
                ## Objective
                <one sentence describing what this skill accomplishes>
                
                ## Prerequisites
                <list any required tools, files, or conditions>
                
                ## Steps
                <numbered list of concrete actions to reproduce this task>
                
                ## Tools Used
                <list of tool names used>
                
                ## Notes
                <any edge cases, warnings, or tips learned from this execution>
                
                Output ONLY the Markdown document, nothing else.
                """.trimIndent()

            try {
                val response =
                    llmAdapter.complete(
                        systemPrompt = "You extract reusable procedural skills from agent trajectories.",
                        messages = listOf("user" to synthesisPrompt),
                        model = config.modelName,
                        temperature = 0.1,
                    )
                val content = response.content

                if (!validateSkillContent(content)) {
                    logger.warn { "Generated skill failed validation, discarding" }
                    return@withContext null
                }

                // Extraire le nom de la skill depuis le contenu
                val skillName = extractSkillName(content, originalQuery)

                SkillEntry(name = skillName, content = content)
            } catch (e: Exception) {
                logger.error(e) { "Skill synthesis failed" }
                null
            }
        }

    /**
     * Vérifie que le contenu généré a la structure minimale attendue.
     * Protège contre le skill drift.
     */
    fun validateSkillContent(content: String): Boolean {
        val requiredSections = listOf("# Skill:", "## Objective", "## Steps")
        return requiredSections.all { section ->
            content.contains(section, ignoreCase = true)
        }
    }

    /**
     * Extrait un nom de fichier propre depuis le contenu du skill.
     */
    private fun extractSkillName(
        content: String,
        fallbackQuery: String,
    ): String {
        // Chercher "# Skill: <name>" dans le contenu
        val regex = Regex("""#\s*Skill:\s*(.+)""")
        val match = regex.find(content)
        val rawName = match?.groupValues?.get(1)?.trim() ?: fallbackQuery.take(40)

        // Normaliser en snake_case pour le nom de fichier
        return rawName
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(50)
    }
}
