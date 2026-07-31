package dev.promethe.api

import kotlinx.serialization.Serializable

// ══════════════════════════════════════════════════════════════
// Gateway Response DTOs
//
// Typed replacements for all remaining buildJsonObject patterns.
// ══════════════════════════════════════════════════════════════

// ── MCP ─────────────────────────────────────────────────────

@Serializable
data class McpToolInfo(
    val name: String,
    val description: String,
)

@Serializable
data class McpConnectResponse(
    val status: String = "connected",
    val tools: List<McpToolInfo>,
)

// ── Webhook ─────────────────────────────────────────────────

@Serializable
data class WebhookReply(
    val status: String = "ok",
    val reply: String,
)

// ── Execute ─────────────────────────────────────────────────

@Serializable
data class ExecuteResponse(
    val result: String,
)

// ── Health (enhanced) ───────────────────────────────────────

@Serializable
data class EnhancedHealthResponse(
    val status: String = "ok",
    val version: String = "1.0.0",
    val uptime: String,
    val memory: JvmMemoryInfo,
)

@Serializable
data class JvmMemoryInfo(
    val used: String,
    val max: String,
)

// ── Auth / Setup ────────────────────────────────────────────

@Serializable
data class SetupRemoteResponse(
    val status: String = "ok",
    val user: String,
)
