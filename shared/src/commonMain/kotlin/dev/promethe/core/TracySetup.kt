package dev.promethe.core

import dev.promethe.core.Log

/**
 * Observability setup entry point.
 * Passes config to [Tracing] to select the appropriate backend.
 */
object TracySetup {
    private val logger = Log.create("TracySetup")

    fun initialize(config: AgentConfig) {
        Tracing.initialize(config.tracingBackend)
        logger.info { "Backend: ${config.tracingBackend}" }
    }
}
