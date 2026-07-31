package dev.promethe.core.autonomous

import kotlinx.serialization.Serializable

/**
 * BudgetLimits — garde-fous pour le mode autonome.
 *
 * Tous les champs à 0 ou négatif = illimité.
 *
 * Valeurs par défaut conservatrices pour éviter les dérapages :
 * - 100K tokens max
 * - $1.00 max
 * - 50 itérations max
 * - 30 minutes max
 * - 2 retries par tâche
 */
@Serializable
data class BudgetLimits(
    /** Nombre maximum de tokens (prompt + completion). 0 = illimité. */
    val maxTokens: Long = 100_000,
    /** Coût maximum en dollars USD. 0 = illimité. */
    val maxCostDollars: Double = 1.0,
    /** Nombre maximum d'itérations (appels LLM totaux). 0 = illimité. */
    val maxIterations: Int = 50,
    /** Durée maximale en millisecondes. 0 = illimité. */
    val maxDurationMs: Long = 30 * 60 * 1_000L, // 30 minutes
    /** Nombre maximum de retries par tâche individuelle. */
    val maxRetriesPerTask: Int = 2,
) {
    companion object {
        /** Budget minimal pour les tests rapides. */
        val MINIMAL = BudgetLimits(
            maxTokens = 10_000,
            maxCostDollars = 0.10,
            maxIterations = 10,
            maxDurationMs = 5 * 60 * 1_000L,
            maxRetriesPerTask = 1,
        )

        /** Budget standard pour les tâches courantes. */
        val STANDARD = BudgetLimits()

        /** Budget étendu pour les tâches complexes. */
        val EXTENDED = BudgetLimits(
            maxTokens = 500_000,
            maxCostDollars = 5.0,
            maxIterations = 200,
            maxDurationMs = 2 * 60 * 60 * 1_000L, // 2 heures
            maxRetriesPerTask = 3,
        )

        /** Pas de limites (utiliser avec prudence). */
        val UNLIMITED = BudgetLimits(
            maxTokens = 0,
            maxCostDollars = 0.0,
            maxIterations = 0,
            maxDurationMs = 0,
            maxRetriesPerTask = 5,
        )
    }
}
