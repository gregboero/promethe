package dev.promethe.core

class RewardSignal {
    /**
     * Ajuste la stratégie en fonction du feedback utilisateur.
     * - score < 0.4 → augmenter temperature (plus créatif)
     * - score > 0.8 → diminuer temperature (plus fiable)
     */
    fun adjustConfig(
        avgScore: Double,
        current: AgentConfig,
    ): AgentConfig {
        val newTemp =
            when {
                avgScore < 0.4 -> (current.temperature + 0.1).coerceAtMost(0.9)
                avgScore > 0.8 -> (current.temperature - 0.05).coerceAtLeast(0.05)
                else -> current.temperature
            }
        return current.copy(temperature = newTemp)
    }

    /**
     * Détermine si on doit synthétiser une skill basé sur le feedback.
     * Ne synthétise QUE si le feedback de la session est positif.
     */
    fun shouldAllowSynthesis(sessionScore: Double?): Boolean = sessionScore == null || sessionScore >= 0.6
}
