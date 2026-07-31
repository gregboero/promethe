package dev.promethe.core

import dev.promethe.core.hooks.*
import dev.promethe.core.sandbox.SandboxedCommandRunner
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * PluginLoader — dynamic plugin discovery and loading system.
 *
 * Scans a `plugins/` directory for plugin manifests and loads:
 *   - Custom tools (tool definitions in JSON)
 *   - Custom system prompts (persona overlays)
 *   - Webhook channel presets
 *   - MCP server configurations
 *
 * Plugin structure:
 *   plugins/
 *     my-plugin/
 *       plugin.json          ← manifest (name, version, description)
 *       tools/               ← custom tool definitions
 *         my_tool.json       ← tool schema + script
 *       prompts/             ← system prompt fragments
 *         persona.md
 *       webhooks/            ← webhook channel presets
 *         telegram.json
 *       mcp/                 ← MCP server configs
 *         my-server.json
 *
 * Each plugin is isolated and can be enabled/disabled at runtime.
 */
class PluginLoader(
    private val pluginsDir: String = "plugins",
    /**
     * LAB plugins are disabled unless the local process owner explicitly
     * enables them. A manifest cannot enable the runtime by itself.
     */
    private val runtimeEnabled: Boolean = false,
    /**
     * A missing runner disables executable plugin hooks. It must never cause a
     * fallback to a host shell or direct process launcher.
     */
    private val commandRunner: SandboxedCommandRunner? = null,
    private val approvalGate: ApprovalGate? = null,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

    private data class LoadedPlugin(
        val manifest: PluginManifest,
        val directory: File,
    )

    private val loadedPlugins = mutableMapOf<String, LoadedPlugin>()

    @Serializable
    data class PluginManifest(
        val name: String,
        val version: String = "1.0.0",
        val description: String = "",
        val author: String = "",
        val enabled: Boolean = true,
        val tools: List<PluginTool> = emptyList(),
        val prompts: List<String> = emptyList(), // Filenames in prompts/ dir
        val webhooks: List<String> = emptyList(), // Filenames in webhooks/ dir
        val mcpServers: List<String> = emptyList(), // Filenames in mcp/ dir
        val hooks: List<PluginHookDef> = emptyList(), // Hook definitions
    )

    @Serializable
    data class PluginTool(
        val name: String,
        val description: String,
        /** Deprecated legacy shell string. It is retained for decoding only and is never executed. */
        val command: String = "",
        val executable: String = "",
        val arguments: List<String> = emptyList(),
        val script: String = "", // Script file path (relative to plugin)
        val parameters: List<PluginToolParam> = emptyList(),
    )

    @Serializable
    data class PluginToolParam(
        val name: String,
        val description: String,
        val type: String = "string", // "string", "number", "boolean"
        val required: Boolean = true,
    )

    /**
     * Scan the plugins directory and load all valid manifests.
     */
    fun discover(): List<PluginManifest> {
        val dir = File(pluginsDir)
        if (!dir.exists() || !dir.isDirectory) {
            logger.debug { "No plugins directory found at: $pluginsDir" }
            loadedPlugins.clear()
            return emptyList()
        }

        val canonicalRoot = dir.canonicalFile
        loadedPlugins.clear()
        val plugins = mutableListOf<PluginManifest>()

        for (subDir in canonicalRoot.listFiles()?.filter { it.isDirectory } ?: emptyList()) {
            val canonicalPluginDir = runCatching { subDir.canonicalFile }.getOrNull()
            if (canonicalPluginDir == null) {
                logger.warn { "Skipping unreadable plugin directory '${subDir.name}'" }
                continue
            }
            if (canonicalPluginDir.parentFile != canonicalRoot || canonicalPluginDir.name != subDir.name) {
                logger.warn { "Skipping plugin directory '${subDir.name}': canonical path escapes the plugin root" }
                continue
            }

            val manifestFile =
                runCatching { File(canonicalPluginDir, "plugin.json").canonicalFile }
                    .getOrNull()
            if (
                manifestFile == null ||
                manifestFile.parentFile != canonicalPluginDir ||
                manifestFile.name != "plugin.json" ||
                !manifestFile.isFile
            ) {
                logger.debug { "Skipping ${subDir.name}: no plugin.json" }
                continue
            }

            try {
                val decoded = json.decodeFromString<PluginManifest>(manifestFile.readText())
                require(SAFE_PLUGIN_NAME.matches(decoded.name)) { "Invalid plugin name" }
                require(decoded.name == canonicalPluginDir.name) {
                    "Plugin name must exactly match its directory"
                }
                require(decoded.hooks.all { SAFE_COMPONENT_ID.matches(it.id) }) {
                    "Plugin hook ids may contain only letters, digits, dots, underscores, and dashes"
                }

                val manifest = decoded.copy(enabled = runtimeEnabled && decoded.enabled)
                loadedPlugins[manifest.name] = LoadedPlugin(manifest, canonicalPluginDir)
                plugins.add(manifest)
                logger.info {
                    "Discovered plugin: ${manifest.name} v${manifest.version} " +
                        "(runtime=${if (manifest.enabled) "enabled" else "disabled"})"
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to load ${subDir.name}/plugin.json" }
            }
        }

        return plugins
    }

    /**
     * Get all loaded and enabled plugins.
     */
    fun getEnabledPlugins(): List<PluginManifest> =
        loadedPlugins.values
            .map(LoadedPlugin::manifest)
            .filter(PluginManifest::enabled)

    /**
     * Get all plugin tools (from all enabled plugins).
     */
    fun getAllTools(): List<PluginTool> = getEnabledPlugins().flatMap { it.tools }

    /**
     * Load system prompt fragments from all enabled plugins.
     * These are appended to the base system prompt.
     */
    fun loadPromptFragments(): List<String> {
        val fragments = mutableListOf<String>()

        for (plugin in getEnabledPlugins()) {
            for (promptFile in plugin.prompts) {
                resolvePluginAsset(plugin.name, "prompts", promptFile)?.let { file ->
                    fragments.add(file.readText())
                }
            }
        }

        return fragments
    }

    /**
     * Load MCP server configurations from all enabled plugins.
     * Returns raw JSON strings for each MCP config.
     */
    fun loadMcpConfigs(): List<String> {
        val configs = mutableListOf<String>()

        for (plugin in getEnabledPlugins()) {
            for (mcpFile in plugin.mcpServers) {
                resolvePluginAsset(plugin.name, "mcp", mcpFile)?.let { file ->
                    configs.add(file.readText())
                }
            }
        }

        return configs
    }

    /**
     * Load webhook channel presets from all enabled plugins.
     * Returns raw JSON strings for each webhook config.
     */
    fun loadWebhookPresets(): List<String> {
        val presets = mutableListOf<String>()

        for (plugin in getEnabledPlugins()) {
            for (webhookFile in plugin.webhooks) {
                resolvePluginAsset(plugin.name, "webhooks", webhookFile)?.let { file ->
                    presets.add(file.readText())
                }
            }
        }

        return presets
    }

    /**
     * Get a summary of all loaded plugins for status reporting.
     */
    fun getStatus(): Map<String, Any> =
        mapOf(
            "pluginsDir" to pluginsDir,
            "totalPlugins" to loadedPlugins.size,
            "enabledPlugins" to getEnabledPlugins().size,
            "totalTools" to getAllTools().size,
            "plugins" to
                loadedPlugins.values.map(LoadedPlugin::manifest).map { p ->
                    mapOf(
                        "name" to p.name,
                        "version" to p.version,
                        "enabled" to p.enabled,
                        "tools" to p.tools.size,
                        "hooks" to p.hooks.size,
                        "prompts" to p.prompts.size,
                        "description" to p.description,
                    )
                },
        )

    // ── Plugin Hook Definitions ─────────────────────────────

    @Serializable
    data class PluginHookDef(
        val id: String,
        val events: List<String>, // e.g. ["BEFORE_TOOL_CALL", "ON_ERROR"]
        /** Deprecated legacy shell string. It is retained for decoding only and is never executed. */
        val command: String = "",
        /** Explicit executable to run through the sandbox. */
        val executable: String = "",
        /** Literal arguments for [executable]; no command parsing is performed. */
        val arguments: List<String> = emptyList(),
        /** Deprecated legacy script path. Configure an executable and literal arguments instead. */
        val script: String = "",
        val priority: Int = 100,
    )

    /**
     * Load hooks from all enabled plugins and register them into the HookManager.
     * Plugin hooks only execute explicit executable-plus-arguments definitions.
     */
    suspend fun loadAndRegisterHooks(hookManager: HookManager) {
        for (plugin in getEnabledPlugins()) {
            for (hookDef in plugin.hooks) {
                val events =
                    hookDef.events
                        .mapNotNull { eventName ->
                            try {
                                HookEvent.valueOf(eventName)
                            } catch (e: Exception) {
                                logger.debug(e) { "Unknown hook event name '$eventName' in plugin hook '${hookDef.id}'" }
                                null
                            }
                        }.toSet()

                if (events.isEmpty()) {
                    logger.warn { "Hook '${hookDef.id}' has no valid events, skipping" }
                    continue
                }

                val pluginHook =
                    PluginScriptHook(
                        hookId = "plugin.${plugin.name}.${hookDef.id}",
                        hookEvents = events,
                        hookPriority = hookDef.priority,
                        commandRunner = commandRunner,
                        approvalGate = approvalGate,
                        executable = hookDef.executable,
                        arguments = hookDef.arguments,
                        hasLegacyShellConfiguration = hookDef.command.isNotBlank() || hookDef.script.isNotBlank(),
                    )
                hookManager.register(pluginHook)
                logger.info { "Registered hook: ${pluginHook.id} on ${events.map { it.name }}" }
            }
        }
    }

    private fun resolvePluginAsset(
        pluginName: String,
        category: String,
        requestedPath: String,
    ): File? {
        if (requestedPath.isBlank() || File(requestedPath).isAbsolute) return null
        val plugin = loadedPlugins[pluginName] ?: return null
        val categoryRoot =
            runCatching { File(plugin.directory, category).canonicalFile }
                .getOrNull()
                ?: return null
        if (!categoryRoot.toPath().startsWith(plugin.directory.toPath())) {
            logger.warn { "Rejected plugin category outside its canonical plugin directory" }
            return null
        }
        val candidate =
            runCatching { File(categoryRoot, requestedPath).canonicalFile }
                .getOrNull()
                ?: return null
        if (!candidate.toPath().startsWith(categoryRoot.toPath()) || !candidate.isFile) {
            logger.warn { "Rejected plugin asset outside its canonical '$category' directory" }
            return null
        }
        return candidate
    }

    private companion object {
        val SAFE_PLUGIN_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        val SAFE_COMPONENT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}

/**
 * A hook implementation backed by an explicit executable and literal arguments.
 * Legacy shell commands and script paths are rejected rather than interpreted.
 */
class PluginScriptHook(
    private val hookId: String,
    private val hookEvents: Set<HookEvent>,
    private val hookPriority: Int = 100,
    private val commandRunner: SandboxedCommandRunner? = null,
    private val approvalGate: ApprovalGate? = null,
    private val executable: String = "",
    private val arguments: List<String> = emptyList(),
    private val hasLegacyShellConfiguration: Boolean = false,
) : Hook {
    override val id = hookId
    override val events = hookEvents
    override val priority = hookPriority

    override suspend fun execute(context: HookContext): HookResult {
        if (hasLegacyShellConfiguration) {
            logger.warn { "$hookId uses a deprecated shell command/script and was not executed" }
            return HookResult.Continue
        }
        if (executable.isBlank()) return HookResult.Continue
        val runner = commandRunner
        val gate = approvalGate
        if (runner == null || gate == null) {
            logger.warn { "$hookId is unavailable because sandboxed approval is not configured" }
            return HookResult.Continue
        }

        val approvalArguments =
            canonicalJson(
                buildJsonObject {
                    put("executable", executable)
                    put(
                        "arguments",
                        buildJsonArray {
                            arguments.forEach { add(JsonPrimitive(it)) }
                        },
                    )
                },
            )
        val approval =
            gate.checkMandatory(
                toolName = "plugin_hook",
                args = approvalArguments,
                sessionId = context.sessionId.ifBlank { "plugin-hook" },
            )
        if (!approval.allowed) {
            return HookResult.Abort("Plugin hook execution was not approved")
        }

        val result =
            runner.execute(
                executable = executable,
                arguments = arguments,
                sessionId = context.sessionId,
                timeoutMillis = PLUGIN_HOOK_TIMEOUT_MILLIS,
            )
        if (result.errorCode != null || result.exitCode != 0) {
            logger.warn { "$hookId failed through the sandbox (${result.errorCode ?: "exit ${result.exitCode}"})" }
        }
        return HookResult.Continue
    }

    private companion object {
        const val PLUGIN_HOOK_TIMEOUT_MILLIS = 5_000L
    }
}
