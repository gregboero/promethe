package dev.promethe.core.rag

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable

private val logger = KotlinLogging.logger {}

// ══════════════════════════════════════════════════════════════
// Outils de gestion de la base de connaissances RAG :
//   - KnowledgeSearchTool  → recherche sémantique
//   - KnowledgeIngestTool  → indexer un document
//   - KnowledgeDeleteTool  → supprimer / lister les documents
// ══════════════════════════════════════════════════════════════

// ── KnowledgeSearchTool ─────────────────────────────────────

@Serializable
data class KnowledgeSearchArgs(
    @property:LLMDescription("La requête de recherche en langage naturel dans la base de connaissances.")
    val query: String,
    @property:LLMDescription("Nombre maximum de résultats à retourner (défaut: 5).")
    val topK: Int = 5,
)

/**
 * KnowledgeSearchTool — recherche sémantique dans le RAG.
 *
 * Retourne les N chunks les plus pertinents avec score de similarité et source.
 */
class KnowledgeSearchTool(
    private val knowledgeBase: KnowledgeBase,
) : SimpleTool<KnowledgeSearchArgs>(
        argsType = typeToken<KnowledgeSearchArgs>(),
        name = "knowledge_search",
        description = "Search the local knowledge base (RAG). " +
            "Returns the most relevant passages to answer a question. " +
            "Use when information is not in the conversation context.",
    ) {
    override suspend fun execute(args: KnowledgeSearchArgs): String {
        val results = knowledgeBase.search(args.query, args.topK)

        if (results.isEmpty()) {
            return "ℹ️ Aucun résultat trouvé pour : \"${args.query}\""
        }

        return buildString {
            appendLine("🔍 Résultats de recherche (${results.size}) :")
            appendLine()
            for ((i, result) in results.withIndex()) {
                appendLine("--- Résultat ${i + 1} (score: ${String.format("%.3f", result.score)}) ---")
                if (result.source.isNotBlank()) appendLine("Source: ${result.source}")
                if (result.heading.isNotBlank()) appendLine("Section: ${result.heading}")
                appendLine()
                appendLine(result.content)
                appendLine()
            }
        }
    }
}

// ── KnowledgeIngestTool ─────────────────────────────────────

@Serializable
data class KnowledgeIngestArgs(
    @property:LLMDescription("Le contenu textuel du document à ingérer dans la base de connaissances.")
    val content: String,
    @property:LLMDescription("Nom du fichier ou identifiant de la source (ex: 'rapport.md', 'https://…').")
    val filename: String,
    @property:LLMDescription("Catégorie optionnelle pour étiqueter le document ingéré.")
    val category: String = "",
)

/**
 * KnowledgeIngestTool — indexe un document dans le RAG.
 *
 * Le contenu est découpé en chunks, vectorisé et stocké pour
 * être disponible lors des recherches sémantiques.
 */
class KnowledgeIngestTool(
    private val knowledgeBase: KnowledgeBase,
) : SimpleTool<KnowledgeIngestArgs>(
        argsType = typeToken<KnowledgeIngestArgs>(),
        name = "knowledge_ingest",
        description = "Ingest a document into the RAG knowledge base. " +
            "Use this tool to add text content (file, web page, note) " +
            "so it becomes available for semantic search.",
    ) {
    override suspend fun execute(args: KnowledgeIngestArgs): String {
        logger.info { "Ingestion du document '${args.filename}', catégorie='${args.category}', taille=${args.content.length} caractères" }

        return try {
            val metadata = buildMap {
                if (args.category.isNotBlank()) put("category", args.category)
            }

            val docInfo = knowledgeBase.ingest(
                content = args.content,
                filename = args.filename,
                metadata = metadata,
            )

            logger.info { "Ingestion réussie : ${docInfo.chunkCount} chunks pour '${args.filename}'" }
            buildString {
                appendLine("✅ Document ingéré avec succès !")
                appendLine("📄 Fichier : ${args.filename}")
                appendLine("🔑 ID : ${docInfo.id}")
                appendLine("📦 Chunks : ${docInfo.chunkCount}")
                appendLine("📊 Tokens estimés : ~${docInfo.totalTokens}")
                if (args.category.isNotBlank()) appendLine("🏷️ Catégorie : ${args.category}")
            }
        } catch (e: Exception) {
            logger.error(e) { "Erreur lors de l'ingestion de '${args.filename}'" }
            "❌ Erreur lors de l'ingestion de '${args.filename}' : ${e.message}"
        }
    }
}

// ── KnowledgeDeleteTool ─────────────────────────────────────

@Serializable
data class KnowledgeDeleteArgs(
    @property:LLMDescription("L'identifiant unique du document à supprimer. Utiliser listFirst=true pour obtenir la liste.")
    val docId: String,
    @property:LLMDescription("Si true, liste les documents au lieu de supprimer. Utile pour trouver le docId.")
    val listFirst: Boolean = false,
)

/**
 * KnowledgeDeleteTool — supprime ou liste les documents du RAG.
 *
 * Avec listFirst=true, retourne la liste des documents indexés.
 * Sinon, supprime le document correspondant au docId.
 */
class KnowledgeDeleteTool(
    private val knowledgeBase: KnowledgeBase,
) : SimpleTool<KnowledgeDeleteArgs>(
        argsType = typeToken<KnowledgeDeleteArgs>(),
        name = "knowledge_delete",
        description = "Delete a document from the RAG knowledge base by ID, " +
            "or list available documents if listFirst=true. " +
            "Use to remove obsolete or incorrect content.",
    ) {
    override suspend fun execute(args: KnowledgeDeleteArgs): String {
        if (args.listFirst) return listDocuments()
        return deleteDocument(args.docId)
    }

    private fun listDocuments(): String {
        logger.info { "Listing des documents RAG" }
        return try {
            val documents = knowledgeBase.listDocuments()
            if (documents.isEmpty()) return "La base de connaissances est vide."

            logger.info { "${documents.size} documents trouvés" }
            buildString {
                appendLine("📚 Documents (${documents.size}) :")
                appendLine()
                for (doc in documents) {
                    appendLine("• ${doc.filename}  (ID: ${doc.id})")
                    appendLine("  📦 ${doc.chunkCount} chunks · ~${doc.totalTokens} tokens")
                    appendLine()
                }
            }.trimEnd()
        } catch (e: Exception) {
            logger.error(e) { "Erreur lors du listing" }
            "❌ Erreur : ${e.message}"
        }
    }

    private suspend fun deleteDocument(docId: String): String {
        logger.info { "Suppression du document '$docId'" }
        return try {
            knowledgeBase.deleteDocument(docId)
            logger.info { "Document '$docId' supprimé" }
            "✅ Document '$docId' supprimé de la base de connaissances."
        } catch (e: Exception) {
            logger.error(e) { "Erreur lors de la suppression de '$docId'" }
            "❌ Erreur : ${e.message}"
        }
    }
}
