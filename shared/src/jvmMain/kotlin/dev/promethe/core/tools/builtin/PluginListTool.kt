package dev.promethe.core.tools.builtin

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.promethe.core.PluginLoader
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable

private val logger = KotlinLogging.logger {}

/**
 * Arguments pour l'outil de listage des plugins.
 */
@Serializable
data class PluginListToolArgs(
    @property:LLMDescription("Si vrai, affiche uniquement les plugins activés. Par défaut : false.")
    val onlyEnabled: Boolean = false,
)

/**
 * Outil permettant à l'agent de lister tous les plugins installés et leur statut.
 *
 * Affiche un résumé lisible avec des emojis incluant le nom, la version,
 * l'état d'activation, les outils, les hooks et les prompts de chaque plugin.
 */
class PluginListTool(
    private val pluginLoader: PluginLoader?,
) : SimpleTool<PluginListToolArgs>(
        argsType = typeToken<PluginListToolArgs>(),
        name = "plugin_list",
        description = "List all installed plugins and their status (availability, tools and activation state).",
    ) {
    override suspend fun execute(args: PluginListToolArgs): String {
        if (pluginLoader == null) {
            logger.warn { "PluginLoader non configuré, impossible de lister les plugins." }
            return "⚠️ Le système de plugins n'est pas configuré."
        }

        return try {
            val status = pluginLoader.getStatus()
            val pluginsDir = status["pluginsDir"] ?: "inconnu"
            val totalPlugins = status["totalPlugins"] ?: 0
            val enabledPlugins = status["enabledPlugins"] ?: 0
            val totalTools = status["totalTools"] ?: 0

            @Suppress("UNCHECKED_CAST")
            val plugins = (status["plugins"] as? List<Map<String, Any>>) ?: emptyList()

            val filteredPlugins = if (args.onlyEnabled) {
                plugins.filter { it["enabled"] == true }
            } else {
                plugins
            }

            val sb = StringBuilder()
            sb.appendLine("📦 **Plugins** — Répertoire : $pluginsDir")
            sb.appendLine("   Total : $totalPlugins | Activés : $enabledPlugins | Outils : $totalTools")
            sb.appendLine()

            if (filteredPlugins.isEmpty()) {
                sb.appendLine("ℹ️ Aucun plugin ${if (args.onlyEnabled) "activé " else ""}trouvé.")
            } else {
                for (plugin in filteredPlugins) {
                    val name = plugin["name"] ?: "sans nom"
                    val version = plugin["version"] ?: "?"
                    val enabled = plugin["enabled"] == true
                    val tools = plugin["tools"] ?: 0
                    val hooks = plugin["hooks"] ?: 0
                    val prompts = plugin["prompts"] ?: 0
                    val description = plugin["description"] ?: ""

                    val statusEmoji = if (enabled) "✅" else "❌"
                    sb.appendLine("$statusEmoji **$name** v$version")
                    if (description.toString().isNotBlank()) {
                        sb.appendLine("   📝 $description")
                    }
                    sb.appendLine("   🔧 Outils : $tools | 🪝 Hooks : $hooks | 💬 Prompts : $prompts")
                    sb.appendLine()
                }
            }

            logger.info { "Listage des plugins terminé : ${filteredPlugins.size} plugin(s) affiché(s)." }
            sb.toString().trimEnd()
        } catch (e: Exception) {
            logger.error(e) { "Erreur lors du listage des plugins." }
            "❌ Erreur lors de la récupération des plugins : ${e.message}"
        }
    }
}
