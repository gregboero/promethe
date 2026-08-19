package dev.promethe.gateway

import dev.promethe.core.autonomous.*
import dev.promethe.core.ToolCallOrigin
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}
private val goalJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

// ── DTOs ──

@Serializable
data class GoalStartRequest(
    val goal: String,
    val maxTasks: Int = 10,
    val budgetPreset: String = "STANDARD", // MINIMAL, STANDARD, EXTENDED, UNLIMITED
)

@Serializable
data class GoalStatusResponse(
    val state: String,
    val goal: String = "",
    val tasksTotal: Int = 0,
    val tasksCompleted: Int = 0,
    val tasksFailed: Int = 0,
    val currentTask: String = "",
    val totalTokens: Long = 0,
    val totalCost: Double = 0.0,
    val elapsedMs: Long = 0,
    val results: List<GoalTaskResult> = emptyList(),
)

@Serializable
data class GoalTaskResult(
    val index: Int,
    val title: String,
    val status: String,
    val response: String,
    val durationMs: Long,
)

/**
 * Mutable state of the goal currently being executed.
 *
 * One instance per [goalRoutes] mount (instead of file-level globals) so
 * concurrent test applications — or a future multi-mount — don't share state.
 */
class GoalState {
    @Volatile
    var goal: String = ""

    @Volatile
    var executor: AutonomousExecutor? = null

    @Volatile
    var job: Job? = null

    @Volatile
    var status: GoalStatusResponse = GoalStatusResponse(state = "IDLE")
}

/**
 * GoalRoutes — mode autonome via API.
 *
 * - POST /api/v1/goal       → lancer un objectif autonome
 * - GET  /api/v1/goal/status → état d'avancement
 * - POST /api/v1/goal/stop   → arrêt gracieux
 */
fun Route.goalRoutes(
    a2aClient: A2AInternalClient,
    state: GoalState = GoalState(),
) = goalRoutes(
    AcpExecutor { sessionId, text, channelHint ->
        a2aClient.execute(sessionId, text, channelHint, ToolCallOrigin.AUTONOMY)
    },
    state,
)

fun Route.goalRoutes(
    agentExecutor: AcpExecutor,
    state: GoalState = GoalState(),
) {
    post("/goal") {
        // Vérifie qu'aucun goal n'est en cours
        if (state.executor?.state == AutonomousExecutor.ExecutionState.RUNNING) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "A goal is already running"))
            return@post
        }

        val req = call.receive<GoalStartRequest>()
        if (req.goal.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Goal cannot be blank"))
            return@post
        }

        val budget = when (req.budgetPreset.uppercase()) {
            "MINIMAL" -> BudgetLimits.MINIMAL
            "EXTENDED" -> BudgetLimits.EXTENDED
            "UNLIMITED" -> BudgetLimits.UNLIMITED
            else -> BudgetLimits.STANDARD
        }

        state.goal = req.goal
        val decomposer = GoalDecomposer()
        val executor = AutonomousExecutor(budget)
        state.executor = executor

        state.status = GoalStatusResponse(
            state = "DECOMPOSING",
            goal = req.goal,
        )

        // Lancer en background
        state.job = application.launch {
            try {
                // Phase 1 : Décomposition via LLM
                logger.info { "🎯 Goal API: décomposition de '${req.goal.take(80)}'" }
                val prompt = decomposer.decompose(req.goal, maxTasks = req.maxTasks)

                // Appeler l'agent pour la décomposition
                val decomposeSessionId = "goal-decompose-${System.currentTimeMillis()}"
                val llmResponse = agentExecutor.execute(decomposeSessionId, prompt.userPrompt, "goal")
                val plan = decomposer.parseResponse(llmResponse, req.goal)

                state.status = state.status.copy(
                    state = "RUNNING",
                    tasksTotal = plan.tasks.size,
                )

                logger.info { "🎯 Goal API: plan de ${plan.tasks.size} tâches, exécution..." }

                // Phase 2 : Exécution autonome
                val summary = executor.execute(plan) { task, context ->
                    state.status = state.status.copy(
                        currentTask = "[${task.index}/${plan.tasks.size}] ${task.title}",
                    )

                    val taskPrompt = buildString {
                        appendLine("Tâche ${task.index}/${plan.tasks.size}: ${task.title}")
                        appendLine(task.description)
                        if (context.isNotBlank()) {
                            appendLine("\nContexte des tâches précédentes:")
                            appendLine(context)
                        }
                    }

                    val taskSessionId = "goal-task-${task.index}-${System.currentTimeMillis()}"
                    val response = agentExecutor.execute(taskSessionId, taskPrompt, "goal")
                    TaskExecutionResult(
                        success = true,
                        response = response,
                        tokensUsed = response.length.toLong(), // approximation
                        cost = 0.0,
                    )
                }

                // Mise à jour statut final
                state.status = GoalStatusResponse(
                    state = summary.state.name,
                    goal = req.goal,
                    tasksTotal = summary.tasksTotal,
                    tasksCompleted = summary.tasksCompleted,
                    tasksFailed = summary.tasksFailed,
                    currentTask = "",
                    totalTokens = summary.totalTokensUsed,
                    totalCost = summary.totalCost,
                    elapsedMs = summary.elapsedMs,
                    results = summary.results.map { r ->
                        GoalTaskResult(
                            index = r.taskIndex,
                            title = r.taskTitle,
                            status = r.status.name,
                            response = r.response.take(500),
                            durationMs = r.durationMs,
                        )
                    },
                )

                logger.info { "🎯 Goal API: terminé (${summary.state}, ${summary.tasksCompleted}/${summary.tasksTotal} réussies)" }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // POST /goal/stop cancelled us — /goal/stop already set the status.
                throw e
            } catch (e: Exception) {
                logger.error(e) { "🎯 Goal API: erreur" }
                state.status = state.status.copy(
                    state = "FAILED",
                )
            }
        }

        call.respond(mapOf("status" to "started", "goal" to req.goal))
    }

    get("/goal/status") {
        call.respond(state.status)
    }

    post("/goal/stop") {
        state.executor?.requestStop()
        state.job?.cancel()
        state.status = state.status.copy(state = "STOPPED")
        call.respond(mapOf("status" to "stopped"))
    }
}
