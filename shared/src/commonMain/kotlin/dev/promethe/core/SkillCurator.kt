package dev.promethe.core

import dev.promethe.core.Log

import kotlinx.coroutines.withContext

/**
 * SkillCurator — proposes skill quality actions without mutating user data.
 *
 * A curation pass grades skills and detects near-duplicates, but every action
 * is returned as a quarantined proposal for explicit review. The curator
 * never deletes, merges, writes, or invalidates skills automatically.
 */
class SkillCurator(
    private val skillLoader: SkillLoader,
    private val skillWriter: SkillWriter,
    private val llmAdapter: KoogLlmAdapter,
    private val config: AgentConfig,
) {
    private val logger = Log.create("SkillCurator")

    companion object {
        /** Skills scoring at or below this threshold receive a review proposal. */
        const val PRUNE_THRESHOLD = 2

        /** Jaccard similarity above this triggers a duplicate review proposal. */
        const val SIMILARITY_THRESHOLD = 0.65

        /** Maximum skills to evaluate per curation run (avoid huge LLM costs). */
        const val MAX_SKILLS_PER_RUN = 50
    }

    /**
     * Runs a non-destructive curation pass.
     *
     * The returned proposals are quarantined until a separate, explicit
     * approval workflow is introduced. This method deliberately has no write
     * or delete side effects.
     */
    suspend fun curate(): CurationReport {
        val skills = skillLoader.listSkills()
        if (skills.isEmpty()) return CurationReport(total = 0)

        val graded = gradeSkills(skills.take(MAX_SKILLS_PER_RUN))
        val proposals = buildList {
            graded
                .filter { (_, score) -> score <= PRUNE_THRESHOLD }
                .forEach { (skill, score) ->
                    add(
                        SkillCurationProposal(
                            action = CurationAction.REVIEW_LOW_QUALITY,
                            skill = skill.name,
                            score = score,
                            rationale = "Quality score is at or below the review threshold.",
                        ),
                    )
                }
            addAll(findDuplicateProposals(skills))
        }

        proposals.forEach { proposal ->
            logger.info { "Quarantined curation proposal: ${proposal.action} for '${proposal.skill}'" }
        }

        return CurationReport(
            total = skills.size,
            graded = graded.map { (s, score) -> s.name to score },
            proposals = proposals,
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

    // ── Duplicate Detection (proposal only) ──────────────────

    /**
     * Finds pairs of skills with high keyword overlap (Jaccard similarity)
     * and returns review proposals. No skill is modified or removed.
     */
    private fun findDuplicateProposals(skills: List<SkillEntry>): List<SkillCurationProposal> {
        val proposals = mutableListOf<SkillCurationProposal>()
        for (i in skills.indices) {
            for (j in (i + 1) until skills.size) {
                val similarity =
                    jaccardSimilarity(
                        extractKeywords(skills[i]),
                        extractKeywords(skills[j]),
                    )
                if (similarity >= SIMILARITY_THRESHOLD) {
                    val (primary, related) =
                        if (skills[i].content.length >= skills[j].content.length) {
                            skills[i] to skills[j]
                        } else {
                            skills[j] to skills[i]
                        }
                    proposals.add(
                        SkillCurationProposal(
                            action = CurationAction.REVIEW_DUPLICATE,
                            skill = primary.name,
                            relatedSkills = listOf(related.name),
                            similarity = similarity,
                            rationale = "Skills have high keyword overlap and require manual comparison.",
                        ),
                    )
                }
            }
        }
        return proposals
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
    val proposals: List<SkillCurationProposal> = emptyList(),
) {
    override fun toString(): String =
        buildString {
            appendLine("=== Skill Curation Report ===")
            appendLine("Total skills: $total")
            appendLine("Merged automatically: 0")
            appendLine("Pruned automatically: 0")
            appendLine("Quarantined proposals: ${proposals.size}")
            if (graded.isNotEmpty()) {
                appendLine("Grades: ${graded.joinToString(", ") { "${it.first}=${it.second}" }}")
                appendLine("Average: ${"%.1f".format(graded.map { it.second }.average())}/5")
            }
        }
}

enum class CurationAction {
    REVIEW_LOW_QUALITY,
    REVIEW_DUPLICATE,
}

/** A non-applied curation recommendation awaiting explicit review. */
data class SkillCurationProposal(
    val action: CurationAction,
    val skill: String,
    val relatedSkills: List<String> = emptyList(),
    val score: Int? = null,
    val similarity: Double? = null,
    val rationale: String,
    val quarantined: Boolean = true,
)
