package dev.promethe.core

import dev.promethe.db.PrometheDatabaseApi
import kotlin.time.Clock

class FeedbackCollector(
    private val database: PrometheDatabaseApi,
) {
    /** Parse l'input utilisateur en score numérique */
    fun parseScore(input: String): Double? =
        when (input.trim().lowercase()) {
            "👍", "+", "y", "yes", "good" -> {
                1.0
            }

            "👎", "-", "n", "no", "bad" -> {
                0.0
            }

            "😐", "~", "meh", "ok" -> {
                0.5
            }

            else -> {
                val num = input.trim().toDoubleOrNull()
                when {
                    num == null -> null

                    num in 0.0..1.0 -> num

                    num in 0.0..10.0 -> num / 10.0

                    // normalise 0-10 → 0-1
                    else -> null
                }
            }
        }

    /** Enregistre un feedback pour une session */
    suspend fun record(
        sessionId: String,
        score: Double,
        comment: String? = null,
    ) {
        database.insertFeedback(
            sessionId = sessionId,
            score = score,
            comment = comment,
            timestamp = Clock.System.now().toEpochMilliseconds(),
        )
    }

    /** Récupère la moyenne globale des feedbacks */
    suspend fun getGlobalStats(): Pair<Double, Long> {
        val stats = database.getAverageFeedback()
        return stats.avgScore to stats.feedbackCount
    }
}
