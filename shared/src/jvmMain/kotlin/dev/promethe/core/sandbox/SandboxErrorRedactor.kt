package dev.promethe.core.sandbox

import dev.promethe.api.SandboxedExecutionRequest

internal object SandboxErrorRedactor {
    private const val MAX_ERROR_LENGTH = 512
    private val credentialPatterns =
        listOf(
            Regex("(?i)(authorization\\s*[:=]\\s*)([^\\s,;]+)"),
            Regex("(?i)((?:api[-_]?key|token|secret|password)\\s*[:=]\\s*)([^\\s,;]+)"),
            Regex("(?i)(bearer\\s+)([^\\s,;]+)"),
        )

    fun redact(
        message: String?,
        request: SandboxedExecutionRequest? = null,
    ): String {
        var safe = message.orEmpty().replace('\r', ' ').replace('\n', ' ').trim()
        request?.sensitiveEnvironmentKeys.orEmpty().forEach { key ->
            request?.environment?.get(key)?.takeIf { it.isNotEmpty() }?.let { value ->
                safe = safe.replace(value, "[REDACTED]")
            }
        }
        credentialPatterns.forEach { pattern ->
            safe = pattern.replace(safe) { match -> "${match.groupValues[1]}[REDACTED]" }
        }
        return safe.take(MAX_ERROR_LENGTH).ifBlank { "Sandbox operation failed." }
    }
}
