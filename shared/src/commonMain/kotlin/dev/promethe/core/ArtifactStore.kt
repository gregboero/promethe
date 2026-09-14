package dev.promethe.core

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

data class ArtifactWriteRequest(
    val content: ByteArray,
    val mediaType: String,
    val kind: String,
    val runId: String? = null,
    val stepId: String? = null,
    val intentId: String? = null,
    val toolName: String? = null,
)

data class ArtifactReference(
    val hash: String,
    val uri: String,
    val sizeBytes: Long,
    val mediaType: String,
)

interface ArtifactStore {
    suspend fun put(request: ArtifactWriteRequest): ArtifactReference

    suspend fun read(hash: String): ByteArray?
}

data class ExternalizedObservation(
    val text: String,
    val artifact: ArtifactReference? = null,
)

class ArtifactObservationExternalizer(
    private val store: ArtifactStore,
    private val maxInlineBytes: Int = DEFAULT_MAX_INLINE_BYTES,
) {
    init {
        require(maxInlineBytes > 0) { "Inline artifact threshold must be positive" }
    }

    suspend fun externalize(
        content: String,
        runId: String?,
        stepId: String?,
        intentId: String?,
        toolName: String,
    ): ExternalizedObservation {
        val bytes = content.encodeToByteArray()
        if (bytes.size <= maxInlineBytes) return ExternalizedObservation(content)

        val artifact =
            store.put(
                ArtifactWriteRequest(
                    content = bytes,
                    mediaType = TEXT_MEDIA_TYPE,
                    kind = TOOL_OUTPUT_KIND,
                    runId = runId,
                    stepId = stepId,
                    intentId = intentId,
                    toolName = toolName,
                ),
            )
        return ExternalizedObservation(
            text =
                buildString {
                    append(content.take(HEAD_CHARS))
                    append("\n\n[Tool output externalized")
                    append("\nsha256: ")
                    append(artifact.hash)
                    append("\nuri: ")
                    append(artifact.uri)
                    append("\nbytes: ")
                    append(artifact.sizeBytes)
                    append("\nUse artifact_read with this hash to retrieve another segment.]")
                    append("\n\n")
                    append(content.takeLast(TAIL_CHARS))
                },
            artifact = artifact,
        )
    }

    companion object {
        const val DEFAULT_MAX_INLINE_BYTES = 8 * 1024
        private const val HEAD_CHARS = 800
        private const val TAIL_CHARS = 400
        private const val TEXT_MEDIA_TYPE = "text/plain; charset=utf-8"
        private const val TOOL_OUTPUT_KIND = "tool-output"
    }
}

@Serializable
data class ArtifactReadArgs(
    @property:LLMDescription("SHA-256 hash from an artifact observation.")
    val hash: String,
    @property:LLMDescription("Byte offset at which the returned segment starts.")
    val offset: Int = 0,
    @property:LLMDescription("Maximum bytes to return, from 1 to 4096.")
    val maxBytes: Int = DEFAULT_ARTIFACT_READ_BYTES,
)

class ArtifactReadTool(
    private val store: ArtifactStore,
) : SimpleTool<ArtifactReadArgs>(
        argsType = typeToken<ArtifactReadArgs>(),
        name = "artifact_read",
        description = "Read a bounded text segment from a content-addressed tool-output artifact by SHA-256 hash.",
    ) {
    override suspend fun execute(args: ArtifactReadArgs): String {
        require(ARTIFACT_HASH.matches(args.hash)) { "Artifact hash must be 64 lowercase hexadecimal characters" }
        require(args.offset >= 0) { "Artifact offset must not be negative" }
        require(args.maxBytes in 1..MAX_ARTIFACT_READ_BYTES) { "Artifact maxBytes must be between 1 and $MAX_ARTIFACT_READ_BYTES" }

        val content = store.read(args.hash) ?: return "[ERROR] Artifact not found"
        if (args.offset >= content.size) {
            return "[Artifact segment ${args.hash} bytes=${content.size} offset=${args.offset}: end of artifact]"
        }
        val end = (args.offset + args.maxBytes).coerceAtMost(content.size)
        val text = content.decodeToString(args.offset, end, throwOnInvalidSequence = false)
        return buildString {
            append("[Artifact segment ")
            append(args.hash)
            append(" bytes=")
            append(args.offset)
            append("..")
            append(end)
            append('/')
            append(content.size)
            append("]\n")
            append(text)
        }
    }
}

internal val ARTIFACT_HASH = Regex("^[0-9a-f]{64}$")
private const val DEFAULT_ARTIFACT_READ_BYTES = 4_096
private const val MAX_ARTIFACT_READ_BYTES = 4_096
