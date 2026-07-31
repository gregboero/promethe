package dev.promethe.api

import kotlinx.serialization.Serializable

@Serializable
data class PluginResponse(
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val enabled: Boolean,
    val toolCount: Int,
    val hookCount: Int,
    val promptCount: Int,
)

@Serializable
data class PluginListResponse(
    val plugins: List<PluginResponse>,
    val totalPlugins: Int,
    val enabledPlugins: Int,
    val totalTools: Int,
)

@Serializable
data class PluginToggleRequest(
    val enabled: Boolean,
)
