package dev.promethe.core.hooks

import dev.promethe.core.PrometheJson
import kotlinx.serialization.Serializable

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * HookConfigLoader — loads hook configurations from YAML or JSON files.
 *
 * Supports:
 *   - `hooks.json` (preferred, no YAML dependency)
 *   - Environment variable overrides
 *
 * Hook config format:
 * ```json
 * {
 *   "hooks": [
 *     {
 *       "event": "on_message",
 *       "action": "log",
 *       "config": {"level": "info"}
 *     },
 *     {
 *       "event": "on_tool_call",
 *       "action": "approve",
 *       "config": {"dangerous_tools": ["shell", "docker"]}
 *     }
 *   ]
 * }
 * ```
 */
class HookConfigLoader(
    private val basePath: String,
) {
    private val json = PrometheJson

    /**
     * Load hook configurations from file.
     * Tries hooks.json first, then hooks.yaml (parsed as simplified YAML).
     */
    fun load(): HookConfigFile {
        val jsonFile = java.io.File(basePath, "hooks.json")
        if (jsonFile.exists()) {
            return try {
                json.decodeFromString<HookConfigFile>(jsonFile.readText())
            } catch (e: Exception) {
                logger.error(e) { "Failed to parse hooks.json" }
                HookConfigFile()
            }
        }

        val yamlFile = java.io.File(basePath, "hooks.yaml")
        if (yamlFile.exists()) {
            return parseSimpleYaml(yamlFile.readText())
        }

        val ymlFile = java.io.File(basePath, "hooks.yml")
        if (ymlFile.exists()) {
            return parseSimpleYaml(ymlFile.readText())
        }

        return HookConfigFile()
    }

    /**
     * Simple YAML parser for hook configs.
     * Handles flat key-value pairs and lists — not a full YAML parser.
     */
    private fun parseSimpleYaml(yaml: String): HookConfigFile {
        val hooks = mutableListOf<HookEntry>()
        var currentHook: MutableMap<String, String>? = null

        for (line in yaml.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("- event:")) {
                // Save previous hook
                currentHook?.let { map ->
                    hooks.add(
                        HookEntry(
                            event = map["event"] ?: "",
                            action = map["action"] ?: "",
                            enabled = map["enabled"]?.toBoolean() ?: true,
                        ),
                    )
                }
                currentHook = mutableMapOf("event" to trimmed.substringAfter(":").trim())
            } else if (currentHook != null && trimmed.contains(":")) {
                val key = trimmed.substringBefore(":").trim()
                val value = trimmed.substringAfter(":").trim()
                currentHook[key] = value
            }
        }
        // Don't forget the last one
        currentHook?.let { map ->
            hooks.add(
                HookEntry(
                    event = map["event"] ?: "",
                    action = map["action"] ?: "",
                    enabled = map["enabled"]?.toBoolean() ?: true,
                ),
            )
        }

        return HookConfigFile(hooks = hooks)
    }
}

// ── Config models ────────────────────────────────────────────────────

@Serializable
data class HookConfigFile(
    val hooks: List<HookEntry> = emptyList(),
)

@Serializable
data class HookEntry(
    val event: String,
    val action: String,
    val enabled: Boolean = true,
    val config: Map<String, String> = emptyMap(),
)
