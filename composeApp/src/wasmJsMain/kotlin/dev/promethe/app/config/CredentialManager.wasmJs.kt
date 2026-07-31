package dev.promethe.app.config

import kotlinx.browser.localStorage

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * WasmJS implementation — persists non-secret display/config values only.
 * Remote access tokens deliberately stay in memory or in an HttpOnly cookie.
 */
actual object CredentialManager {
    private const val KEY_PREFIX = "promethe_"

    private var cached: AppCredentials? = null

    actual fun load(): AppCredentials? {
        cached?.let { return it }
        val gatewayUrl = localStorage.getItem("${KEY_PREFIX}gatewayUrl")
        val lastRemoteGatewayUrl = localStorage.getItem("${KEY_PREFIX}lastRemoteGatewayUrl")
        val llmProvider = localStorage.getItem("${KEY_PREFIX}llmProvider")
        if (gatewayUrl == null && lastRemoteGatewayUrl == null && llmProvider == null) return null
        val creds =
            AppCredentials(
                llmProvider = llmProvider ?: "openrouter",
                llmModel = localStorage.getItem("${KEY_PREFIX}llmModel") ?: "",
                gatewayUrl = gatewayUrl ?: "http://localhost:8080",
                lastRemoteGatewayUrl = lastRemoteGatewayUrl ?: "",
                ollamaUrl = localStorage.getItem("${KEY_PREFIX}ollamaUrl") ?: "http://localhost:11434",
            )
        cached = creds
        return creds
    }

    actual fun save(credentials: AppCredentials) {
        cached = credentials
        try {
            localStorage.removeItem("${KEY_PREFIX}apiKey")
            localStorage.setItem("${KEY_PREFIX}llmProvider", credentials.llmProvider)
            localStorage.removeItem("${KEY_PREFIX}llmApiKey")
            localStorage.setItem("${KEY_PREFIX}llmModel", credentials.llmModel)
            localStorage.setItem("${KEY_PREFIX}gatewayUrl", credentials.gatewayUrl)
            localStorage.setItem("${KEY_PREFIX}lastRemoteGatewayUrl", credentials.lastRemoteGatewayUrl)
            localStorage.setItem("${KEY_PREFIX}ollamaUrl", credentials.ollamaUrl)
        } catch (e: Exception) {
            logger.debug(e) { "Failed to persist credentials to localStorage" }
            // localStorage may be unavailable (e.g. incognito, storage quota)
        }
    }

    actual fun isConfigured(): Boolean = load()?.llmApiKey?.isNotBlank() == true

    actual fun clearRemoteSessionHint() {
        localStorage.removeItem("promethe_browserSession")
    }
}
