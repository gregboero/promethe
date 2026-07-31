package dev.promethe.core.autonomous

import kotlinx.serialization.Serializable

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * GoalDecomposer — décompose un objectif complexe en sous-tâches ordonnées.
 *
 * Utilise le LLM pour analyser un objectif de haut niveau et générer
 * un plan d'exécution séquentiel avec dépendances.
 *
 * Exemple :
 *   goal "Créer une API REST pour gérer les utilisateurs"
 *   → [1] Créer le schéma de base de données
 *   → [2] Implémenter les endpoints CRUD
 *   → [3] Ajouter la validation et l'auth
 *   → [4] Écrire les tests
 */
class GoalDecomposer {
    @Serializable
    data class SubTask(
        val index: Int,
        val title: String,
        val description: String,
        val dependsOn: List<Int> = emptyList(),
        val estimatedComplexity: Complexity = Complexity.MEDIUM,
    )

    @Serializable
    enum class Complexity { LOW, MEDIUM, HIGH }

    @Serializable
    data class ExecutionPlan(
        val goal: String,
        val tasks: List<SubTask>,
        val estimatedTotalSteps: Int,
    )

    /**
     * Décompose un objectif en sous-tâches via le LLM.
     *
     * @param goal l'objectif de haut niveau en langage naturel
     * @param context contexte supplémentaire (fichiers ouverts, historique, etc.)
     * @param maxTasks nombre maximum de sous-tâches à générer
     * @return un plan d'exécution ordonné
     */
    fun decompose(
        goal: String,
        context: String = "",
        maxTasks: Int = 10,
    ): DecomposePrompt {
        logger.info { "Decomposing goal: '${goal.take(80)}' (maxTasks=$maxTasks)" }

        val systemPrompt =
            """
            Tu es un planificateur expert. Décompose l'objectif suivant en sous-tâches 
            séquentielles et atomiques. Chaque sous-tâche doit être réalisable en une 
            seule itération d'un agent.
            
            Règles :
            - Maximum $maxTasks sous-tâches
            - Chaque tâche a : index, title, description, dependsOn (indices), complexity (LOW/MEDIUM/HIGH)
            - L'ordre doit respecter les dépendances
            - Sois concis et précis
            - Réponds en JSON strict
            """.trimIndent()

        val userPrompt = buildString {
            appendLine("OBJECTIF : $goal")
            if (context.isNotBlank()) {
                appendLine()
                appendLine("CONTEXTE :")
                appendLine(context)
            }
            appendLine()
            appendLine("Génère le plan au format JSON :")
            appendLine(
                """{"tasks": [{"index": 1, "title": "...", "description": "...", "dependsOn": [], "estimatedComplexity": "MEDIUM"}, ...]}""",
            )
        }

        return DecomposePrompt(systemPrompt, userPrompt, goal, maxTasks)
    }

    /**
     * Retour intermédiaire contenant les prompts à envoyer au LLM.
     * La couche appelante envoie au LLM et parse le JSON résultat.
     */
    data class DecomposePrompt(
        val systemPrompt: String,
        val userPrompt: String,
        val originalGoal: String,
        val maxTasks: Int,
    )

    /**
     * Parse la réponse JSON du LLM en ExecutionPlan.
     */
    fun parseResponse(
        llmResponse: String,
        originalGoal: String,
    ): ExecutionPlan =
        try {
            val json = kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                isLenient = true
            }

            // Extraire le JSON du markdown si nécessaire
            val jsonStr = extractJson(llmResponse)
            val parsed = json.decodeFromString<TasksWrapper>(jsonStr)

            ExecutionPlan(
                goal = originalGoal,
                tasks = parsed.tasks,
                estimatedTotalSteps = parsed.tasks.size,
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse LLM decomposition response, creating single-task plan" }
            // Fallback : une seule tâche = l'objectif entier
            ExecutionPlan(
                goal = originalGoal,
                tasks = listOf(
                    SubTask(
                        index = 1,
                        title = originalGoal,
                        description = originalGoal,
                    ),
                ),
                estimatedTotalSteps = 1,
            )
        }

    @Serializable
    private data class TasksWrapper(
        val tasks: List<SubTask>,
    )

    private fun extractJson(text: String): String {
        // Essayer d'extraire un bloc JSON markdown
        val codeBlock = Regex("```(?:json)?\\s*\\n?(\\{.*?\\})\\s*```", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1)
        if (codeBlock != null) return codeBlock

        // Sinon chercher le premier { ... }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) return text.substring(start, end + 1)

        return text
    }
}
