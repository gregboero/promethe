package dev.promethe.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Skill DTOs ──────────────────────────────────────────────

@Serializable
data class SkillDto(
    val name: String,
    val description: String = "",
    val content: String,
    val preview: String = "",
    val isSystem: Boolean = false,
)

@Serializable
data class SkillListResponse(
    val skills: List<SkillDto>,
    val count: Int = skills.size,
)

@Serializable
data class CreateSkillRequest(
    val name: String,
    val description: String = "",
    val content: String,
)

@Serializable
data class UpdateSkillRequest(
    val content: String,
)

@Serializable
data class ImportSkillRequest(
    val url: String,
    val name: String? = null,
)

@Serializable
data class SkillCurationReport(
    val analyzed: Int = 0,
    val issues: List<String> = emptyList(),
    val merged: Int = 0,
    val deleted: Int = 0,
)

@Serializable
data class SkillEvolveResponse(
    val success: Boolean,
    val name: String,
    val message: String,
    @SerialName("new_content") val newContent: String? = null,
)

// ── Skill Index (lazy-loading) ──────────────────────────────

/**
 * Lightweight skill summary for system prompt injection.
 * Contains only name + description (~10 tokens per skill) so the LLM
 * can discover skills without loading their full SKILL.md content.
 */
@Serializable
data class SkillSummaryDto(
    val name: String,
    val description: String,
    val source: SkillSource = SkillSource.CUSTOM,
    val requirements: SkillRequirements = SkillRequirements(),
)

/**
 * Declares what a skill needs to run.
 * Used by the client to filter unavailable skills.
 */
@Serializable
data class SkillRequirements(
    /** Target platforms: "jvm", "macos", "ios", "wasmjs" */
    val platforms: List<String> = emptyList(),
    /** Required CLI tools: "claude", "codex", "gh", "docker", "python", "uv" */
    val requiresCli: List<String> = emptyList(),
    /** OAuth provider required: "google", "microsoft", "notion", "airtable" */
    val requiresOAuth: String? = null,
)

@Serializable
enum class SkillSource {
    /** Included in all tiers */
    BUNDLED,

    /** Available in Pro/Enterprise tiers */
    OPTIONAL,

    /** User-created skills */
    CUSTOM,
}
