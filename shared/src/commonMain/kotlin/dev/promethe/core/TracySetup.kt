package dev.promethe.core

import dev.promethe.core.Log

/**
 * Observability setup entry point.
 * Passes config to [Tracing] to select the appropriate backend.
 */
object TracySetup {
    private val logger = Log.create("TracySetup")

    fun initialize(config: AgentConfig) {
        val endpoint =
            if (config.tracingBackend.equals("langfuse", ignoreCase = true)) {
                config.langfuseHost
            } else {
                config.otlpEndpoint
            }
        Tracing.initialize(
            backend = config.tracingBackend,
            endpoint = endpoint,
            publicKey = config.langfusePublicKey,
            secretKey = config.langfuseSecretKey,
        )
        logger.info { "Backend: ${config.tracingBackend}" }
    }
}
