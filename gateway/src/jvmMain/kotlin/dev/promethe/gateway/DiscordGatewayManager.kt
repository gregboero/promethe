package dev.promethe.gateway

import dev.promethe.core.CredentialsStore
import dev.promethe.core.ApprovalGate
import dev.promethe.core.ToolApprovalGate
import dev.promethe.core.config.ConfigProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import java.text.Normalizer
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.requests.GatewayIntent

private val logger = KotlinLogging.logger {}

internal fun interface DiscordAgentExecutor {
    suspend fun execute(
        sessionId: String,
        text: String,
        externalContext: String?,
        projectId: String?,
    ): String
}

internal data class DiscordInboundMessage(
    val sessionId: String,
    val text: String,
)

internal data class DiscordGatewayConfiguration(
    val token: String,
    val messageContentEnabled: Boolean,
    val allowedUsers: DiscordIdAllowlist,
    val approvalUserIds: Set<String>,
    val knowledgeChannelIds: Set<String>,
    val requiresMessageContent: Boolean,
)

internal data class DiscordIdAllowlist(
    val configured: Boolean,
    val ids: Set<String>,
) {
    fun allows(id: String): Boolean = !configured || id in ids
}

/**
 * Owns the Discord Gateway connection used for direct messages and explicit bot mentions.
 * JDA handles heartbeats, reconnect/resume, and Discord REST rate limits.
 */
internal class DiscordGatewayManager(
    private val executor: DiscordAgentExecutor,
    private val tokenProvider: () -> String = {
        CredentialsStore.load()?.toEnvMap()?.get("DISCORD_BOT_TOKEN").orEmpty()
    },
    private val messageContentEnabledProvider: () -> Boolean = {
        ConfigProvider.get().getBoolean("DISCORD_MESSAGE_CONTENT_ENABLED", false)
    },
    private val allowedUserIdsProvider: () -> String = {
        ConfigProvider.get().get("DISCORD_ALLOWED_USER_IDS", "")
    },
    private val knowledgeChannelIdsProvider: () -> String = {
        ConfigProvider.get().get("DISCORD_KNOWLEDGE_CHANNEL_IDS", "")
    },
    private val approvalUserIdsProvider: () -> String = {
        ConfigProvider.get().get("DISCORD_APPROVER_USER_IDS", "")
    },
    private val policyService: DiscordPolicyService,
    private val knowledgeArchive: DiscordKnowledgeArchive = DiscordKnowledgeArchive(),
    private val approvalGate: ToolApprovalGate? = null,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("discord-gateway"))
    private val reloadMutex = Mutex()
    private val channelMutexes = ConcurrentHashMap<String, Mutex>()

    @Volatile
    private var activeConfiguration: DiscordGatewayConfiguration? = null

    @Volatile
    private var jda: JDA? = null

    private val approvalSubscription =
        approvalGate?.addRequestListener { request ->
            if (request.sessionId.startsWith("discord-")) {
                scope.launch { sendApprovalRequest(request) }
            }
        }

    fun start() {
        scope.launch { reload() }
    }

    suspend fun reload() {
        reloadMutex.withLock {
            val token = tokenProvider().trim()
            val messageContentEnabled = messageContentEnabledProvider()
            val staticKnowledgeChannelIds = discordIdSet(knowledgeChannelIdsProvider())
            val effectiveKnowledgeChannelIds = policyService.effectiveKnowledgeChannelIds(staticKnowledgeChannelIds)
            val configuration =
                DiscordGatewayConfiguration(
                    token = token,
                    messageContentEnabled = messageContentEnabled,
                    allowedUsers = discordIdAllowlist(allowedUserIdsProvider()),
                    approvalUserIds = discordIdSet(approvalUserIdsProvider()),
                    knowledgeChannelIds = staticKnowledgeChannelIds,
                    requiresMessageContent =
                        discordRequiresMessageContent(
                            messageContentEnabled,
                            effectiveKnowledgeChannelIds,
                        ),
                )
            if (configuration == activeConfiguration && jda != null) return

            jda?.shutdownNow()
            jda = null
            activeConfiguration = null

            if (token.isBlank()) {
                logger.info { "Discord Gateway disabled: DISCORD_BOT_TOKEN is not configured" }
                return
            }

            try {
                val listener =
                    DiscordListener(
                        scope = scope,
                        executor = executor,
                        channelMutexes = channelMutexes,
                        listenForAddressedMessages = configuration.messageContentEnabled,
                        allowedUsers = configuration.allowedUsers,
                        staticKnowledgeChannelIds = configuration.knowledgeChannelIds,
                        policyService = policyService,
                        knowledgeArchive = knowledgeArchive,
                        approvalGate = approvalGate,
                        approvalUserIdsProvider = { discordIdSet(approvalUserIdsProvider()) },
                    )
                jda =
                    JDABuilder
                        .createLight(
                            token,
                            discordGatewayIntents(configuration.requiresMessageContent),
                        ).setActivity(Activity.listening("mentions"))
                        .setAutoReconnect(true)
                        .addEventListeners(listener)
                        .build()
                activeConfiguration = configuration
                logger.info {
                    "Discord Gateway connection starting " +
                        "[messageContent=${configuration.requiresMessageContent}, " +
                        "restrictedUsers=${configuration.allowedUsers.configured}, " +
                        "approvers=${configuration.approvalUserIds.size}, " +
                        "knowledgeChannels=${effectiveKnowledgeChannelIds.size}]"
                }
            } catch (error: Exception) {
                logger.error(error) { "Discord Gateway could not start; verify the bot token" }
                throw IllegalStateException("Discord Gateway could not start; verify the bot token.", error)
            }
        }
    }

    override fun close() {
        approvalSubscription?.close()
        scope.cancel()
        jda?.shutdownNow()
        jda = null
        activeConfiguration = null
        channelMutexes.clear()
    }

    private fun sendApprovalRequest(request: ToolApprovalGate.ApprovalRequest) {
        val gate = approvalGate ?: return
        if (ToolApprovalGate.requiresLocalOwner(request.toolName)) {
            gate.respond(request.id, approved = false)
            logger.warn { "Discord request for local-owner-only tool '${request.toolName}' was denied" }
            return
        }

        val approverIds = discordIdSet(approvalUserIdsProvider())
        val activeJda = jda
        if (activeJda == null || approverIds.isEmpty()) {
            logger.warn {
                "Discord approval ${request.id} cannot be delivered: " +
                    if (activeJda == null) "gateway is unavailable" else "DISCORD_APPROVER_USER_IDS is empty"
            }
            return
        }

        val buttons = discordApprovalButtons(request)
        val message = discordApprovalMessage(request)
        approverIds.forEach { approverId ->
            activeJda
                .retrieveUserById(approverId)
                .flatMap { user -> user.openPrivateChannel() }
                .flatMap { channel -> channel.sendMessage(message).addComponents(ActionRow.of(buttons)) }
                .queue(
                    { logger.info { "Discord approval ${request.id} sent to integration approver $approverId" } },
                    { error -> logger.warn(error) { "Discord approval DM failed for approver $approverId" } },
                )
        }
    }
}

