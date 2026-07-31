package dev.promethe.app.config

import dev.promethe.app.util.PrometheJson
import java.io.File
import java.security.SecureRandom

/**
 * Android implementation — persists to ~/.promethe/credentials.json in the user home directory.
 * Self-contained file-based storage using kotlinx.serialization, matching the desktop/JVM path.
 */
actual object CredentialManager {
    private val configDir = File(System.getProperty("user.home"), ".promethe")
    private val configFile = File(configDir, "credentials.json")

    actual fun load(): AppCredentials? {
        if (!configFile.exists()) return null
        return try {
            PrometheJson.decodeFromString(AppCredentials.serializer(), configFile.readText())
        } catch (e: Exception) {
            null
        }
    }

    actual fun save(credentials: AppCredentials) {
        val apiKeyToSave = credentials.apiKey.ifBlank { generateApiKey() }
        val credsWithKey = credentials.copy(apiKey = apiKeyToSave)
        try {
            configDir.mkdirs()
            configFile.writeText(PrometheJson.encodeToString(AppCredentials.serializer(), credsWithKey))
            // Restrict permissions
            configFile.setReadable(false, false)
            configFile.setReadable(true, true)
            configFile.setWritable(false, false)
            configFile.setWritable(true, true)
        } catch (e: Exception) {
            // best-effort
        }
    }

    actual fun isConfigured(): Boolean {
        val creds = load() ?: return false
        return creds.llmApiKey.isNotBlank() || creds.llmProvider == "ollama"
    }

    actual fun clearRemoteSessionHint() = Unit

    private fun generateApiKey(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return "pk-prom-" + bytes.joinToString("") { "%02x".format(it) }
    }
}
