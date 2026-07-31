package dev.promethe.core.autonomous

import kotlinx.coroutines.*
import kotlin.time.Clock

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * AutonomousExecutor — exécute un plan de sous-tâches de manière autonome.
 *
 * Boucle autonome :
 * 1. Prend la prochaine tâche du plan
 * 2. Vérifie les budgets (tokens, coût, itérations)
 * 3. Exécute la tâche via l'agent
 * 4. Évalue le résultat (succès/échec/retry)
 * 5. Passe à la tâche suivante ou s'arrête
 *
 * Garde-fous :
 * - Budget max de tokens
 * - Budget max de coût ($)
 * - Nombre max d'itérations
 * - Timeout global
 * - Possibilité de pause/arrêt par l'utilisateur
 */
class AutonomousExecutor(
    private val budget: BudgetLimits = BudgetLimits(),
) {
    // ── État d'exécution ──

    private var _state = ExecutionState.IDLE
    val state: ExecutionState get() = _state

    private val taskResults = mutableListOf<TaskResult>()
    private var totalTokensUsed = 0L
    private var totalCost = 0.0
    private var totalIterations = 0
    private var startTimeMs = 0L

    @Volatile
    private var stopRequested = false

    // ── Types ──

    enum class ExecutionState {
        IDLE,
        RUNNING,
        PAUSED,
        COMPLETED,
        FAILED,
        BUDGET_EXCEEDED,
        STOPPED,
    }

    data class TaskResult(
        val taskIndex: Int,
        val taskTitle: String,
        val status: TaskStatus,
        val response: String,
        val tokensUsed: Long,
        val costIncurred: Double,
        val durationMs: Long,
        val retryCount: Int,
    )

    enum class TaskStatus {
        SUCCESS,
        FAILED,
        SKIPPED,
        BUDGET_EXCEEDED,
    }

    data class ExecutionSummary(
        val state: ExecutionState,
        val tasksCompleted: Int,
        val tasksFailed: Int,
        val tasksTotal: Int,
        val totalTokensUsed: Long,
        val totalCost: Double,
        val totalIterations: Int,
        val elapsedMs: Long,
        val results: List<TaskResult>,
        val budgetRemaining: BudgetRemaining,
    )

    data class BudgetRemaining(
        val tokensLeft: Long,
        val costLeft: Double,
        val iterationsLeft: Int,
    )

    /**
     * Exécute un plan de tâches de manière autonome.
     *
     * @param plan le plan d'exécution généré par GoalDecomposer
     * @param executeTask la lambda qui exécute une seule tâche et retourne (réponse, tokens, coût)
     * @return un résumé de l'exécution
     */
    suspend fun execute(
        plan: GoalDecomposer.ExecutionPlan,
        executeTask: suspend (task: GoalDecomposer.SubTask, context: String) -> TaskExecutionResult,
    ): ExecutionSummary {
        _state = ExecutionState.RUNNING
        stopRequested = false
        taskResults.clear()
        totalTokensUsed = 0
        totalCost = 0.0
        totalIterations = 0
        startTimeMs = Clock.System.now().toEpochMilliseconds()

        logger.info { "🚀 Mode autonome démarré: '${plan.goal}' (${plan.tasks.size} tâches)" }

        for (task in plan.tasks) {
            // Vérifier arrêt demandé
            if (stopRequested) {
                _state = ExecutionState.STOPPED
                logger.info { "⛔ Arrêt demandé par l'utilisateur" }
                break
            }

            // Vérifier les budgets
            val budgetCheck = checkBudget()
            if (budgetCheck != null) {
                _state = ExecutionState.BUDGET_EXCEEDED
                logger.warn { "💰 Budget dépassé: $budgetCheck" }
                taskResults.add(
                    TaskResult(task.index, task.title, TaskStatus.BUDGET_EXCEEDED, budgetCheck, 0, 0.0, 0, 0),
                )
                break
            }

            // Vérifier les dépendances
            val depsOk = task.dependsOn.all { depIdx ->
                taskResults.any { it.taskIndex == depIdx && it.status == TaskStatus.SUCCESS }
            }
            if (!depsOk) {
                logger.warn { "⏭️ Tâche ${task.index} '${task.title}' ignorée (dépendances non remplies)" }
                taskResults.add(
                    TaskResult(task.index, task.title, TaskStatus.SKIPPED, "Dépendances non remplies", 0, 0.0, 0, 0),
                )
                continue
            }

            // Construire le contexte des tâches précédentes
            val context = buildTaskContext(task, taskResults)

            // Exécuter avec retry
            var retryCount = 0
            var lastResult: TaskExecutionResult? = null

            while (retryCount <= budget.maxRetriesPerTask) {
                totalIterations++
                val taskStart = Clock.System.now().toEpochMilliseconds()

                try {
                    logger.info {
                        "📋 Tâche ${task.index}/${plan.tasks.size}: '${task.title}'" +
                            if (retryCount > 0) " (retry $retryCount)" else ""
                    }

                    lastResult = executeTask(task, context)
                    totalTokensUsed += lastResult.tokensUsed
                    totalCost += lastResult.cost

                    val duration = Clock.System.now().toEpochMilliseconds() - taskStart
                    taskResults.add(
                        TaskResult(
                            taskIndex = task.index,
                            taskTitle = task.title,
                            status = if (lastResult.success) TaskStatus.SUCCESS else TaskStatus.FAILED,
                            response = lastResult.response,
                            tokensUsed = lastResult.tokensUsed,
                            costIncurred = lastResult.cost,
                            durationMs = duration,
                            retryCount = retryCount,
                        ),
                    )

                    if (lastResult.success) {
                        val tokens = lastResult.tokensUsed
                        logger.info { "✅ Tâche ${task.index} terminée (${duration}ms, $tokens tokens)" }
                        break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "❌ Tâche ${task.index} échouée: ${e.message}" }
                    val duration = Clock.System.now().toEpochMilliseconds() - taskStart
                    lastResult = TaskExecutionResult(
                        success = false,
                        response = "Erreur: ${e.message}",
                        tokensUsed = 0,
                        cost = 0.0,
                    )
                    if (retryCount >= budget.maxRetriesPerTask) {
                        taskResults.add(
                            TaskResult(task.index, task.title, TaskStatus.FAILED, lastResult.response, 0, 0.0, duration, retryCount),
                        )
                    }
                }
                retryCount++
            }
        }

        if (_state == ExecutionState.RUNNING) {
            _state = ExecutionState.COMPLETED
        }

        val summary = buildSummary(plan.tasks.size)
        logger.info {
            "🏁 Mode autonome terminé: ${summary.tasksCompleted}/${summary.tasksTotal} tâches, " +
                "${summary.totalTokensUsed} tokens, \$${String.format("%.4f", summary.totalCost)}"
        }

        return summary
    }

    /**
     * Demande l'arrêt gracieux de l'exécution en cours.
     */
    fun requestStop() {
        stopRequested = true
        logger.info { "Stop requested for autonomous execution" }
    }

    /**
     * Remet l'exécuteur en état initial.
     */
    fun reset() {
        _state = ExecutionState.IDLE
        taskResults.clear()
        totalTokensUsed = 0
        totalCost = 0.0
        totalIterations = 0
        stopRequested = false
    }

    fun getSummary(totalTasks: Int): ExecutionSummary = buildSummary(totalTasks)

    // ── Internals ──

    private fun checkBudget(): String? {
        if (budget.maxTokens > 0 && totalTokensUsed >= budget.maxTokens) {
            return "Limite de tokens atteinte: $totalTokensUsed/${budget.maxTokens}"
        }
        if (budget.maxCostDollars > 0 && totalCost >= budget.maxCostDollars) {
            return "Limite de coût atteinte: \$${String.format("%.4f", totalCost)}/\$${budget.maxCostDollars}"
        }
        if (budget.maxIterations > 0 && totalIterations >= budget.maxIterations) {
            return "Limite d'itérations atteinte: $totalIterations/${budget.maxIterations}"
        }
        val elapsed = Clock.System.now().toEpochMilliseconds() - startTimeMs
        if (budget.maxDurationMs > 0 && elapsed >= budget.maxDurationMs) {
            return "Timeout global atteint: ${elapsed / 1000}s"
        }
        return null
    }

    private fun buildTaskContext(
        currentTask: GoalDecomposer.SubTask,
        previousResults: List<TaskResult>,
    ): String =
        buildString {
            appendLine("=== Contexte des tâches précédentes ===")
            for (result in previousResults.filter { it.status == TaskStatus.SUCCESS }) {
                appendLine("✅ Tâche ${result.taskIndex}: ${result.taskTitle}")
                appendLine("   Résultat: ${result.response.take(200)}")
                appendLine()
            }
            appendLine("=== Tâche en cours ===")
            appendLine("Titre: ${currentTask.title}")
            appendLine("Description: ${currentTask.description}")
        }

    private fun buildSummary(totalTasks: Int): ExecutionSummary {
        val elapsed = if (startTimeMs > 0) {
            Clock.System.now().toEpochMilliseconds() - startTimeMs
        } else {
            0L
        }

        return ExecutionSummary(
            state = _state,
            tasksCompleted = taskResults.count { it.status == TaskStatus.SUCCESS },
            tasksFailed = taskResults.count { it.status == TaskStatus.FAILED },
            tasksTotal = totalTasks,
            totalTokensUsed = totalTokensUsed,
            totalCost = totalCost,
            totalIterations = totalIterations,
            elapsedMs = elapsed,
            results = taskResults.toList(),
            budgetRemaining = BudgetRemaining(
                tokensLeft = if (budget.maxTokens > 0) budget.maxTokens - totalTokensUsed else Long.MAX_VALUE,
                costLeft = if (budget.maxCostDollars > 0) budget.maxCostDollars - totalCost else Double.MAX_VALUE,
                iterationsLeft = if (budget.maxIterations > 0) budget.maxIterations - totalIterations else Int.MAX_VALUE,
            ),
        )
    }
}

/**
 * Résultat de l'exécution d'une seule tâche.
 */
data class TaskExecutionResult(
    val success: Boolean,
    val response: String,
    val tokensUsed: Long,
    val cost: Double,
)
