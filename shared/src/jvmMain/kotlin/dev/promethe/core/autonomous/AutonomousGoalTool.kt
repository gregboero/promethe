package dev.promethe.core.autonomous

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

// ── Argument data class ──────────────────────────────────────────

@Serializable
data class AutonomousGoalArgs(
    @property:LLMDescription(
        "L'objectif de haut niveau à accomplir. " +
            "Doit être clair et spécifique. L'agent le décomposera en sous-tâches.",
    )
    val goal: String,
    @property:LLMDescription("Nombre max de sous-tâches (défaut: 8).")
    val maxTasks: Int = 8,
    @property:LLMDescription("Preset de budget : MINIMAL, STANDARD, EXTENDED (défaut: STANDARD).")
    val budgetPreset: String = "STANDARD",
)

// ── Tool implementation ──────────────────────────────────────────

/**
 * AutonomousGoalTool — permet à l'agent de lancer un objectif autonome
 * directement depuis le chat.
 *
 * L'agent appelle ce tool quand une tâche est trop complexe pour une
 * seule itération. Le tool :
 * 1. Décompose l'objectif en sous-tâches via GoalDecomposer
 * 2. Retourne le plan d'exécution pour validation
 *
 * Note : l'exécution réelle se fait via le GoalRoutes/AutonomousExecutor.
 * Ce tool sert à la phase de décomposition uniquement pour que l'agent
 * puisse montrer le plan à l'utilisateur avant de lancer.
 */
class AutonomousGoalTool :
    SimpleTool<AutonomousGoalArgs>(
        argsType = typeToken<AutonomousGoalArgs>(),
        name = "autonomous_goal",
        description = "Break down a complex goal into sequential sub-tasks. " +
            "Use when a task requires multiple steps, files, or iterations. " +
            "Returns an execution plan that the user can validate before launching.",
    ) {
    private val decomposer = GoalDecomposer()

    override suspend fun execute(args: AutonomousGoalArgs): String {
        logger.info { "🎯 autonomous_goal: décomposition de '${args.goal.take(80)}'" }

        val prompt = decomposer.decompose(
            goal = args.goal,
            maxTasks = args.maxTasks,
        )

        // Retourner le prompt de décomposition pour que l'agent l'envoie au LLM
        // et parse la réponse dans la boucle de conversation normale
        return buildString {
            appendLine("=== Plan de décomposition ===")
            appendLine()
            appendLine("🎯 Objectif : ${args.goal}")
            appendLine("📋 Budget : ${args.budgetPreset}")
            appendLine("🔧 Max tâches : ${args.maxTasks}")
            appendLine()
            appendLine("Le prompt de décomposition a été préparé.")
            appendLine("Envoie le prompt suivant au LLM pour obtenir le plan détaillé :")
            appendLine()
            appendLine("--- SYSTEM PROMPT ---")
            appendLine(prompt.systemPrompt)
            appendLine()
            appendLine("--- USER PROMPT ---")
            appendLine(prompt.userPrompt)
            appendLine()
            appendLine("Après avoir reçu le plan JSON du LLM, présente-le à l'utilisateur ")
            appendLine("pour validation avant de lancer l'exécution autonome via /api/goal.")
        }
    }
}
