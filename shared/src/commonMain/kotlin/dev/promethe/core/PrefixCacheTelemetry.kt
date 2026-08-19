package dev.promethe.core

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.executor.clients.anthropic.AnthropicCacheControl
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.ByteString.Companion.encodeUtf8

internal data class StablePromptPrefix(
    val fingerprint: String,
    val bytes: Int,
)

internal data class PrefixObservation(
    val reused: Boolean,
    val prefix: StablePromptPrefix,
)

internal data class ProviderCacheUsage(
    val readTokens: Long? = null,
    val writeTokens: Long? = null,
) {
    val observable: Boolean
        get() = readTokens != null || writeTokens != null
}

internal data class PrefixCacheSnapshot(
    val providerHits: Long,
    val providerMisses: Long,
    val providerReadTokens: Long,
    val providerWriteTokens: Long,
    val observableResponses: Long,
    val candidateHits: Long,
    val candidateMisses: Long,
    val knownPrefixes: Int,
)

internal class PrefixCacheTelemetry(
    private val maxPrefixes: Int = 32,
) {
    private val mutex = Mutex()
    private val prefixes = LinkedHashMap<String, Unit>(16, 0.75f, true)
    private var providerHits = 0L
    private var providerMisses = 0L
    private var providerReadTokens = 0L
    private var providerWriteTokens = 0L
    private var observableResponses = 0L
    private var candidateHits = 0L
    private var candidateMisses = 0L

    init {
        require(maxPrefixes > 0) { "maxPrefixes must be positive" }
    }

    suspend fun observe(prefix: StablePromptPrefix): PrefixObservation =
        mutex.withLock {
            val reused = prefixes.containsKey(prefix.fingerprint)
            prefixes[prefix.fingerprint] = Unit
            if (reused) {
                candidateHits++
            } else {
                candidateMisses++
            }
            while (prefixes.size > maxPrefixes) {
                prefixes.remove(prefixes.keys.first())
            }
            PrefixObservation(reused, prefix)
        }

    suspend fun recordProviderUsage(usage: ProviderCacheUsage) {
        if (!usage.observable) return
        mutex.withLock {
            observableResponses++
            val read = usage.readTokens ?: 0L
            if (read > 0L) {
                providerHits++
            } else {
                providerMisses++
            }
            providerReadTokens += read
            providerWriteTokens += usage.writeTokens ?: 0L
        }
    }

    suspend fun snapshot(): PrefixCacheSnapshot =
        mutex.withLock {
            PrefixCacheSnapshot(
                providerHits = providerHits,
                providerMisses = providerMisses,
                providerReadTokens = providerReadTokens,
                providerWriteTokens = providerWriteTokens,
                observableResponses = observableResponses,
                candidateHits = candidateHits,
                candidateMisses = candidateMisses,
                knownPrefixes = prefixes.size,
            )
        }
}

internal fun stablePromptPrefix(
    provider: String,
    model: String,
    systemMessages: List<String>,
    tools: List<ToolDescriptor>,
): StablePromptPrefix {
    val material =
        buildString {
            appendSegment("provider", provider.lowercase())
            appendSegment("model", model)
            systemMessages.forEach { appendSegment("system", it) }
            tools.sortedWith(compareBy<ToolDescriptor> { it.name }.thenBy { it.description }).forEach {
                appendSegment("tool", it.toString())
            }
        }
    return StablePromptPrefix(
        fingerprint = material.encodeUtf8().sha256().hex(),
        bytes = material.encodeToByteArray().size,
    )
}

internal fun preparePromptTools(
    provider: String,
    tools: List<ToolDescriptor>,
): List<ToolDescriptor> {
    val sorted = tools.sortedWith(compareBy<ToolDescriptor> { it.name }.thenBy { it.description })
    if (provider.lowercase() != "anthropic" || sorted.isEmpty()) return sorted
    return sorted.dropLast(1) + sorted.last().withCacheControl(AnthropicCacheControl.Default)
}

internal fun providerCacheUsage(
    metaInfo: ResponseMetaInfo,
    rawResponse: JsonObject?,
): ProviderCacheUsage {
    val metadata = metaInfo.metadata
    val metadataRead =
        metadata.longAt("cacheReadInputTokens")
            ?: metadata.longAt("cache_read_input_tokens")
            ?: metadata.longAt("cachedTokens")
    val metadataWrite =
        metadata.longAt("cacheCreationInputTokens")
            ?: metadata.longAt("cache_creation_input_tokens")
            ?: metadata.longAt("cacheWriteInputTokens")

    val usage = rawResponse.objectAt("usage")
    val rawRead =
        usage.objectAt("prompt_tokens_details").longAt("cached_tokens")
            ?: usage.objectAt("input_tokens_details").longAt("cached_tokens")
            ?: usage.longAt("cache_read_input_tokens")
    val rawWrite = usage.longAt("cache_creation_input_tokens") ?: usage.longAt("cache_write_input_tokens")

    return ProviderCacheUsage(
        readTokens = metadataRead ?: rawRead,
        writeTokens = metadataWrite ?: rawWrite,
    )
}

private fun StringBuilder.appendSegment(
    label: String,
    value: String,
) {
    append(label)
    append(':')
    append(value.encodeToByteArray().size)
    append(':')
    append(value)
    append('\n')
}

private fun JsonObject?.longAt(key: String): Long? = (this?.get(key) as? JsonPrimitive)?.longOrNull

private fun JsonObject?.objectAt(key: String): JsonObject? = this?.get(key).asObjectOrNull()

private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject
