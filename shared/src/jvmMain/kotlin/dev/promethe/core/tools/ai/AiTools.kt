package dev.promethe.core.tools.ai

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.promethe.core.PrometheJson
import dev.promethe.core.providers.Capability
import dev.promethe.core.providers.CapabilityResolution
import dev.promethe.core.providers.CapabilityRouter
import dev.promethe.core.providers.ProviderEntry
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

private val lenientJson = PrometheJson

// ── Args ─────────────────────────────────────────────────────────────

@Serializable
data class EmbeddingArgs(
    @property:LLMDescription("Text to generate embeddings for.")
    val text: String,
    @property:LLMDescription("Embedding model. Default 'text-embedding-3-small'.")
    val model: String = "text-embedding-3-small",
)

@Serializable
data class SpeechToTextArgs(
    @property:LLMDescription("Path to the audio file (mp3, wav, m4a, etc.).")
    val path: String,
    @property:LLMDescription("Language code (e.g. 'en', 'fr'). Optional — auto-detect if empty.")
    val language: String = "",
    @property:LLMDescription("Model: 'whisper-1'. Default 'whisper-1'.")
    val model: String = "whisper-1",
)

@Serializable
data class VectorSearchArgs(
    @property:LLMDescription("Action: 'search' to find similar vectors, 'add' to store a new vector. Default 'search'.")
    val action: String = "search",
    @property:LLMDescription("Search query text (used when action='search').")
    val query: String = "",
    @property:LLMDescription("Text content to store (used when action='add').")
    val text: String = "",
    @property:LLMDescription(
        "Pre-computed embedding as comma-separated floats (used when action='add'). If empty, text will be stored with an empty embedding.",
    )
    val embedding: String = "",
    @property:LLMDescription("Maximum number of results. Default 5.")
    val topK: Int = 5,
    @property:LLMDescription("Collection/namespace to search in. Default 'default'.")
    val collection: String = "default",
    @property:LLMDescription("Query embedding as comma-separated floats (used when action='search'). Required for similarity search.")
    val queryEmbedding: String = "",
)

// ── Local Vector Store ──────────────────────────────────────────────

/**
 * In-memory vector store with cosine-similarity search and JSON persistence.
 *
 * Stored at `{baseDir}/vector_store.json`. Each collection is a named group
 * of vectors. No external dependencies — pure Kotlin math.
 */
class LocalVectorStore(
    private val baseDir: String,
) {
    @Serializable
    data class VectorEntry(
        val id: String,
        val text: String,
        val embedding: List<Float>,
        val collection: String,
        val metadata: Map<String, String> = emptyMap(),
    )

    @Serializable
    data class StoreData(
        val entries: MutableList<VectorEntry> = mutableListOf(),
    )

    private val storeFile = java.io.File(baseDir, "vector_store.json")
    private val data: StoreData by lazy { loadOrCreate() }
    private val json = PrometheJson

    // ── Persistence ──────────────────────────────────────────────────

    private fun loadOrCreate(): StoreData {
        if (!storeFile.exists()) return StoreData()
        return try {
            json.decodeFromString<StoreData>(storeFile.readText())
        } catch (_: Exception) {
            StoreData()
        }
    }

    private fun persist() {
        storeFile.parentFile?.mkdirs()
        storeFile.writeText(json.encodeToString(StoreData.serializer(), data))
    }

    // ── Operations ───────────────────────────────────────────────────

    /** Add a vector entry. Returns the generated ID. */
    fun add(
        text: String,
        embedding: FloatArray,
        collection: String = "default",
        metadata: Map<String, String> = emptyMap(),
    ): String {
        val id = "vec_${System.currentTimeMillis()}_${data.entries.size}"
        data.entries.add(
            VectorEntry(
                id = id,
                text = text,
                embedding = embedding.toList(),
                collection = collection,
                metadata = metadata,
            ),
        )
        persist()
        return id
    }

    /** Search for top-K similar vectors by cosine similarity. */
    fun search(
        queryEmbedding: FloatArray,
        topK: Int = 5,
        collection: String = "default",
    ): List<ScoredEntry> {
        val candidates = data.entries.filter {
            it.collection == collection && it.embedding.isNotEmpty()
        }
        if (candidates.isEmpty()) return emptyList()

        return candidates
            .map { entry ->
                val entryVec = entry.embedding.toFloatArray()
                val score = cosineSimilarity(queryEmbedding, entryVec)
                ScoredEntry(entry, score)
            }
            .sortedByDescending { it.score }
            .take(topK)
    }

    /** Count entries in a collection. */
    fun count(collection: String = "default"): Int = data.entries.count { it.collection == collection }

    /** Total entries across all collections. */
    fun totalCount(): Int = data.entries.size

    data class ScoredEntry(
        val entry: VectorEntry,
        val score: Double,
    )

    // ── Cosine Similarity (pure Kotlin math) ─────────────────────────

    companion object {
        fun cosineSimilarity(
            a: FloatArray,
            b: FloatArray,
        ): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val len = minOf(a.size, b.size)
            var dot = 0.0
            var normA = 0.0
            var normB = 0.0
            for (i in 0 until len) {
                dot += a[i].toDouble() * b[i].toDouble()
                normA += a[i].toDouble() * a[i].toDouble()
                normB += b[i].toDouble() * b[i].toDouble()
            }
            val denom = Math.sqrt(normA) * Math.sqrt(normB)
            return if (denom == 0.0) 0.0 else dot / denom
        }
    }
}

