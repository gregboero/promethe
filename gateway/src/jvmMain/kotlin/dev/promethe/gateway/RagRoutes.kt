package dev.promethe.gateway

import dev.promethe.api.*
import dev.promethe.core.CredentialsStore
import dev.promethe.core.rag.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/** Current RAG config (in-memory, persisted to credentials.json). */
private var currentRagConfig = RagConfig()

/** Lazy-initialized KnowledgeBase — rebuilt when config changes. */
private var knowledgeBase: KnowledgeBase? = null

// ── Conversion helpers: Credentials ↔ RagConfig ──

private fun CredentialsStore.Credentials.toRagConfig() =
    RagConfig(
        enabled = ragEnabled,
        embeddingProvider = runCatching { EmbeddingProvider.valueOf(ragEmbeddingProvider.uppercase()) }
            .getOrDefault(EmbeddingProvider.OLLAMA),
        embeddingModel = ragEmbeddingModel,
        embeddingBaseUrl = ragEmbeddingBaseUrl,
        embeddingApiKey = ragEmbeddingApiKey,
        embeddingDimensions = ragEmbeddingDimensions,
        vectorStoreType = runCatching { VectorStoreType.valueOf(ragVectorStoreType.uppercase()) }
            .getOrDefault(VectorStoreType.SQLITE_VEC),
        vectorStoreUrl = ragVectorStoreUrl,
        vectorStoreApiKey = ragVectorStoreApiKey,
        vectorStorePath = ragVectorStorePath,
        chunkSize = ragChunkSize,
        chunkOverlap = ragChunkOverlap,
    )

private fun CredentialsStore.Credentials.withRagConfig(config: RagConfig) =
    copy(
        ragEnabled = config.enabled,
        ragEmbeddingProvider = config.embeddingProvider.name.lowercase(),
        ragEmbeddingModel = config.embeddingModel,
        ragEmbeddingBaseUrl = config.embeddingBaseUrl,
        ragEmbeddingApiKey = config.embeddingApiKey,
        ragEmbeddingDimensions = config.embeddingDimensions,
        ragVectorStoreType = config.vectorStoreType.name.lowercase(),
        ragVectorStoreUrl = config.vectorStoreUrl,
        ragVectorStoreApiKey = config.vectorStoreApiKey,
        ragVectorStorePath = config.vectorStorePath,
        ragChunkSize = config.chunkSize,
        ragChunkOverlap = config.chunkOverlap,
    )

/**
 * Build or rebuild the KnowledgeBase from the current RagConfig.
 */
private fun rebuildKnowledgeBase(config: RagConfig): KnowledgeBase? {
    if (!config.enabled) {
        logger.info { "RAG disabled, no KnowledgeBase created" }
        return null
    }

    return try {
        val embeddingService = EmbeddingServiceFactory.create(
            provider = config.embeddingProvider.name.lowercase(),
            model = config.embeddingModel,
            baseUrl = config.embeddingBaseUrl,
            apiKey = config.embeddingApiKey,
        )

        val vectorStore = VectorStoreFactory.create(
            storeType = config.vectorStoreType.name.lowercase(),
            url = config.vectorStoreUrl,
            apiKey = config.vectorStoreApiKey,
            path = config.vectorStorePath,
            vectorSize = config.embeddingDimensions,
        )

        val chunker = DocumentChunker(
            maxChunkSize = config.chunkSize,
            overlapSize = config.chunkOverlap,
        )

        KnowledgeBase(embeddingService, vectorStore, chunker).also {
            logger.info { "KnowledgeBase created: ${config.embeddingProvider}/${config.embeddingModel} + ${config.vectorStoreType}" }
        }
    } catch (e: Exception) {
        logger.error(e) { "Failed to create KnowledgeBase" }
        null
    }
}