internal fun discordGatewayIntents(messageContentEnabled: Boolean): Set<GatewayIntent> =
    EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.DIRECT_MESSAGES).apply {
        if (messageContentEnabled) add(GatewayIntent.MESSAGE_CONTENT)
    }

internal fun discordRequiresMessageContent(
    addressedMessagesEnabled: Boolean,
    knowledgeChannelIds: Set<String>,
): Boolean = addressedMessagesEnabled || knowledgeChannelIds.isNotEmpty()

internal fun discordIdAllowlist(raw: String): DiscordIdAllowlist =
    DiscordIdAllowlist(
        configured = raw.isNotBlank(),
        ids = discordIdSet(raw),
    )

internal fun discordIdSet(raw: String): Set<String> =
    raw
        .split(',', ';', '\n', '\r', ' ', '\t')
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .filter(DISCORD_SNOWFLAKE::matches)
        .toSet()

private class DiscordListener(
    private val scope: CoroutineScope,
    private val executor: DiscordAgentExecutor,
    private val channelMutexes: ConcurrentHashMap<String, Mutex>,
    private val listenForAddressedMessages: Boolean,
    private val allowedUsers: DiscordIdAllowlist,
    private val staticKnowledgeChannelIds: Set<String>,
    private val policyService: DiscordPolicyService,
    private val knowledgeArchive: DiscordKnowledgeArchive,
    private val approvalGate: ToolApprovalGate?,
    private val approvalUserIdsProvider: () -> Set<String>,
) : ListenerAdapter() {
    override fun onReady(event: ReadyEvent) {
        logger.info { "Discord Gateway ready as ${event.jda.selfUser.name} (${event.jda.guilds.size} guilds)" }
    }

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot || event.message.isWebhookMessage) return

        val selfUser = event.jda.selfUser
        val isBotMentioned = event.message.mentions.users.any { it.id == selfUser.id }
        val isReplyToBot = event.message.referencedMessage?.author?.id == selfUser.id
        val botNames =
            buildSet {
                add(selfUser.name)
                if (event.isFromGuild) add(event.guild.selfMember.effectiveName)
            }
        val guildId = if (event.isFromGuild) event.guild.id else "dm"
        val channelId = event.channel.id
        val knowledgeEnabled = policyService.capturesKnowledge(staticKnowledgeChannelIds, guildId, channelId)
        val projectId = policyService.projectIdForChannel(guildId, channelId)
        var cachedContent: String? = null
        val content = {
            cachedContent ?: event.message.contentRaw.also { cachedContent = it }
        }
        val inbound =
            if (
                policyService.allowsUser(
                    staticAllowlist = allowedUsers,
                    userId = event.author.id,
                    guildId = guildId,
                    channelId = channelId,
                    content = content,
                )
            ) {
                discordInboundMessage(
                    content = content,
                    botUserId = selfUser.id,
                    isGuildMessage = event.isFromGuild,
                    isBotMentioned = isBotMentioned,
                    guildId = guildId,
                    channelId = channelId,
                    listenForAddressedMessages = listenForAddressedMessages,
                    isReplyToBot = isReplyToBot,
                    botNames = botNames,
                )
            } else {
                null
            }
        if (inbound == null && !knowledgeEnabled) return

        val messageId = event.message.id
        val authorName = event.member?.effectiveName ?: event.author.effectiveName
        val channelName = event.channel.name
        val timestamp = event.message.timeCreated.toInstant().toEpochMilli()
        val replyTo = event.message.referencedMessage?.id
        val attachments =
            event.message.attachments.map { attachment ->
                OpenKnowledgeAttachment(
                    identifier = "discord:attachment:${attachment.id}",
                    name = attachment.fileName,
                    contentUrl = attachment.url,
                    encodingFormat = attachment.contentType,
                    contentSize = attachment.size.toLong(),
                )
            }
        val archivedMessage =
            if (knowledgeEnabled) {
                discordKnowledgeMessage(
                    messageId = messageId,
                    guildId = guildId,
                    channelId = channelId,
                    channelName = channelName,
                    authorId = event.author.id,
                    authorName = authorName,
                    authorIsPromethe = false,
                    content = content(),
                    timestampMillis = timestamp,
                    replyToMessageId = replyTo,
                    attachments = attachments,
                )
            } else {
                null
            }

        if (inbound != null) {
            logger.debug {
                "Discord message accepted " +
                    "[channel=$channelId, mention=$isBotMentioned, reply=$isReplyToBot]"
            }
        }

        scope.launch {
            val sessionId = inbound?.sessionId ?: "discord-$guildId-$channelId"
            val channelMutex = channelMutexes.computeIfAbsent(sessionId) { Mutex() }
            channelMutex.withLock {
                archivedMessage?.let { knowledgeArchive.appendBestEffort(it) }
                if (inbound == null) return@withLock
                event.channel.sendTyping().queue(
                    {},
                    { error -> logger.debug(error) { "Discord typing indicator failed" } },
                )
                try {
                    val externalContext =
                        if (knowledgeEnabled) {
                            knowledgeArchive
                                .recentContextBestEffort(
                                    guildId = guildId,
                                    channelId = channelId,
                                    excludeIdentifier = archivedMessage?.identifier,
                                ).takeIf(String::isNotBlank)
                        } else {
                            null
                        }
                    val response = executor.execute(inbound.sessionId, inbound.text, externalContext, projectId)
                    if (knowledgeEnabled) {
                        knowledgeArchive.appendBestEffort(
                            discordKnowledgeMessage(
                                messageId = "promethe-$messageId",
                                guildId = guildId,
                                channelId = channelId,
                                channelName = channelName,
                                authorId = selfUser.id,
                                authorName = selfUser.name,
                                authorIsPromethe = true,
                                content = response,
                                timestampMillis = System.currentTimeMillis(),
                                replyToMessageId = messageId,
                            ),
                        )
                    }
                    sendDiscordReply(event, response)
                } catch (error: Exception) {
                    logger.error(error) { "Discord agent execution failed for channel $channelId" }
                    sendDiscordReply(event, "Promethe could not process this message. Please try again.")
                }
            }
        }
    }

    override fun onButtonInteraction(event: ButtonInteractionEvent) {
        val action = parseDiscordApprovalAction(event.componentId) ?: return
        if (event.user.id !in approvalUserIdsProvider()) {
            event.reply("You are not authorized to resolve this Promethe request.").setEphemeral(true).queue()
            return
        }
        val gate = approvalGate
        if (gate == null) {
            event.editMessage("This approval service is no longer available.").setComponents(emptyList()).queue()
            return
        }

        val result =
            gate.respondFromIntegrationApprover(
                requestId = action.requestId,
                approved = action.approved,
                scope = action.scope,
            )
        val resolution =
            when (result) {
                ToolApprovalGate.ResponseResult.ACCEPTED -> {
                    if (action.approved) {
                        "Approved by ${event.user.effectiveName} (${action.scope.name.lowercase()})."
                    } else {
                        "Rejected by ${event.user.effectiveName}."
                    }
                }

                ToolApprovalGate.ResponseResult.NOT_FOUND -> {
                    "This approval request expired or was already resolved."
                }

                ToolApprovalGate.ResponseResult.LOCAL_OWNER_REQUIRED -> {
                    "This operation can only be approved by the local Desktop owner."
                }

                ToolApprovalGate.ResponseResult.PERSISTENT_REQUIRES_LOCAL_OWNER -> {
                    "A permanent grant is not allowed for this operation."
                }

                ToolApprovalGate.ResponseResult.INVALID_EXPIRATION -> {
                    "The requested approval duration is invalid."
                }

                ToolApprovalGate.ResponseResult.PERSISTENCE_FAILED -> {
                    "The permanent approval could not be stored. Resolve it from the local Desktop app."
                }
            }
        event.editMessage(resolution).setComponents(emptyList()).queue()
    }
}