// ── Tools ────────────────────────────────────────────────────────────

class EmbeddingTool(
    private val httpClient: HttpClient,
    private val apiKeys: Map<String, String>,
    private val router: CapabilityRouter,
    private val defaultProvider: String = "openai",
    private val defaultModel: String = "text-embedding-3-small",
    private val defaultBaseUrl: String = "",
    private val defaultDimensions: Int = 768,
) : SimpleTool<EmbeddingArgs>(
        argsType = typeToken<EmbeddingArgs>(),
        name = "embedding",
        description = "Generate text embeddings using the configured embedding provider (OpenAI, Gemini, Ollama, Cohere, Voyage, Mistral).",
    ) {
    override suspend fun execute(args: EmbeddingArgs): String {
        val resolution = router.resolve(Capability.EMBEDDINGS)
        return when (resolution) {
            is CapabilityResolution.NotConfigured -> resolution.message
            is CapabilityResolution.Ready -> embedWith(resolution.provider, args)
            is CapabilityResolution.MultipleAvailable -> embedWith(resolution.providers.first(), args)
        }
    }

    private suspend fun embedWith(
        provider: ProviderEntry,
        args: EmbeddingArgs,
    ): String {
        val providerName = when (provider.id) {
            "openai-embed" -> "openai"
            "google-embed" -> "gemini"
            "ollama-embed" -> "ollama"
            "cohere-embed" -> "cohere"
            "voyage-embed" -> "voyage"
            "mistral-embed" -> "mistral"
            else -> defaultProvider
        }
        val apiKey = apiKeys[provider.credentialKeys.first()] ?: ""
        val service = dev.promethe.core.rag.EmbeddingServiceFactory.create(
            provider = providerName,
            model = args.model.ifBlank { defaultModel },
            apiKey = apiKey,
            baseUrl = defaultBaseUrl,
            dimensions = defaultDimensions,
        )
        return try {
            val embedding = service.embed(args.text)
            val dims = embedding.size
            val preview = embedding.take(5).joinToString(", ") { "%.6f".format(it) }
            "Embedding generated via ${provider.name}: $dims dimensions [$preview, ...]"
        } catch (e: Exception) {
            "[ERROR] Embedding via ${provider.name}: ${e.message}"
        }
    }
}

/**
 * STT provider configuration. Injected at registration time from
 * VoiceProviderRegistry settings or env vars.
 */
data class SttConfig(
    val provider: String = "openai", // "openai", "deepgram", "google", etc.
    val baseUrl: String = "https://api.openai.com",
    val apiKey: String = "",
)

class SpeechToTextTool(
    private val httpClient: HttpClient,
    private val apiKeys: Map<String, String>,
    private val workDir: String,
    private val sttConfig: SttConfig? = null,
) : SimpleTool<SpeechToTextArgs>(
        argsType = typeToken<SpeechToTextArgs>(),
        name = "speech_to_text",
        description = "Transcribe audio files using a speech-to-text provider (default: OpenAI Whisper).",
    ) {
    override suspend fun execute(args: SpeechToTextArgs): String {
        // Route on the configured provider. Only OpenAI-compatible transcription
        // endpoints (/v1/audio/transcriptions) are implemented — refuse anything
        // else explicitly rather than silently calling Whisper with wrong creds.
        val provider = sttConfig?.provider?.lowercase()?.takeIf { it.isNotBlank() } ?: "openai"
        if (provider !in OPENAI_COMPATIBLE_PROVIDERS) {
            return "[ERROR] STT provider '$provider' is not supported by speech_to_text " +
                "(OpenAI-compatible endpoints only: ${OPENAI_COMPATIBLE_PROVIDERS.joinToString()}). " +
                "Unset STT_PROVIDER or point STT_BASE_URL at an OpenAI-compatible server."
        }

        // Resolve API key: prefer sttConfig, fallback to apiKeys map
        val effectiveKey = sttConfig?.apiKey?.takeIf { it.isNotBlank() }
            ?: apiKeys["openai"]
            ?: ""
        if (effectiveKey.isBlank()) return "[ERROR] No API key configured for STT provider."

        val effectiveBaseUrl = sttConfig?.baseUrl?.takeIf { it.isNotBlank() }
            ?: "https://api.openai.com"

        return transcribeViaWhisper(args, effectiveKey, effectiveBaseUrl)
    }

    private companion object {
        val OPENAI_COMPATIBLE_PROVIDERS = setOf("openai", "whisper", "litellm")
        const val SUCCESS_STATUS_MIN = 200
        const val SUCCESS_STATUS_MAX = 299
    }

    private suspend fun transcribeViaWhisper(
        args: SpeechToTextArgs,
        apiKey: String,
        baseUrl: String = "https://api.openai.com",
    ): String {
        val file = resolveWorkspaceFile(args.path) ?: return "[ERROR] File not found or outside workspace: ${args.path}"

        return try {
            val endpoint = "${baseUrl.trimEnd('/')}/v1/audio/transcriptions"
            val response =
                httpClient.post(endpoint) {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append(
                                    key = "file",
                                    value = file.readBytes(),
                                    headers =
                                        Headers.build {
                                            append(
                                                HttpHeaders.ContentDisposition,
                                                "form-data; name=\"file\"; filename=\"${file.name}\"",
                                            )
                                        },
                                )
                                append("model", args.model)
                                if (args.language.isNotBlank()) append("language", args.language)
                            },
                        ),
                    )
                }
            val output = response.bodyAsText()
            if (response.status.value !in SUCCESS_STATUS_MIN..SUCCESS_STATUS_MAX) {
                return "[ERROR] Whisper transcription failed: HTTP ${response.status.value}"
            }
            val parsed = lenientJson.parseToJsonElement(output).jsonObject
            parsed["text"]?.jsonPrimitive?.content ?: output
        } catch (e: Exception) {
            "[ERROR] Whisper transcription failed: ${e.message}"
        }
    }

    private fun resolveWorkspaceFile(requestedPath: String): File? =
        try {
            val root = Path.of(workDir).toRealPath()
            val candidate = root.resolve(requestedPath).normalize()
            if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) return null
            candidate.toRealPath().takeIf { it.startsWith(root) }?.toFile()
        } catch (_: Exception) {
            null
        }
}