fun Route.ragRoutes() {
    // Load saved RAG config from credentials.json at startup
    CredentialsStore.load()?.let { creds ->
        val saved = creds.toRagConfig()
        currentRagConfig = saved
        if (saved.enabled) {
            knowledgeBase = rebuildKnowledgeBase(saved)
        }
        logger.info { "RAG config loaded from credentials (enabled=${saved.enabled})" }
    }

    route("/rag") {
        // ── Liste des modèles d'embedding connus ──
        get("/models") {
            try {
                call.respond(EmbeddingModelInfo.KNOWN_MODELS)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Internal error"))
            }
        }

        // ── Config GET/PUT ──
        get("/config") {
            try {
                call.respond(currentRagConfig)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Internal error"))
            }
        }

        put("/config") {
            try {
                val config = call.receive<RagConfig>()
                val oldConfig = currentRagConfig
                currentRagConfig = config

                // Rebuild si la config a changé
                if (config.enabled && (config != oldConfig || knowledgeBase == null)) {
                    knowledgeBase?.close()
                    knowledgeBase = rebuildKnowledgeBase(config)

                    // Enregistrer le tool knowledge_search dans l'agent
                    val kb = knowledgeBase
                    if (kb != null) {
                        dev.promethe.core.ToolRegistry.register(KnowledgeSearchTool(kb))
                        logger.info { "knowledge_search tool registered via Settings UI" }
                    }
                } else if (!config.enabled) {
                    knowledgeBase?.close()
                    knowledgeBase = null
                }

                // Persist to credentials.json
                CredentialsStore.update { credentials ->
                    credentials.withRagConfig(config)
                }

                call.respond(currentRagConfig)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Internal error"))
            }
        }

        // ── Test de connexion embedding ──
        post("/test-embedding") {
            try {
                // Créer un service temporaire pour tester
                val config = currentRagConfig
                val embeddingService = EmbeddingServiceFactory.create(
                    provider = config.embeddingProvider.name.lowercase(),
                    model = config.embeddingModel,
                    baseUrl = config.embeddingBaseUrl,
                    apiKey = config.embeddingApiKey,
                )

                val result = embeddingService.testConnection()
                embeddingService.close()

                call.respond(
                    RagTestResponse(
                        success = result.success,
                        provider = config.embeddingProvider.name,
                        model = config.embeddingModel,
                        dimensions = result.dimensions,
                        latencyMs = result.latencyMs,
                        error = result.error,
                    ),
                )
            } catch (e: Exception) {
                call.respond(
                    RagTestResponse(
                        success = false,
                        provider = currentRagConfig.embeddingProvider.name,
                        model = currentRagConfig.embeddingModel,
                        dimensions = 0,
                        latencyMs = 0L,
                        error = e.message,
                    ),
                )
            }
        }

        // ── Liste des documents ingérés ──
        get("/documents") {
            try {
                val kb = knowledgeBase
                if (kb == null) {
                    call.respond(emptyList<RagDocumentInfo>())
                    return@get
                }

                val docs = kb.listDocuments().map { doc ->
                    RagDocumentInfo(
                        id = doc.id,
                        filename = doc.filename,
                        chunkCount = doc.chunkCount,
                        totalTokens = doc.totalTokens,
                        ingestedAt = doc.ingestedAt,
                    )
                }
                call.respond(docs)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Internal error"))
            }
        }

        // ── Ingestion de document ──
        post("/ingest") {
            try {
                val kb = knowledgeBase
                if (kb == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("RAG non activé"))
                    return@post
                }

                val request = call.receive<RagIngestRequest>()
                val info = kb.ingest(
                    content = request.content,
                    filename = request.filename,
                    metadata = request.metadata,
                )

                call.respond(
                    RagDocumentInfo(
                        id = info.id,
                        filename = info.filename,
                        chunkCount = info.chunkCount,
                        totalTokens = info.totalTokens,
                        ingestedAt = info.ingestedAt,
                    ),
                )
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Ingestion failed"))
            }
        }

        // ── Recherche sémantique ──
        post("/search") {
            try {
                val kb = knowledgeBase
                if (kb == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("RAG non activé"))
                    return@post
                }

                val request = call.receive<RagSearchRequest>()
                val startTime = System.currentTimeMillis()
                val results = kb.search(request.query, request.topK)
                val elapsed = System.currentTimeMillis() - startTime

                call.respond(
                    RagSearchResponse(
                        results = results.map { r ->
                            RagSearchResult(
                                content = r.content,
                                score = r.score,
                                source = r.source,
                                metadata = r.metadata,
                            )
                        },
                        queryTimeMs = elapsed,
                    ),
                )
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Search failed"))
            }
        }

        // ── Suppression de document ──
        delete("/documents/{id}") {
            try {
                val kb = knowledgeBase
                if (kb == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("RAG non activé"))
                    return@delete
                }

                val docId = call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing document id"))
                    return@delete
                }

                kb.deleteDocument(docId)
                call.respond(HttpStatusCode.OK, mapOf("deleted" to docId))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Delete failed"))
            }
        }

        // ── Stats / connectivity ──
        get("/status") {
            try {
                val kb = knowledgeBase
                if (kb == null) {
                    call.respond(
                        mapOf(
                            "enabled" to false,
                            "provider" to currentRagConfig.embeddingProvider.name,
                            "vectorStore" to currentRagConfig.vectorStoreType.name,
                        ),
                    )
                    return@get
                }

                val connectivity = kb.testConnectivity()
                val totalChunks = kb.totalChunks()

                call.respond(
                    mapOf(
                        "enabled" to true,
                        "embeddingOk" to connectivity.embeddingOk,
                        "vectorStoreOk" to connectivity.vectorStoreOk,
                        "provider" to connectivity.embeddingProvider,
                        "model" to connectivity.embeddingModel,
                        "vectorStore" to connectivity.vectorStoreType,
                        "dimensions" to connectivity.embeddingDimensions,
                        "totalChunks" to totalChunks,
                        "documents" to kb.listDocuments().size,
                    ),
                )
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(e.message ?: "Status check failed"))
            }
        }
    }
}
