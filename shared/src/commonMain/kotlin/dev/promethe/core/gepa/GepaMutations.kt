package dev.promethe.core.gepa

import dev.promethe.core.Log

import dev.promethe.core.AgentConfig
import dev.promethe.core.KoogLlmAdapter
import kotlin.time.Clock
import kotlin.random.Random

class GepaMutations(
    private val llmAdapter: KoogLlmAdapter,
    private val config: AgentConfig,
) {
    private val logger = Log.create("GepaMutations")
    private val instanceId = Clock.System.now().toEpochMilliseconds()
    private var idCounter = 0

    private fun nextId() = "gepa-$instanceId-${++idCounter}"

    /** Génère la population initiale : prompt original + N-1 reformulations LLM */
    suspend fun seedPopulation(
        originalPrompt: String,
        size: Int,
    ): List<PromptCandidate> {
        val candidates =
            mutableListOf(
                PromptCandidate(id = nextId(), promptText = originalPrompt, generation = 0),
            )
        repeat(size - 1) { i ->
            val rephrased = llmRephrase(originalPrompt, i)
            candidates.add(
                PromptCandidate(
                    id = nextId(),
                    promptText = rephrased,
                    generation = 0,
                    mutationType = MutationType.LLM_REPHRASE,
                ),
            )
        }
        return candidates
    }

    /** Crossover : coupe les lignes de 2 parents à un point aléatoire et les mélange */
    fun crossover(
        p1: PromptCandidate,
        p2: PromptCandidate,
        gen: Int,
    ): PromptCandidate {
        val l1 = p1.promptText.lines()
        val l2 = p2.promptText.lines()
        val cut1 = Random.nextInt(0, maxOf(1, l1.size))
        val cut2 = Random.nextInt(0, maxOf(1, l2.size))
        return PromptCandidate(
            id = nextId(),
            promptText = (l1.take(cut1) + l2.drop(cut2)).joinToString("\n"),
            generation = gen,
            parentIds = listOf(p1.id, p2.id),
            mutationType = MutationType.CROSSOVER,
        )
    }

    /** Mutation : insère, supprime ou modifie une ligne aléatoire */
    fun mutateSegment(
        parent: PromptCandidate,
        gen: Int,
    ): PromptCandidate {
        val lines = parent.promptText.lines().toMutableList()
        if (lines.isEmpty()) return parent.copy(id = nextId(), generation = gen)
        val type =
            when (Random.nextInt(3)) {
                0 -> MutationType.SEGMENT_INSERT
                1 -> MutationType.SEGMENT_DELETE
                else -> MutationType.SEGMENT_MODIFY
            }
        when (type) {
            MutationType.SEGMENT_INSERT -> {
                lines.add(
                    Random.nextInt(0, lines.size + 1),
                    "- Follow the user's instructions precisely.",
                )
            }

            MutationType.SEGMENT_DELETE -> {
                if (lines.size > 3) lines.removeAt(Random.nextInt(lines.size))
            }

            MutationType.SEGMENT_MODIFY -> {
                val pos = Random.nextInt(lines.size)
                lines[pos] = lines[pos].uppercase()
            }

            else -> {}
        }
        return PromptCandidate(
            id = nextId(),
            promptText = lines.joinToString("\n"),
            generation = gen,
            parentIds = listOf(parent.id),
            mutationType = type,
        )
    }

    private suspend fun llmRephrase(
        original: String,
        variant: Int,
    ): String {
        val style =
            when (variant % 4) {
                0 -> "more concise while retaining all instructions"
                1 -> "more structured with clear numbered steps"
                2 -> "more explicit about edge cases and error handling"
                else -> "focused on clarity and removing redundancy"
            }
        return try {
            llmAdapter
                .complete(
                    systemPrompt =
                        "You are a prompt engineer. Rewrite the following system prompt " +
                            "to be $style. Output ONLY the rewritten prompt.",
                    messages = listOf("user" to original),
                    temperature = 0.7,
                ).content
        } catch (e: Exception) {
            logger.warn(e) { "LLM rephrase failed for variant $variant, using original prompt" }
            original
        }
    }
}