class VectorSearchTool(
    private val workDir: String,
) : SimpleTool<VectorSearchArgs>(
        argsType = typeToken<VectorSearchArgs>(),
        name = "vector_search",
        description = "Local vector store with cosine similarity. " +
            "Actions: 'add' (store text + embedding), 'search' (find top-K similar). " +
            "Provide embeddings as comma-separated floats. Data persists to vector_store.json.",
    ) {
    private val store by lazy { LocalVectorStore(workDir) }

    override suspend fun execute(args: VectorSearchArgs): String =
        when (args.action.lowercase().trim()) {
            "add" -> {
                executeAdd(args)
            }

            "search" -> {
                executeSearch(args)
            }

            "count" -> {
                val total = store.count(args.collection)
                "Collection '${args.collection}' contains $total vectors (${store.totalCount()} total across all collections)."
            }

            else -> {
                "[ERROR] Unknown action '${args.action}'. Use 'add', 'search', or 'count'."
            }
        }

    private fun executeAdd(args: VectorSearchArgs): String {
        if (args.text.isBlank()) {
            return "[ERROR] 'text' is required for action='add'."
        }
        val embedding = parseEmbedding(args.embedding)
        val id = store.add(
            text = args.text,
            embedding = embedding,
            collection = args.collection,
        )
        val dims = embedding.size
        return "Vector stored: id=$id, collection='${args.collection}', dimensions=$dims, " +
            "total=${store.count(args.collection)} vectors in collection."
    }

    private fun executeSearch(args: VectorSearchArgs): String {
        val queryVec = parseEmbedding(args.queryEmbedding)
        if (queryVec.isEmpty()) {
            return "[ERROR] 'queryEmbedding' is required for action='search'. " +
                "Provide a comma-separated list of floats. " +
                "Use the 'embedding' tool first to generate embeddings from text."
        }

        val count = store.count(args.collection)
        if (count == 0) {
            return "[INFO] Collection '${args.collection}' is empty. " +
                "Use action='add' to store vectors first."
        }

        val results = store.search(
            queryEmbedding = queryVec,
            topK = args.topK,
            collection = args.collection,
        )

        if (results.isEmpty()) {
            return "[INFO] No results found in collection '${args.collection}'."
        }

        val sb = StringBuilder()
        sb.appendLine("Found ${results.size} results in '${args.collection}' (searched $count vectors):")
        sb.appendLine()
        results.forEachIndexed { i, scored ->
            sb.appendLine("${i + 1}. [score=%.4f] id=${scored.entry.id}".format(scored.score))
            sb.appendLine("   ${scored.entry.text.take(200)}${if (scored.entry.text.length > 200) "..." else ""}")
            if (scored.entry.metadata.isNotEmpty()) {
                sb.appendLine("   meta: ${scored.entry.metadata}")
            }
        }
        return sb.toString().trimEnd()
    }

    /** Parse a comma-separated float string into a FloatArray. */
    private fun parseEmbedding(raw: String): FloatArray {
        if (raw.isBlank()) return FloatArray(0)
        return try {
            raw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { it.toFloat() }
                .toFloatArray()
        } catch (_: NumberFormatException) {
            FloatArray(0)
        }
    }
}
