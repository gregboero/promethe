package dev.promethe.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Explicit LAB oracle for the page task. Expected values are deliberately not part of completion. */
object HarnessTaskCompletion {
    @Serializable
    data class Result(
        val complete: Boolean,
        val reason: String,
        val missingPages: List<Int>,
        val answerCount: Int?,
    )

    fun assess(
        response: String,
        readPages: List<Int>,
        pageCount: Int,
    ): Result {
        require(pageCount in 1..32)
        val missing = (0 until pageCount).filter { it !in readPages }

        fun reject(
            reason: String,
            count: Int? = null,
        ) = Result(false, reason, missing, count)
        if (response.isBlank()) return reject("empty_final_response")
        if (readPages.any { it !in 0 until pageCount }) return reject("unexpected_page_read")
        if (readPages.toSet().size != readPages.size) return reject("duplicate_page_read")
        if (missing.isNotEmpty()) return reject("missing_page_reads")
        val answers = runCatching { Json.parseToJsonElement(response.trim()) as? JsonArray }.getOrNull()
            ?: return reject("invalid_answer_format")
        if (answers.size != pageCount) return reject("answer_count_mismatch", answers.size)
        if (answers.any { it !is JsonPrimitive || it.isString || it.intOrNull == null }) return reject("non_integer_answer", answers.size)
        return Result(true, "complete", emptyList(), answers.size)
    }

    fun requireComplete(
        response: String,
        readPages: List<Int>,
        pageCount: Int,
    ) {
        val result = assess(response, readPages, pageCount)
        if (!result.complete) throw AgentExecutionException("task_${result.reason}", "Task completion rejected: ${result.reason}")
    }
}
