package dev.promethe.api

import kotlinx.serialization.Serializable

// ── Agent Profiles ──────────────────────────────────────────────────────────

/**
 * Defines a reusable agent persona with its own model, tools, and behavior.
 *
 * Profiles are stored in the database and can be:
 * - Created/modified by users via UI or CLI
 * - Created at runtime by agents (ephemeral sub-agents)
 * - Referenced by delegate_task to spawn sub-agents
 */
@Serializable
data class AgentProfile(
    val id: String, // "main", "coder", "reviewer"
    val name: String, // "Code Reviewer"
    val provider: String = "openai", // LLM provider key
    val model: String = "gpt-4o-mini", // model name
    val systemPrompt: String = "", // custom system prompt override
    val tools: List<String> = emptyList(), // allowed tool names (empty = all)
    val skills: List<String> = emptyList(), // assigned skills
    val maxIterations: Int = 10, // max PRA loop iterations
    val temperature: Double = 0.2,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.AUTO,
    val isSystem: Boolean = false, // non-deletable system agent
    val ephemeral: Boolean = false, // auto-created by agent, can be auto-deleted
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** Request to create or update an agent profile. */
@Serializable
data class AgentProfileRequest(
    val id: String? = null, // null = auto-generate
    val name: String,
    val provider: String = "openai",
    val model: String = "gpt-4o-mini",
    val systemPrompt: String = "",
    val tools: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val maxIterations: Int = 10,
    val temperature: Double = 0.2,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.AUTO,
    val isSystem: Boolean = false,
    val ephemeral: Boolean = false,
)

/** Enriched agent status — extends profile with runtime info. */
@Serializable
data class AgentStatusDto(
    val id: String,
    val name: String,
    val status: String, // "running", "idle", "error"
    val profileId: String? = null,
    val currentStep: String? = null,
    val tokensUsed: Long = 0,
    val startedAt: Long? = null,
    val lastActivityAt: Long? = null,
)

@Serializable
data class AgentExecutionEvent(
    val agentId: String,
    val type: String, // "step_start", "tool_call", "observation", "step_complete", "error"
    val content: String? = null,
    val tool: String? = null,
    val timestamp: Long,
    val runId: String? = null,
    val stepId: String? = null,
)

// ── GEPA ────────────────────────────────────────────────────────────────────

@Serializable
data class GepaOptimizeRequest(
    val maxGenerations: Int = 5,
    val populationSize: Int = 8,
    val target: String = "system_prompt", // "system_prompt" | "skill:<name>" | "profile:<id>"
)

@Serializable
data class GepaResultDto(
    val bestPromptPreview: String,
    val accuracy: Double,
    val improvement: Double,
    val generations: Int,
    val totalCandidatesEvaluated: Int,
)

@Serializable
data class GenerationSnapshot(
    val generation: Int,
    val populationSize: Int,
    val frontSizes: List<Int>,
    val bestAccuracy: Double,
    val bestTokenCount: Int,
    val avgAccuracy: Double,
)

@Serializable
data class GepaStartResponse(
    val jobId: String,
)

@Serializable
data class GepaJobResponse(
    val jobId: String,
    val status: String, // "running", "completed", "failed"
    val progress: Float = 0f,
    val result: GepaResultDto? = null,
    val error: String? = null,
    val startedAt: Long = 0L,
    val currentGeneration: Int = 0,
    val maxGenerations: Int = 0,
    val targetLabel: String = "System Prompt", // Human-readable target description
)
