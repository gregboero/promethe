package dev.promethe.app.config

import platform.Foundation.NSUserDefaults
import platform.Security.*
import kotlinx.cinterop.*

/**
 * iOS implementation — persists to NSUserDefaults.
 *
 * For sensitive data like API keys, a production app should use Keychain.
 * NSUserDefaults is acceptable for development and non-sensitive config.
 * Credentials survive app restarts and are backed up automatically.
 */
actual object CredentialManager {

    private const val SUITE = "dev.promethe.credentials"
    private val defaults = NSUserDefaults(suiteName = SUITE)

    private var cached: AppCredentials? = null

    actual fun load(): AppCredentials? {
        cached?.let { return it }
        val apiKey = defaults.stringForKey("apiKey") ?: return null
        val creds = AppCredentials(
            apiKey = apiKey,
            llmProvider = defaults.stringForKey("llmProvider") ?: "openrouter",
            llmApiKey = defaults.stringForKey("llmApiKey") ?: "",
            llmModel = defaults.stringForKey("llmModel") ?: "",
            gatewayUrl = defaults.stringForKey("gatewayUrl") ?: "http://localhost:8080",
            ollamaUrl = defaults.stringForKey("ollamaUrl") ?: "http://localhost:11434",
        )
        cached = creds
        return creds
    }

    actual fun save(credentials: AppCredentials) {
        cached = credentials
        defaults.setObject(credentials.apiKey, forKey = "apiKey")
        defaults.setObject(credentials.llmProvider, forKey = "llmProvider")
        defaults.setObject(credentials.llmApiKey, forKey = "llmApiKey")
        defaults.setObject(credentials.llmModel, forKey = "llmModel")
        defaults.setObject(credentials.gatewayUrl, forKey = "gatewayUrl")
        defaults.setObject(credentials.ollamaUrl, forKey = "ollamaUrl")
        defaults.synchronize()
    }

    actual fun isConfigured(): Boolean =
        load()?.llmApiKey?.isNotBlank() == true

    actual fun clearRemoteSessionHint() = Unit
}
