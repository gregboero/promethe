package dev.promethe.gateway

import dev.promethe.core.PrometheHome
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
internal data class OpenKnowledgeAuthor(
    @SerialName("@type") val type: String,
    val identifier: String,
    val name: String,
)

@Serializable
internal data class OpenKnowledgeConversation(
    @SerialName("@type") val type: String = "Conversation",
    val identifier: String,
    val name: String,
)

@Serializable
internal data class OpenKnowledgeAttachment(
    @SerialName("@type") val type: String = "MediaObject",
    val identifier: String,
    val name: String,
    val contentUrl: String,
    val encodingFormat: String? = null,
    val contentSize: Long? = null,
)

@Serializable
internal data class DiscordKnowledgeMessage(
    @SerialName("@context") val context: JsonObject = OPEN_KNOWLEDGE_CONTEXT,
    @SerialName("@type") val type: String = "Message",
    val identifier: String,
    val dateCreated: String,
    val text: String,
    val author: OpenKnowledgeAuthor,
    val isPartOf: OpenKnowledgeConversation,
    val inReplyTo: String? = null,
    val associatedMedia: List<OpenKnowledgeAttachment> = emptyList(),
    @SerialName("promethe:role") val role: String,
    @SerialName("promethe:source") val source: String = "discord",
)

/**
 * Append-only, vendor-neutral Discord transcript archive.
 *
 * One JSON-LD file is written per Discord message. The message identifier is
 * hashed for the filename, making writes idempotent while preserving the real
 * identifier inside the document.
 */
internal class DiscordKnowledgeArchive(
    private val root: Path = PrometheHome.knowledgeDir.toPath().resolve("discord"),
) {
    private val mutex = Mutex()

    suspend fun appendBestEffort(message: DiscordKnowledgeMessage): Boolean =
        try {
            append(message)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.warn(error) { "Discord knowledge message could not be archived" }
            false
        }

    suspend fun recentContextBestEffort(
        guildId: String,
        channelId: String,
        excludeIdentifier: String? = null,
    ): String =
        try {
            recentContext(guildId, channelId, excludeIdentifier)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger.warn(error) { "Discord knowledge context could not be read" }
            ""
        }

    suspend fun append(message: DiscordKnowledgeMessage): Boolean =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val conversation = parseDiscordConversationId(message.isPartOf.identifier)
                val directory = channelDirectory(conversation.guildId, conversation.channelId)
                Files.createDirectories(directory)
                val target = directory.resolve("${sha256(message.identifier)}.jsonld")
                try {
                    Files.writeString(
                        target,
                        JSON.encodeToString(DiscordKnowledgeMessage.serializer(), message),
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                    )
                    true
                } catch (_: FileAlreadyExistsException) {
                    false
                }
            }
        }

    suspend fun recentContext(
        guildId: String,
        channelId: String,
        excludeIdentifier: String? = null,
        maxMessages: Int = DEFAULT_CONTEXT_MESSAGES,
        maxCharacters: Int = DEFAULT_CONTEXT_CHARACTERS,
    ): String =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val directory = channelDirectory(guildId, channelId)
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return@withContext ""
                val paths =
                    Files.list(directory).use { stream ->
                        stream.iterator().asSequence().toList()
                    }
                val records =
                    paths
                        .asSequence()
                        .filter { path ->
                            path.fileName.toString().endsWith(".jsonld") &&
                                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        }.sortedByDescending { path ->
                            runCatching { Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis() }
                                .getOrDefault(0L)
                        }.take(MAX_FILES_SCANNED)
                        .mapNotNull { path ->
                            runCatching {
                                JSON.decodeFromString(DiscordKnowledgeMessage.serializer(), Files.readString(path))
                            }.getOrNull()
                        }.filter { it.identifier != excludeIdentifier }
                        .take(maxMessages.coerceIn(1, MAX_CONTEXT_MESSAGES))
                        .sortedBy(DiscordKnowledgeMessage::dateCreated)
                        .toList()
                renderContext(records, maxCharacters.coerceIn(1_000, MAX_CONTEXT_CHARACTERS))
            }
        }

    private fun channelDirectory(
        guildId: String,
        channelId: String,
    ): Path {
        require(DISCORD_ID.matches(guildId) || guildId == "dm") { "Invalid Discord guild id" }
        require(DISCORD_ID.matches(channelId)) { "Invalid Discord channel id" }
        val normalizedRoot = root.toAbsolutePath().normalize()
        val directory = normalizedRoot.resolve(guildId).resolve(channelId).normalize()
        require(directory.startsWith(normalizedRoot)) { "Discord archive path escaped its root" }
        return directory
    }
}

internal fun discordKnowledgeMessage(
    messageId: String,
    guildId: String,
    channelId: String,
    channelName: String,
    authorId: String,
    authorName: String,
    authorIsPromethe: Boolean,
    content: String,
    timestampMillis: Long,
    replyToMessageId: String? = null,
    attachments: List<OpenKnowledgeAttachment> = emptyList(),
): DiscordKnowledgeMessage =
    DiscordKnowledgeMessage(
        identifier = "discord:message:$messageId",
        dateCreated = Instant.ofEpochMilli(timestampMillis).toString(),
        text = content,
        author =
            OpenKnowledgeAuthor(
                type = if (authorIsPromethe) "SoftwareApplication" else "Person",
                identifier = "discord:user:$authorId",
                name = authorName,
            ),
        isPartOf =
            OpenKnowledgeConversation(
                identifier = "discord:conversation:$guildId:$channelId",
                name = channelName,
            ),
        inReplyTo = replyToMessageId?.let { "discord:message:$it" },
        associatedMedia = attachments,
        role = if (authorIsPromethe) "assistant" else "participant",
    )

private data class DiscordConversationId(
    val guildId: String,
    val channelId: String,
)

private fun parseDiscordConversationId(identifier: String): DiscordConversationId {
    val parts = identifier.split(':')
    require(parts.size == 4 && parts[0] == "discord" && parts[1] == "conversation") {
        "Invalid Discord conversation identifier"
    }
    return DiscordConversationId(parts[2], parts[3])
}

private fun renderContext(
    records: List<DiscordKnowledgeMessage>,
    maxCharacters: Int,
): String {
    if (records.isEmpty()) return ""
    val rendered =
        records.map { record ->
            "[${record.dateCreated}] ${record.author.name} (${record.role}): ${record.text.trim()}"
        }
    val selected = ArrayDeque<String>()
    var size = 0
    rendered.asReversed().forEach { line ->
        if (size + line.length + 1 <= maxCharacters) {
            selected.addFirst(line)
            size += line.length + 1
        }
    }
    return selected.joinToString("\n")
}

private fun sha256(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

private val OPEN_KNOWLEDGE_CONTEXT =
    buildJsonObject {
        put("@vocab", "https://schema.org/")
        put("promethe", "https://promethe.dev/ns/knowledge#")
    }
private val JSON =
    Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }
private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}
private val DISCORD_ID = Regex("[0-9]{1,20}")
private const val DEFAULT_CONTEXT_MESSAGES = 50
private const val MAX_CONTEXT_MESSAGES = 100
private const val DEFAULT_CONTEXT_CHARACTERS = 16_000
private const val MAX_CONTEXT_CHARACTERS = 32_000
private const val MAX_FILES_SCANNED = 500
