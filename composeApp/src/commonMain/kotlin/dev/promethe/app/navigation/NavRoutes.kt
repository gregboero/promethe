package dev.promethe.app.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe route keys for Navigation 3.
 *
 * Each route is a @Serializable object or data class that acts as a
 * back stack entry. The sealed interface provides exhaustive when-matching
 * and type-safe argument passing (e.g. Chat(sessionId)).
 */
@Serializable
sealed interface PrometheRoute {
    // ── Top-level tabs ──

    @Serializable data object Sessions : PrometheRoute

    @Serializable data class Chat(
        val sessionId: String,
    ) : PrometheRoute

    @Serializable data object Agents : PrometheRoute

    @Serializable data object Monitor : PrometheRoute

    @Serializable data object Stats : PrometheRoute

    @Serializable data object Memory : PrometheRoute

    @Serializable data object Gepa : PrometheRoute

    @Serializable data object Settings : PrometheRoute

    @Serializable data object Tools : PrometheRoute

    @Serializable data object Channels : PrometheRoute

    @Serializable data object Scheduler : PrometheRoute

    @Serializable data object Mcp : PrometheRoute

    @Serializable data object Plugins : PrometheRoute

    @Serializable data object Knowledge : PrometheRoute

    @Serializable data object Orchestrator : PrometheRoute

    @Serializable data object Skills : PrometheRoute

    @Serializable data object Goal : PrometheRoute

    companion object {
        /**
         * Map a legacy string route ID to a PrometheRoute.
         * Used for WasmJs hash routing compatibility and NavItem bridging.
         */
        fun fromId(id: String): PrometheRoute? =
            when (id) {
                "sessions" -> Sessions
                "agents" -> Agents
                "monitor" -> Monitor
                "stats" -> Stats
                "memory" -> Memory
                "gepa" -> Gepa
                "settings" -> Settings
                "tools" -> Tools
                "channels" -> Channels
                "scheduler" -> Scheduler
                "mcp" -> Mcp
                "plugins" -> Plugins
                "knowledge" -> Knowledge
                "orchestrator" -> Orchestrator
                "skills" -> Skills
                "goal" -> Goal
                else -> null
            }

        /**
         * Map a PrometheRoute to its legacy string ID.
         * Used for NavigationRail/Bar selected-state and hash routing.
         */
        fun toId(route: PrometheRoute): String =
            when (route) {
                is Sessions -> "sessions"

                is Chat -> "sessions"

                // Chat is a sub-route of sessions
                is Agents -> "agents"

                is Monitor -> "monitor"

                is Stats -> "stats"

                is Memory -> "memory"

                is Gepa -> "gepa"

                is Settings -> "settings"

                is Tools -> "tools"

                is Channels -> "channels"

                is Scheduler -> "scheduler"

                is Mcp -> "mcp"

                is Plugins -> "plugins"

                is Knowledge -> "knowledge"

                is Orchestrator -> "orchestrator"

                is Skills -> "skills"

                is Goal -> "goal"
            }
    }
}
