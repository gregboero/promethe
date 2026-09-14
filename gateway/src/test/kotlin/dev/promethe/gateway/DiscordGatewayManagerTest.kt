package dev.promethe.gateway

import dev.promethe.api.DiscordInteraction
import dev.promethe.api.DiscordInteractionMember
import dev.promethe.api.DiscordInteractionUser
import dev.promethe.core.ApprovalGate
import dev.promethe.core.ToolApprovalGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.dv8tion.jda.api.requests.GatewayIntent

class DiscordGatewayManagerTest {
    @Test
    fun `message content intent is opt-in`() {
        assertEquals(
            setOf(GatewayIntent.GUILD_MESSAGES, GatewayIntent.DIRECT_MESSAGES),
            discordGatewayIntents(messageContentEnabled = false),
        )
        assertTrue(GatewayIntent.MESSAGE_CONTENT in discordGatewayIntents(messageContentEnabled = true))
    }

    @Test
    fun `knowledge channels require message content intent`() {
        assertFalse(discordRequiresMessageContent(addressedMessagesEnabled = false, knowledgeChannelIds = emptySet()))
        assertTrue(discordRequiresMessageContent(addressedMessagesEnabled = false, knowledgeChannelIds = setOf("123")))
    }

    @Test
    fun `empty Discord user allow-list preserves unrestricted access`() {
        val allowlist = discordIdAllowlist("  ")

        assertFalse(allowlist.configured)
        assertTrue(allowlist.allows("123"))
    }

    @Test
    fun `configured Discord user allow-list rejects users not listed`() {
        val allowlist = discordIdAllowlist("123, 456\n789")

        assertTrue(allowlist.configured)
        assertEquals(setOf("123", "456", "789"), allowlist.ids)
        assertTrue(allowlist.allows("456"))
        assertFalse(allowlist.allows("999"))
    }

    @Test
    fun `malformed configured Discord user allow-list fails closed`() {
        val allowlist = discordIdAllowlist("not-a-discord-id")

        assertTrue(allowlist.configured)
        assertTrue(allowlist.ids.isEmpty())
        assertFalse(allowlist.allows("123"))
    }

    @Test
    fun `Discord interaction user is resolved for guilds and direct messages`() {
        assertEquals(
            "123",
            DiscordInteraction(member = DiscordInteractionMember(user = DiscordInteractionUser(id = "123")))
                .requestingUserId(),
        )
        assertEquals("456", DiscordInteraction(user = DiscordInteractionUser(id = "456")).requestingUserId())
    }

    @Test
    fun `guild messages without a mention never read message content`() {
        var contentRead = false
        assertNull(
            discordInboundMessage(
                content = {
                    contentRead = true
                    "hello"
                },
                botUserId = "42",
                isGuildMessage = true,
                isBotMentioned = false,
                guildId = "guild",
                channelId = "channel",
            ),
        )
        assertFalse(contentRead)
    }

    @Test
    fun `guild mentions are stripped before A2A execution`() {
        val inbound =
            discordInboundMessage(
                content = { "<@!42> what is the weather?" },
                botUserId = "42",
                isGuildMessage = true,
                isBotMentioned = true,
                guildId = "guild",
                channelId = "channel",
            )

        assertEquals("discord-guild-channel", inbound?.sessionId)
        assertEquals("what is the weather?", inbound?.text)
    }

    @Test
    fun `direct messages do not require a mention`() {
        val inbound =
            discordInboundMessage(
                content = { "hello from a DM" },
                botUserId = "42",
                isGuildMessage = false,
                isBotMentioned = false,
                guildId = null,
                channelId = "dm-channel",
            )

        assertEquals("discord-dm-dm-channel", inbound?.sessionId)
        assertEquals("hello from a DM", inbound?.text)
    }

