package dev.promethe.api

import kotlinx.serialization.Serializable

@Serializable
data class SkillEvaluationCase(
    val id: String,
    val input: String,
    val expectedOutput: String,
)

/** Owner-defined text tasks with an exact-output oracle; never shell commands. */
@Serializable
data class SkillEvaluationSuite(
    val id: String,
    val cases: List<SkillEvaluationCase>,
)

@Serializable
enum class SkillEvaluationStatus { RUNNING, PASSED, FAILED, ERROR, CANCELLED }

@Serializable
data class SkillEvaluationCaseResult(
    val suiteId: String,
    val caseId: String,
    val status: SkillEvaluationStatus,
    val outputHash: String? = null,
    val reason: String? = null,
)

@Serializable
data class SkillEvaluationRun(
    val id: String,
    val revisionHash: String,
    val startedAt: Long,
    val completedAt: Long,
    val evaluator: String,
    val status: SkillEvaluationStatus,
    val results: List<SkillEvaluationCaseResult>,
)

@Serializable
data class SkillVersionDto(
    val id: String,
    val revisionHash: String,
    val capturedAt: Long,
    val content: String,
    val lifecycle: SkillLifecycle,
)

@Serializable
data class SkillValidationState(
    val revisionHash: String,
    val suites: List<SkillEvaluationSuite>,
    val latestRun: SkillEvaluationRun? = null,
    val canPromote: Boolean = false,
    val versions: List<SkillVersionDto> = emptyList(),
    val runs: List<SkillEvaluationRun> = emptyList(),
)

@Serializable
data class ConfigureSkillEvaluationRequest(
    val expectedRevisionHash: String,
    val suites: List<SkillEvaluationSuite>,
)

@Serializable
data class EvaluateSkillRequest(
    val expectedRevisionHash: String,
)

@Serializable
data class RestoreSkillVersionRequest(
    val expectedRevisionHash: String,
    val versionId: String,
)
