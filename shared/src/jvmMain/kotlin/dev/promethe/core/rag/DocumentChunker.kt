package dev.promethe.core.rag

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * DocumentChunker — splits documents into overlapping chunks for embedding.
 *
 * Supports:
 * - Markdown: splits on headings (##, ###) preserving hierarchy
 * - Code: splits on function/class boundaries
 * - Plain text: splits on paragraph boundaries then by token count
 *
 * Each chunk carries metadata (source file, heading, line range).
 */
class DocumentChunker(
    private val maxChunkSize: Int = 512,
    private val overlapSize: Int = 50,
) {
    data class Chunk(
        val content: String,
        val index: Int,
        val metadata: Map<String, String>,
    ) {
        /** Approximate token count (rough: 1 token ≈ 4 chars). */
        val estimatedTokens: Int get() = content.length / 4
    }

    /**
     * Chunk a document based on its file extension.
     */
    fun chunk(
        content: String,
        filename: String,
        extraMetadata: Map<String, String> = emptyMap(),
    ): List<Chunk> {
        if (content.isBlank()) return emptyList()

        val extension = filename.substringAfterLast('.', "").lowercase()
        val baseMeta = mapOf("source" to filename) + extraMetadata

        val chunks = when (extension) {
            "md", "markdown" -> {
                chunkMarkdown(content, baseMeta)
            }

            "kt", "java", "py", "js", "ts", "go", "rs", "c", "cpp", "cs" -> {
                chunkCode(content, baseMeta)
            }

            else -> {
                chunkPlainText(content, baseMeta)
            }
        }

        logger.debug { "Chunked '$filename': ${chunks.size} chunks (max=$maxChunkSize, overlap=$overlapSize)" }
        return chunks
    }

    /**
     * Markdown chunker — splits on headings, preserving heading hierarchy as metadata.
     */
    private fun chunkMarkdown(
        content: String,
        baseMeta: Map<String, String>,
    ): List<Chunk> {
        val sections = mutableListOf<Pair<String, String>>() // (heading, content)
        var currentHeading = ""
        val currentContent = StringBuilder()

        for (line in content.lines()) {
            if (line.startsWith("#")) {
                if (currentContent.isNotBlank()) {
                    sections.add(currentHeading to currentContent.toString().trim())
                }
                currentHeading = line.trimStart('#').trim()
                currentContent.clear()
            } else {
                currentContent.appendLine(line)
            }
        }
        if (currentContent.isNotBlank()) {
            sections.add(currentHeading to currentContent.toString().trim())
        }

        // Now split each section if it's too large
        val result = mutableListOf<Chunk>()
        for ((heading, text) in sections) {
            val meta = baseMeta + ("heading" to heading)
            val subChunks = splitByTokenLimit(text, maxChunkSize, overlapSize)
            for ((i, subChunk) in subChunks.withIndex()) {
                result.add(
                    Chunk(
                        content = if (heading.isNotBlank()) "## $heading\n\n$subChunk" else subChunk,
                        index = result.size,
                        metadata = meta + ("chunk_part" to "${i + 1}/${subChunks.size}"),
                    ),
                )
            }
        }

        return result.ifEmpty {
            // Fallback: treat as plain text
            chunkPlainText(content, baseMeta)
        }
    }

    /**
     * Code chunker — splits on function/class boundaries.
     */
    private fun chunkCode(
        content: String,
        baseMeta: Map<String, String>,
    ): List<Chunk> {
        val lines = content.lines()
        val blocks = mutableListOf<Triple<String, Int, Int>>() // (blockName, startLine, endLine)

        var blockStart = 0
        var blockName = "top-level"
        var braceDepth = 0

        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trim()

            // Detect function/class boundaries
            val isBoundary = trimmed.startsWith("fun ") ||
                trimmed.startsWith("class ") ||
                trimmed.startsWith("object ") ||
                trimmed.startsWith("interface ") ||
                trimmed.startsWith("def ") ||
                trimmed.startsWith("function ") ||
                trimmed.startsWith("async function ") ||
                trimmed.startsWith("public ") ||
                trimmed.startsWith("private ") ||
                trimmed.startsWith("protected ")

            if (isBoundary && braceDepth <= 1) {
                if (i > blockStart) {
                    blocks.add(Triple(blockName, blockStart, i - 1))
                }
                blockName = trimmed.take(60)
                blockStart = i
            }

            braceDepth += trimmed.count { it == '{' } - trimmed.count { it == '}' }
        }
        // Last block
        if (blockStart < lines.size) {
            blocks.add(Triple(blockName, blockStart, lines.size - 1))
        }

        val result = mutableListOf<Chunk>()
        for ((name, start, end) in blocks) {
            val blockContent = lines.subList(start, (end + 1).coerceAtMost(lines.size)).joinToString("\n")
            if (blockContent.isBlank()) continue

            val meta = baseMeta + mapOf(
                "block" to name,
                "lines" to "${start + 1}-${end + 1}",
            )

            val subChunks = splitByTokenLimit(blockContent, maxChunkSize, overlapSize)
            for ((i, subChunk) in subChunks.withIndex()) {
                result.add(
                    Chunk(
                        content = subChunk,
                        index = result.size,
                        metadata = meta + ("chunk_part" to "${i + 1}/${subChunks.size}"),
                    ),
                )
            }
        }

        return result.ifEmpty { chunkPlainText(content, baseMeta) }
    }

    /**
     * Plain text chunker — splits on paragraphs then by token count.
     */
    private fun chunkPlainText(
        content: String,
        baseMeta: Map<String, String>,
    ): List<Chunk> {
        val subChunks = splitByTokenLimit(content, maxChunkSize, overlapSize)
        return subChunks.mapIndexed { i, text ->
            Chunk(
                content = text,
                index = i,
                metadata = baseMeta + ("chunk_part" to "${i + 1}/${subChunks.size}"),
            )
        }
    }

    companion object {
        /**
         * Split text into chunks respecting a token limit, with overlap.
         * Tries to split on paragraph boundaries first, then on sentence boundaries.
         */
        fun splitByTokenLimit(
            text: String,
            maxTokens: Int,
            overlapTokens: Int,
        ): List<String> {
            val maxChars = maxTokens * 4 // rough estimate
            val overlapChars = overlapTokens * 4

            if (text.length <= maxChars) return listOf(text)

            val chunks = mutableListOf<String>()
            var start = 0

            while (start < text.length) {
                var end = (start + maxChars).coerceAtMost(text.length)

                // Try to break at paragraph boundary
                if (end < text.length) {
                    val paragraphBreak = text.lastIndexOf("\n\n", end)
                    if (paragraphBreak > start + maxChars / 2) {
                        end = paragraphBreak
                    } else {
                        // Try sentence boundary
                        val sentenceBreak = text.lastIndexOf(". ", end)
                        if (sentenceBreak > start + maxChars / 2) {
                            end = sentenceBreak + 1
                        }
                    }
                }

                chunks.add(text.substring(start, end).trim())
                start = (end - overlapChars).coerceAtLeast(end) // overlap for context
                if (overlapChars > 0 && end < text.length) {
                    start = (end - overlapChars).coerceAtLeast(start)
                }
                // Prevent infinite loop
                if (start <= chunks.lastIndex && start < end) start = end
            }

            return chunks.filter { it.isNotBlank() }
        }
    }
}
