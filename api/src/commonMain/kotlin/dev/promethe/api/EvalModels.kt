package dev.promethe.api

import kotlinx.serialization.Serializable

@Serializable
enum class EvalAssertionKind {
    EQUALS,
    CONTAINS,
    NOT_CONTAINS,
    MATCHES_REGEX,
    EXISTS,
    NOT_EXISTS,
}

@Serializable
enum class EvalCaseStatus {
    PASSED,
    FAILED,
    ERROR,
}

@Serializable
enum class EvalRunStatus {
    PASSED,
    FAILED,
}

@Serializable
data class EvalAssertion(
    val id: String,
    val field: String = "output",
    val kind: EvalAssertionKind,
    val expected: String? = null,
)

@Serializable
data class EvalCase(
    val id: String,
    val capability: String,
    val description: String,
    val input: String,
    val assertions: List<EvalAssertion>,
    val tags: Set<String> = emptySet(),
    val provider: String? = null,
    val model: String? = null,
    val timeoutMs: Long = 30_000,
)

@Serializable
data class EvalSuite(
    val id: String,
    val version: Int,
    val description: String,
    val cases: List<EvalCase>,
)

@Serializable
data class EvalObservation(
    val output: String = "",
    val errorCode: String? = null,
    val exitCode: Int? = null,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class EvalAssertionResult(
    val assertionId: String,
    val passed: Boolean,
    val actual: String? = null,
    val message: String? = null,
)

@Serializable
data class EvalCaseResult(
    val caseId: String,
    val status: EvalCaseStatus,
    val durationMs: Long,
    val assertions: List<EvalAssertionResult> = emptyList(),
    val error: String? = null,
)

@Serializable
data class EvalRun(
    val id: String,
    val suiteId: String,
    val suiteVersion: Int,
    val startedAt: Long,
    val finishedAt: Long,
    val status: EvalRunStatus,
    val results: List<EvalCaseResult>,
)
