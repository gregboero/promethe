package dev.promethe.gateway

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DiscordKnowledgeArchiveTest {
    @Test
    fun `archive writes idempotent JSON-LD records`() =
        runTest {
            val root = createTempDirectory("promethe-discord-knowledge")
            try {
                val archive = DiscordKnowledgeArchive(root)
                val message = knowledgeMessage(messageId = "100", channelId = "20", content = "Bonjour")

                assertTrue(archive.append(message))
                assertFalse(archive.append(message))

                val files = Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList() }
                assertEquals(1, files.size)
                assertTrue(files.single().fileName.toString().endsWith(".jsonld"))
                val payload = Files.readString(files.single())
                assertTrue(payload.contains("https://schema.org/"))
                assertTrue(payload.contains("discord:message:100"))
                assertTrue(payload.contains("Bonjour"))
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun `recent context stays inside its channel and can exclude current message`() =
        runTest {
            val root = createTempDirectory("promethe-discord-context")
            try {
                val archive = DiscordKnowledgeArchive(root)
                archive.append(knowledgeMessage("100", "20", "first message", timestamp = 1_000))
                archive.append(knowledgeMessage("101", "20", "current message", timestamp = 2_000))
                archive.append(knowledgeMessage("102", "21", "another channel", timestamp = 3_000))

                val context = archive.recentContext("10", "20", excludeIdentifier = "discord:message:101")

                assertTrue(context.contains("first message"))
                assertFalse(context.contains("current message"))
                assertFalse(context.contains("another channel"))
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    private fun knowledgeMessage(
        messageId: String,
        channelId: String,
        content: String,
        timestamp: Long = 1_000,
    ): DiscordKnowledgeMessage =
        discordKnowledgeMessage(
            messageId = messageId,
            guildId = "10",
            channelId = channelId,
            channelName = "knowledge",
            authorId = "30",
            authorName = "Alice",
            authorIsPromethe = false,
            content = content,
            timestampMillis = timestamp,
        )
}
