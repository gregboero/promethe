package dev.promethe.core.rag

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * SemanticChunker — splits documents by semantic boundaries instead of fixed token count.
 *
 * Algorithm:
 * 1. Split document into sentences
 * 2. Batch-embed all sentences
 * 3. Compute cosine similarity between consecutive sentence embeddings
 * 4. Identify "breakpoints" where similarity drops below threshold
 * 5. Group sentences into chunks at breakpoints
 * 6. Merge small chunks, split oversized chunks
 *
 * This produces chunks where each chunk covers a coherent semantic topic,
 * dramatically improving retrieval quality over fixed-size chunking.
 */
class SemanticChunker(
    private val embeddingService: EmbeddingService,
    /** Similarity below this threshold triggers a chunk boundary. */
    private val similarityThreshold: Double = 0.75,
    /** Max tokens per chunk (will force-split if exceeded). */
    private val maxChunkTokens: Int = 512,
    /** Min tokens per chunk (will merge with neighbor if below). */
    private val minChunkTokens: Int = 50,
) {
    /**
     * Chunk a document using semantic boundaries.
     *
     * @param content Document text.
     * @param filename Source filename for metadata.
     * @param extraMetadata Additional metadata to attach to each chunk.
     * @return Semantically coherent chunks with metadata.
     */
    suspend fun chunk(
        content: String,
        filename: String,
        extraMetadata: Map<String, String> = emptyMap(),
    ): List<DocumentChunker.Chunk> {
        if (content.isBlank()) return emptyList()

        val startTime = System.currentTimeMillis()
        val baseMeta = mapOf("source" to filename, "chunking" to "semantic") + extraMetadata

        // 1. Split into sentences
        val sentences = splitSentences(content)
        if (sentences.size <= 1) {
            return listOf(
                DocumentChunker.Chunk(
                    content = content,
                    index = 0,
                    metadata = baseMeta,
                ),
            )
        }

        // 2. Batch-embed all sentences
        val embeddings = embeddingService.embedBatch(sentences)

        // 3. Compute consecutive similarity and find breakpoints
        val breakpoints = findBreakpoints(embeddings)

        // 4. Group sentences into chunks at breakpoints
        val rawChunks = groupSentences(sentences, breakpoints)

        // 5. Merge small chunks and split oversized chunks
        val finalChunks = mergeAndSplit(rawChunks)

        val result = finalChunks.mapIndexed { index, text ->
            DocumentChunker.Chunk(
                content = text,
                index = index,
                metadata = baseMeta + mapOf(
                    "chunk_part" to "${index + 1}/${finalChunks.size}",
                    "semantic_boundaries" to "true",
                ),
            )
        }

        val elapsed = System.currentTimeMillis() - startTime
        logger.debug {
            "SemanticChunker: '$filename' → ${sentences.size} sentences → ${result.size} chunks in ${elapsed}ms"
        }

        return result
    }

    // ── Internal ──

    /**
     * Split text into sentences using common sentence boundaries.
     */
    private fun splitSentences(text: String): List<String> {
        // Split on sentence-ending punctuation followed by whitespace or newline
        val sentencePattern = Regex("""(?<=[.!?])\s+(?=[A-Z"'])|\n{2,}""")
        return text.split(sentencePattern)
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length > 10 } // Skip very short fragments
    }

    /**
     * Find breakpoint indices where consecutive sentence similarity drops below threshold.
     */
    private fun findBreakpoints(embeddings: List<FloatArray>): Set<Int> {
        val breakpoints = mutableSetOf<Int>()

        for (i in 0 until embeddings.size - 1) {
            val similarity = cosineSimilarity(embeddings[i], embeddings[i + 1])
            if (similarity < similarityThreshold) {
                breakpoints.add(i + 1) // Break BEFORE sentence i+1
            }
        }

        return breakpoints
    }

    /**
     * Group sentences into chunks based on breakpoint positions.
     */
    private fun groupSentences(
        sentences: List<String>,
        breakpoints: Set<Int>,
    ): List<String> {
        val chunks = mutableListOf<String>()
        val currentChunk = StringBuilder()

        for ((i, sentence) in sentences.withIndex()) {
            if (i in breakpoints && currentChunk.isNotBlank()) {
                chunks.add(currentChunk.toString().trim())
                currentChunk.clear()
            }
            if (currentChunk.isNotBlank()) currentChunk.append(" ")
            currentChunk.append(sentence)
        }

        if (currentChunk.isNotBlank()) {
            chunks.add(currentChunk.toString().trim())
        }

        return chunks
    }

    /**
     * Merge chunks smaller than minChunkTokens with their neighbors,
     * and split chunks larger than maxChunkTokens.
     */
    private fun mergeAndSplit(chunks: List<String>): List<String> {
        val maxChars = maxChunkTokens * 4
        val minChars = minChunkTokens * 4

        // First pass: merge small chunks
        val merged = mutableListOf<String>()
        var buffer = ""

        for (chunk in chunks) {
            if (buffer.isBlank()) {
                buffer = chunk
            } else if (buffer.length < minChars) {
                buffer = "$buffer $chunk"
            } else {
                merged.add(buffer)
                buffer = chunk
            }
        }
        if (buffer.isNotBlank()) merged.add(buffer)

        // Second pass: split oversized chunks
        val result = mutableListOf<String>()
        for (chunk in merged) {
            if (chunk.length <= maxChars) {
                result.add(chunk)
            } else {
                // Use the fixed-size splitter as fallback for oversized chunks
                result.addAll(
                    DocumentChunker.splitByTokenLimit(chunk, maxChunkTokens, 50),
                )
            }
        }

        return result.filter { it.isNotBlank() }
    }

    private fun cosineSimilarity(
        a: FloatArray,
        b: FloatArray,
    ): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = Math.sqrt(normA) * Math.sqrt(normB)
        return if (denom > 0) dot / denom else 0.0
    }
}