internal data class DiscordApprovalAction(
    val requestId: String,
    val approved: Boolean,
    val scope: ApprovalGate.ApprovalScope,
)

private const val DISCORD_APPROVAL_ACTION_PREFIX = "promethe:approval"

internal fun discordApprovalActionId(
    requestId: String,
    approved: Boolean,
    scope: ApprovalGate.ApprovalScope,
): String = "$DISCORD_APPROVAL_ACTION_PREFIX:${if (approved) "allow" else "deny"}:${scope.name}:$requestId"

internal fun parseDiscordApprovalAction(customId: String): DiscordApprovalAction? {
    val parts = customId.split(':', limit = 5)
    if (parts.size != 5 || parts[0] != "promethe" || parts[1] != "approval") return null
    val approved =
        when (parts[2]) {
            "allow" -> true
            "deny" -> false
            else -> return null
        }
    val scope = runCatching { ApprovalGate.ApprovalScope.valueOf(parts[3]) }.getOrNull() ?: return null
    val requestId = parts[4].takeIf(String::isNotBlank) ?: return null
    return DiscordApprovalAction(requestId, approved, scope)
}

internal fun discordApprovalButtons(request: ToolApprovalGate.ApprovalRequest): List<Button> =
    buildList {
        add(Button.danger(discordApprovalActionId(request.id, false, ApprovalGate.ApprovalScope.ONCE), "Reject"))
        add(Button.success(discordApprovalActionId(request.id, true, ApprovalGate.ApprovalScope.ONCE), "Allow once"))
        add(Button.primary(discordApprovalActionId(request.id, true, ApprovalGate.ApprovalScope.SESSION), "Session"))
        if (request.persistentAllowed) {
            add(
                Button.secondary(
                    discordApprovalActionId(request.id, true, ApprovalGate.ApprovalScope.PERSISTENT),
                    "Always allow",
                ),
            )
        }
    }