    @Test
    fun `message content mode accepts a message addressed by bot name`() {
        val inbound =
            discordInboundMessage(
                content = { "Hey Promethe, what is the weather?" },
                botUserId = "42",
                isGuildMessage = true,
                isBotMentioned = false,
                guildId = "guild",
                channelId = "channel",
                listenForAddressedMessages = true,
                botNames = setOf("Promethe"),
            )

        assertEquals("discord-guild-channel", inbound?.sessionId)
        assertEquals("what is the weather?", inbound?.text)
    }

    @Test
    fun `bot name matching tolerates accents and small spelling errors`() {
        assertEquals("aide-moi", stripDiscordBotAddress("Promete, aide-moi", setOf("Promethe")))
        assertEquals(
            "aide-moi",
            stripDiscordBotAddress("Prom\u00e9th\u00e9e, aide-moi", setOf("Promethe")),
        )
        assertEquals("aide-moi", stripDiscordBotAddress("Promethee, aide-moi", setOf("Promethe")))
        assertEquals("aide-moi", stripDiscordBotAddress("Promthe, aide-moi", setOf("Promethe")))
    }

    @Test
    fun `permissive bot name does not match unrelated words`() {
        assertNull(stripDiscordBotAddress("Promenade demain", setOf("Promethe")))
        assertNull(stripDiscordBotAddress("weather tomorrow", setOf("Promethe")))
    }

    @Test
    fun `message content mode ignores ordinary guild conversation`() {
        val inbound =
            discordInboundMessage(
                content = { "what is the weather?" },
                botUserId = "42",
                isGuildMessage = true,
                isBotMentioned = false,
                guildId = "guild",
                channelId = "channel",
                listenForAddressedMessages = true,
                botNames = setOf("Promethe"),
            )

        assertNull(inbound)
    }

    @Test
    fun `reply to bot is accepted without a mention`() {
        val inbound =
            discordInboundMessage(
                content = { "can you explain that?" },
                botUserId = "42",
                isGuildMessage = true,
                isBotMentioned = false,
                guildId = "guild",
                channelId = "channel",
                listenForAddressedMessages = true,
                isReplyToBot = true,
                botNames = setOf("Promethe"),
            )

        assertEquals("can you explain that?", inbound?.text)
    }

    @Test
    fun `long agent responses respect Discord message limits`() {
        val response = "a".repeat(4_501)
        val chunks = splitDiscordMessage(response)

        assertEquals(response, chunks.joinToString(separator = ""))
        assertTrue(chunks.all { it.length <= 2_000 })
    }

    @Test
    fun `approval component identifiers round trip without carrying arguments`() {
        val customId =
            discordApprovalActionId(
                requestId = "approval-random-id",
                approved = true,
                scope = ApprovalGate.ApprovalScope.PERSISTENT,
            )

        assertEquals(
            DiscordApprovalAction("approval-random-id", true, ApprovalGate.ApprovalScope.PERSISTENT),
            parseDiscordApprovalAction(customId),
        )
        assertNull(parseDiscordApprovalAction("promethe:approval:allow:UNKNOWN:request"))
        assertFalse(customId.contains("targetId"))
    }

    @Test
    fun `permanent Discord approval button is limited to configuration changes`() {
        val ordinary = approvalRequest(persistentAllowed = false)
        val configuration = approvalRequest(persistentAllowed = true)

        assertEquals(3, discordApprovalButtons(ordinary).size)
        assertEquals(4, discordApprovalButtons(configuration).size)
        assertTrue(discordApprovalMessage(configuration).length < 2_000)
    }

    private fun approvalRequest(persistentAllowed: Boolean) =
        ToolApprovalGate.ApprovalRequest(
            id = "approval-random-id",
            toolName = "discord_policy",
            args = """{"action":"listen_channel","targetId":"123"}""",
            argsDigest = "digest",
            fingerprint = "fingerprint",
            sessionId = "discord-1-2",
            persistentAllowed = persistentAllowed,
        )
}
