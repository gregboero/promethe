package dev.promethe.core

import dev.promethe.core.Log

import kotlinx.coroutines.withContext

/**
 * SkillCurator — automatic skill quality control.
 *
 * Periodically grades every skill via LLM and prunes low-quality ones.
 * Detects near-duplicate skills by title/keyword overlap and merges them.
 *
 * Designed to be invoked by [TaskScheduler] on a daily cron (or manually).
 */
class SkillCurator(
    private val skillLoader: SkillLoader,
    private val skillWriter: SkillWriter,
    private val llmAdapter: KoogLlmAdapter,
    private val config: AgentConfig,
) {
    private val logger = Log.create("SkillCurator")

    companion object {
        /** Skills scoring at or below this threshold are pruned. */
        const val PRUNE_THRESHOLD = 2

        /** Jaccard similarity above this triggers a merge check. */
        const val SIMILARITY_THRESHOLD = 0.65

        /** Maximum skills to evaluate per curation run (avoid huge LLM costs). */
        const val MAX_SKILLS_PER_RUN = 50
    }

    /**
     * Runs a full curation pass:
     * 1. Load all skills
     * 2. Detect and merge near-duplicates
     * 3. Grade each remaining skill via LLM
     * 4. Prune skills with score ≤ [PRUNE_THRESHOLD]
     *
     * Returns a [CurationReport] summarizing what happened.
     */
    suspend fun curate(): CurationReport {
        val skills = skillLoader.listSkills()
        if (skills.isEmpty()) return CurationReport(total = 0)

        val merged = mergeNearDuplicates(skills)
        val remaining = skillLoader.listSkills() // Reload after merges

        val graded = gradeSkills(remaining.take(MAX_SKILLS_PER_RUN))
        val pruned = mutableListOf<String>()
        for ((skill, score) in graded) {
            if (score <= PRUNE_THRESHOLD) {
                skillLoader.deleteSkill(skill.name)
                pruned.add(skill.name)
                logger.info { "Pruned '${skill.name}' (score=$score)" }
            }
        }

        skillLoader.invalidateCache()

        return CurationReport(
            total = skills.size,
            merged = merged,
            pruned = pruned,
            graded = graded.map { (s, score) -> s.name to score },
        )
    }

    // ── Grading ──────────────────────────────────────────────

    /**
     * Asks the LLM to score each skill 1-5 on utility, clarity, and freshness.
     * Returns pairs of (skill, averageScore).
     */
    private suspend fun gradeSkills(skills: List<SkillEntry>): List<Pair<SkillEntry, Int>> =
        skills.map { skill ->
            val score =
                try {
                    gradeOneSkill(skill)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to grade '${skill.name}'" }
                    3 // Neutral score on failure — don't prune what we can't evaluate
                }
            skill to score
        }

    private suspend fun gradeOneSkill(skill: SkillEntry): Int =
        withContext(ioDispatcher) {
            val prompt =
                """
                You are a skill quality evaluator for an AI agent.
                Rate this skill on a scale of 1-5 where:
                1 = Useless, incorrect, or completely outdated
                2 = Very low quality, vague, or near-duplicate of common knowledge
                3 = Acceptable but generic
                4 = Good, specific, and actionable
                5 = Excellent, unique insight with clear steps

                Skill name: ${skill.name}
                Skill content:
                ${skill.content.take(2000)}

                Respond with ONLY a single integer (1-5), nothing else.
                """.trimIndent()

            val response =
                llmAdapter.complete(
                    systemPrompt = "You are a strict quality evaluator. Respond with only a number.",
                    messages = listOf("user" to prompt),
                    model = config.modelName,
                    temperature = 0.0,
                )

            response.content
                .trim()
                .filter { it.isDigit() }
                .take(1)
                .toIntOrNull() ?: 3
        }

    // ── Duplicate Detection & Merge ─────────────────────────

    /**
     * Finds pairs of skills with high keyword overlap (Jaccard similarity)
     * and merges the shorter one into the longer one.
     */
    private suspend fun mergeNearDuplicates(skills: List<SkillEntry>): List<String> {
        val merged = mutableListOf<String>()
        val processed = mutableSetOf<String>()

        for (i in skills.indices) {
            if (skills[i].name in processed) continue
            for (j in (i + 1) until skills.size) {
                if (skills[j].name in processed) continue

                val similarity =
                    jaccardSimilarity(
                        extractKeywords(skills[i]),
                        extractKeywords(skills[j]),
                    )
                if (similarity >= SIMILARITY_THRESHOLD) {
                    // Keep the longer/richer skill, delete the shorter
                    val (keep, remove) =
                        if (skills[i].content.length >= skills[j].content.length) {
                            skills[i] to skills[j]
                        } else {
                            skills[j] to skills[i]
                        }
                    skillLoader.deleteSkill(remove.name)
                    processed.add(remove.name)
                    merged.add("${remove.name} → ${keep.name}")
                    logger.info { "Merged '${remove.name}' into '${keep.name}' (similarity=${"%.2f".format(similarity)})" }
                }
            }
        }
        return merged
    }

    private fun extractKeywords(skill: SkillEntry): Set<String> =
        (skill.name + " " + skill.content)
            .lowercase()
            .split(Regex("[\\s\\p{Punct}]+"))
            .filter { it.length > 3 }
            .toSet()

    private fun jaccardSimilarity(
        a: Set<String>,
        b: Set<String>,
    ): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size
        val union = a.union(b).size
        return intersection.toDouble() / union.toDouble()
    }
}

data class CurationReport(
    val total: Int,
    val merged: List<String> = emptyList(),
    val pruned: List<String> = emptyList(),
    val graded: List<Pair<String, Int>> = emptyList(),
) {
    override fun toString(): String =
        buildString {
            appendLine("=== Skill Curation Report ===")
            appendLine("Total skills: $total")
            appendLine("Merged: ${merged.size} (${merged.joinToString(", ")})")
            appendLine("Pruned: ${pruned.size} (${pruned.joinToString(", ")})")
            if (graded.isNotEmpty()) {
                appendLine("Grades: ${graded.joinToString(", ") { "${it.first}=${it.second}" }}")
                appendLine("Average: ${"%.1f".format(graded.map { it.second }.average())}/5")
            }
        }
}