internal fun discordApprovalMessage(request: ToolApprovalGate.ApprovalRequest): String =
    buildString {
        appendLine("**Promethe authorization required**")
        appendLine("Tool: `${request.toolName}`")
        appendLine("Session: `${request.sessionId}`")
        appendLine("Arguments:")
        append("```json\n")
        append(request.args.replace("```", "'''").take(1_200))
        append("\n```")
    }

internal fun discordInboundMessage(
    content: () -> String,
    botUserId: String,
    isGuildMessage: Boolean,
    isBotMentioned: Boolean,
    guildId: String?,
    channelId: String,
    listenForAddressedMessages: Boolean = false,
    isReplyToBot: Boolean = false,
    botNames: Set<String> = emptySet(),
): DiscordInboundMessage? {
    if (isGuildMessage && !isBotMentioned && !isReplyToBot && !listenForAddressedMessages) return null

    val mentionPattern = Regex("<@!?${Regex.escape(botUserId)}>")
    val rawContent = content()
    val addressedContent =
        if (isGuildMessage && !isBotMentioned && !isReplyToBot) {
            stripDiscordBotAddress(rawContent, botNames) ?: return null
        } else {
            rawContent
        }
    val text = addressedContent.replace(mentionPattern, " ").trim().ifBlank { "Hello" }
    val sessionId =
        if (isGuildMessage) {
            "discord-${guildId.orEmpty()}-$channelId"
        } else {
            "discord-dm-$channelId"
        }
    return DiscordInboundMessage(sessionId = sessionId, text = text)
}

