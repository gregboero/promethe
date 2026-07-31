package dev.promethe.core.tools.builtin

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.promethe.core.CheckpointManager
import dev.promethe.core.CheckpointManager.AgentCheckpoint
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable

private val logger = KotlinLogging.logger {}

// ══════════════════════════════════════════════════════════════
// Outils de checkpoint :
//   - CheckpointSaveTool  → sauvegarder un checkpoint de progression
//   - CheckpointListTool  → vérifier l'existence d'un checkpoint
// ══════════════════════════════════════════════════════════════

// ── CheckpointSaveTool ─────────────────────────────────────

@Serializable
data class CheckpointSaveArgs(
    @property:LLMDescription("Identifiant de la session à sauvegarder.")
    val sessionId: String,
    @property:LLMDescription("Libellé lisible décrivant ce checkpoint (optionnel).")
    val label: String = "",
    @property:LLMDescription("État supplémentaire à persister au format JSON (optionnel).")
    val stateJson: String = "{}",
)

/**
 * CheckpointSaveTool — permet à l'agent de sauvegarder un checkpoint
 * de sa progression courante.
 *
 * Le checkpoint est stocké via [CheckpointManager]. Le numéro d'itération
 * est incrémenté automatiquement à partir du dernier checkpoint existant.
 * Le libellé est injecté dans le champ `currentInput` pour le conserver
 * de façon lisible.
 */
class CheckpointSaveTool(
    private val checkpointManager: CheckpointManager,
) : SimpleTool<CheckpointSaveArgs>(
        argsType = typeToken<CheckpointSaveArgs>(),
        name = "checkpoint_save",
        description = "Save a checkpoint of the agent's current progress. " +
            "Use to preserve state before risky operations or at regular intervals.",
    ) {
    override suspend fun execute(args: CheckpointSaveArgs): String =
        try {
            // Déterminer le prochain stepIndex / iteration à partir du dernier checkpoint
            val existing = checkpointManager.load(args.sessionId)
            val nextStep = (existing?.stepIndex ?: 0) + 1
            val nextIteration = (existing?.iteration ?: 0) + 1

            // Construire le contenu du champ currentInput (label + state)
            val currentInput = buildString {
                if (args.label.isNotBlank()) append("[${args.label}] ")
                append(args.stateJson)
            }

            val checkpoint = AgentCheckpoint(
                sessionId = args.sessionId,
                stepIndex = nextStep,
                iteration = nextIteration,
                currentInput = currentInput,
                isComplete = false,
            )

            checkpointManager.save(checkpoint)
            logger.info { "Checkpoint #$nextIteration (step=$nextStep) sauvegardé pour session=${args.sessionId}" }

            "✅ Checkpoint #$nextIteration sauvegardé pour la session '${args.sessionId}'." +
                if (args.label.isNotBlank()) " Label : ${args.label}" else ""
        } catch (e: Exception) {
            logger.error(e) { "Échec de sauvegarde du checkpoint pour session=${args.sessionId}" }
            "[ERREUR] Impossible de sauvegarder le checkpoint : ${e.message}"
        }
}

// ── CheckpointListTool ─────────────────────────────────────

@Serializable
data class CheckpointListArgs(
    @property:LLMDescription("Identifiant de la session dont on veut vérifier le checkpoint.")
    val sessionId: String,
)

/**
 * CheckpointListTool — permet à l'agent de vérifier s'il existe un
 * checkpoint pour une session donnée.
 *
 * Retourne les détails du dernier checkpoint s'il existe, ou un message
 * indiquant qu'aucun checkpoint n'a été trouvé.
 */
class CheckpointListTool(
    private val checkpointManager: CheckpointManager,
) : SimpleTool<CheckpointListArgs>(
        argsType = typeToken<CheckpointListArgs>(),
        name = "checkpoint_list",
        description = "Check if a checkpoint exists for a given session. " +
            "Returns the latest checkpoint or indicates none found.",
    ) {
    override suspend fun execute(args: CheckpointListArgs): String =
        try {
            val checkpoint = checkpointManager.load(args.sessionId)
            if (checkpoint != null) {
                logger.info { "Checkpoint trouvé pour session=${args.sessionId}" }
                buildString {
                    appendLine("Dernier checkpoint pour '${args.sessionId}' :")
                    appendLine("  • Iteration : ${checkpoint.iteration}")
                    appendLine("  • Step      : ${checkpoint.stepIndex}")
                    appendLine("  • Complet   : ${checkpoint.isComplete}")
                    appendLine("  • Contenu   : ${checkpoint.currentInput}")
                    if (checkpoint.escalationLevel != null) {
                        appendLine("  • Escalade  : ${checkpoint.escalationLevel} (tentative ${checkpoint.escalationAttempt})")
                    }
                }
            } else {
                logger.info { "Aucun checkpoint pour session=${args.sessionId}" }
                "Aucun checkpoint trouvé pour la session '${args.sessionId}'."
            }
        } catch (e: Exception) {
            logger.error(e) { "Échec de lecture des checkpoints pour session=${args.sessionId}" }
            "[ERREUR] Impossible de lire les checkpoints : ${e.message}"
        }
}
