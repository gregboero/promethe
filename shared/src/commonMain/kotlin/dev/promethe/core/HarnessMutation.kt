package dev.promethe.core

import kotlinx.serialization.Serializable

@Serializable
enum class HarnessLanguage { JAVASCRIPT, KOTLIN }

@Serializable
data class HarnessRevision(
    val id: String,
    val sessionId: String,
    val baseRevision: String?,
    val source: String,
    val hash: String,
    val toolName: String,
    val createdAt: Long,
    val validated: Boolean = false,
    val language: HarnessLanguage = HarnessLanguage.JAVASCRIPT,
)

@Serializable
data class HarnessObservation(
    val toolName: String,
    val text: String,
)

data class ProcessedObservation(
    val text: String,
    val revision: String? = null,
)

/** Session-scoped presentation only. Never changes the tool's actual outcome or provenance. */
interface ObservationProcessor {
    suspend fun beginStep(
        sessionId: String,
        runId: String?,
        stepId: String?,
    )

    suspend fun process(
        request: ToolExecutionRequest,
        raw: String,
    ): ProcessedObservation

    suspend fun revisionKey(sessionId: String): String

    suspend fun endSession(sessionId: String)
}

interface HarnessControl : ObservationProcessor {
    val language: HarnessLanguage get() = HarnessLanguage.JAVASCRIPT

    val sourceDescription: String get() = when (language) {
        HarnessLanguage.JAVASCRIPT -> "a JavaScript function body receiving observation {toolName,text} and returning a string"
        HarnessLanguage.KOTLIN -> "a Kotlin .kts script with observation.toolName and observation.text; its final expression must be a String. kotlinx.serialization.json.* is imported. No dependency resolution"
    }

    suspend fun command(
        request: ToolExecutionRequest,
        operation: String,
        args: HarnessArguments,
    ): String
}

@Serializable
data class HarnessArguments(
    val source: String = "",
    val revision: String = "",
    val baseRevision: String? = null,
    val toolName: String = "read_file",
)