private val DISCORD_SNOWFLAKE = Regex("[0-9]{1,20}")

internal fun stripDiscordBotAddress(
    content: String,
    botNames: Set<String>,
): String? {
    val exactMatch =
        botNames
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .sortedByDescending(String::length)
            .mapNotNull { name ->
                val pattern =
                    Regex(
                        pattern =
                            "^\\s*(?:(?:hey|hi|hello|bonjour|salut|coucou)[\\s,]+)?" +
                                "@?${Regex.escape(name)}(?=\\s|[,;:!?-]|$)[\\s,;:!?-]*(.*)$",
                        option = RegexOption.IGNORE_CASE,
                    )
                pattern.matchEntire(content)?.groupValues?.get(1)
            }.firstOrNull()
    if (exactMatch != null) return exactMatch

    val fuzzyMatch =
        Regex(
            pattern =
                "^\\s*(?:(?:hey|hi|hello|bonjour|salut|coucou)[\\s,]+)?" +
                    "@?([^\\s,;:!?-]+)(?=\\s|[,;:!?-]|$)[\\s,;:!?-]*(.*)$",
            option = RegexOption.IGNORE_CASE,
        ).matchEntire(content) ?: return null
    val candidate = fuzzyMatch.groupValues[1]
    val matchesBotName =
        botNames.any { botName ->
            val primaryName = botName.trim().substringBefore(' ')
            isPermissiveDiscordBotName(candidate, primaryName)
        }
    return fuzzyMatch.groupValues[2].takeIf { matchesBotName }
}

internal fun isPermissiveDiscordBotName(
    candidate: String,
    botName: String,
): Boolean {
    val normalizedCandidate = normalizeDiscordBotName(candidate)
    val normalizedBotName = normalizeDiscordBotName(botName)
    if (normalizedCandidate == normalizedBotName) return true
    if (normalizedCandidate.length < 6 || normalizedBotName.length < 6) return false
    return levenshteinDistance(normalizedCandidate, normalizedBotName) <= 2
}

private fun normalizeDiscordBotName(value: String): String =
    Normalizer
        .normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .filter(Char::isLetterOrDigit)

private fun levenshteinDistance(
    left: String,
    right: String,
): Int {
    var previous = IntArray(right.length + 1) { it }
    left.forEachIndexed { leftIndex, leftChar ->
        val current = IntArray(right.length + 1)
        current[0] = leftIndex + 1
        right.forEachIndexed { rightIndex, rightChar ->
            val insertion = current[rightIndex] + 1
            val deletion = previous[rightIndex + 1] + 1
            val substitution = previous[rightIndex] + if (leftChar == rightChar) 0 else 1
            current[rightIndex + 1] = minOf(insertion, deletion, substitution)
        }
        previous = current
    }
    return previous[right.length]
}

internal fun splitDiscordMessage(
    content: String,
    maxLength: Int = Message.MAX_CONTENT_LENGTH,
): List<String> {
    val remaining = content.trim()
    if (remaining.isEmpty()) return listOf("Promethe completed the request without a text response.")
    if (remaining.length <= maxLength) return listOf(remaining)

    val chunks = mutableListOf<String>()
    var cursor = remaining
    while (cursor.length > maxLength) {
        val newline = cursor.lastIndexOf('\n', startIndex = maxLength)
        val space = cursor.lastIndexOf(' ', startIndex = maxLength)
        val splitAt = maxOf(newline, space).takeIf { it >= maxLength / 2 } ?: maxLength
        chunks += cursor.substring(0, splitAt).trimEnd()
        cursor = cursor.substring(splitAt).trimStart()
    }
    if (cursor.isNotEmpty()) chunks += cursor
    return chunks
}

private fun sendDiscordReply(
    event: MessageReceivedEvent,
    response: String,
) {
    val allowedMentions = EnumSet.noneOf(Message.MentionType::class.java)
    splitDiscordMessage(response).forEachIndexed { index, chunk ->
        val action =
            if (index == 0) {
                event.message.reply(chunk).mentionRepliedUser(false)
            } else {
                event.channel.sendMessage(chunk)
            }
        action
            .setAllowedMentions(allowedMentions)
            .queue(
                {},
                { error -> logger.warn(error) { "Discord reply failed for channel ${event.channel.id}" } },
            )
    }
}
