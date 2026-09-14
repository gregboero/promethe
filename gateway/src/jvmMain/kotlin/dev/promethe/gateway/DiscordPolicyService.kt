package dev.promethe.gateway

import dev.promethe.api.DISCORD_ACCESS_POLICY_SETTING_KEY
import dev.promethe.api.DiscordAccessPolicy
import dev.promethe.api.DiscordChannelListenRule
import dev.promethe.api.DiscordUserAccessRule
import dev.promethe.api.DiscordUserRuleEffect
import dev.promethe.api.UpsertDiscordChannelRuleRequest
import dev.promethe.api.UpsertDiscordUserRuleRequest
import dev.promethe.db.PrometheDatabaseApi
import java.text.Normalizer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val policyLogger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

internal class DiscordPolicyService(
    private val database: PrometheDatabaseApi,
) {
    private val mutex = Mutex()
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Volatile
    private var policy = DiscordAccessPolicy()

    fun current(): DiscordAccessPolicy = policy

    suspend fun reload(): DiscordAccessPolicy =
        mutex.withLock {
            policy =
                database.getSetting(DISCORD_ACCESS_POLICY_SETTING_KEY)
                    ?.takeIf(String::isNotBlank)
                    ?.let { encoded ->
                        runCatching { json.decodeFromString<DiscordAccessPolicy>(encoded) }
                            .onFailure { policyLogger.error(it) { "Stored Discord access policy is invalid; failing closed" } }
                            .getOrElse { DiscordAccessPolicy(userRules = listOf(failClosedRule())) }
                    } ?: DiscordAccessPolicy()
            policy
        }

    suspend fun upsertUserRule(
        rawUserId: String,
        request: UpsertDiscordUserRuleRequest,
    ): DiscordAccessPolicy {
        val userId = requireDiscordId(rawUserId, "userId")
        val guildId = optionalDiscordId(request.guildId, "guildId")
        val channelId = optionalDiscordId(request.channelId, "channelId")
        val topics = normalizeTopics(request.allowedTopics)
        val rule = DiscordUserAccessRule(userId, guildId, channelId, request.effect, topics)
        return update("user:${rule.userId}:${rule.guildId.orEmpty()}:${rule.channelId.orEmpty()}:${rule.effect}") { current ->
            current.copy(
                userRules =
                    current.userRules.filterNot {
                        it.userId == userId && it.guildId == guildId && it.channelId == channelId
                    } + rule,
            )
        }
    }

    suspend fun removeUserRule(
        rawUserId: String,
        rawGuildId: String? = null,
        rawChannelId: String? = null,
    ): DiscordAccessPolicy {
        val userId = requireDiscordId(rawUserId, "userId")
        val guildId = optionalDiscordId(rawGuildId, "guildId")
        val channelId = optionalDiscordId(rawChannelId, "channelId")
        return update("user-rule-removed:$userId:${guildId.orEmpty()}:${channelId.orEmpty()}") { current ->
            current.copy(
                userRules =
                    current.userRules.filterNot {
                        it.userId == userId && it.guildId == guildId && it.channelId == channelId
                    },
            )
        }
    }

    suspend fun upsertChannelRule(
        rawChannelId: String,
        request: UpsertDiscordChannelRuleRequest,
    ): DiscordAccessPolicy {
        val channelId = requireDiscordId(rawChannelId, "channelId")
        val guildId = optionalDiscordId(request.guildId, "guildId")
        val projectId = request.projectId?.trim()?.takeIf(String::isNotEmpty)
        if (projectId != null) {
            val project = database.getProject(projectId)
            require(project != null && !project.archived) { "Project '$projectId' does not exist or is archived" }
        }
        val rule = DiscordChannelListenRule(channelId, guildId, request.captureKnowledge, projectId)
        return update("channel:${rule.channelId}:capture=${rule.captureKnowledge}:project=${rule.projectId.orEmpty()}") { current ->
            current.copy(
                channelRules = current.channelRules.filterNot { it.channelId == channelId } + rule,
            )
        }
    }

    suspend fun removeChannelRule(rawChannelId: String): DiscordAccessPolicy {
        val channelId = requireDiscordId(rawChannelId, "channelId")
        return update("channel-rule-removed:$channelId") { current ->
            current.copy(channelRules = current.channelRules.filterNot { it.channelId == channelId })
        }
    }

    fun allowsUser(
        staticAllowlist: DiscordIdAllowlist,
        userId: String,
        guildId: String,
        channelId: String,
        content: () -> String,
    ): Boolean {
        val current = policy
        val rule = current.mostSpecificUserRule(userId, guildId, channelId)
        if (rule != null) {
            if (rule.effect == DiscordUserRuleEffect.DENY) return false
            if (rule.allowedTopics.isEmpty()) return true
            val normalizedMessage = normalizeForMatch(content())
            return rule.allowedTopics.any { topic -> normalizedMessage.contains(normalizeForMatch(topic)) }
        }
        if (staticAllowlist.configured) return staticAllowlist.allows(userId)
        return current.userRules.none { it.effect == DiscordUserRuleEffect.ALLOW }
    }

    fun capturesKnowledge(
        staticChannelIds: Set<String>,
        guildId: String,
        channelId: String,
    ): Boolean = channelRule(guildId, channelId)?.captureKnowledge ?: (channelId in staticChannelIds)

    fun projectIdForChannel(
        guildId: String,
        channelId: String,
    ): String? = channelRule(guildId, channelId)?.projectId

    fun effectiveKnowledgeChannelIds(staticChannelIds: Set<String>): Set<String> {
        val overrides = policy.channelRules.associateBy(DiscordChannelListenRule::channelId)
        return staticChannelIds.filterTo(mutableSetOf()) { overrides[it]?.captureKnowledge != false }.apply {
            addAll(policy.channelRules.filter(DiscordChannelListenRule::captureKnowledge).map(DiscordChannelListenRule::channelId))
        }
    }

    private fun channelRule(
        guildId: String,
        channelId: String,
    ): DiscordChannelListenRule? =
        policy.channelRules
            .filter { it.channelId == channelId && (it.guildId == null || it.guildId == guildId) }
            .maxByOrNull { if (it.guildId == null) 0 else 1 }

    private suspend fun update(
        auditSummary: String,
        transform: (DiscordAccessPolicy) -> DiscordAccessPolicy,
    ): DiscordAccessPolicy =
        mutex.withLock {
            val updated = transform(policy).copy(updatedAt = System.currentTimeMillis())
            database.upsertSetting(DISCORD_ACCESS_POLICY_SETTING_KEY, json.encodeToString(updated))
            policy = updated
            policyLogger.info { "Discord access policy updated [$auditSummary]" }
            updated
        }
}

private fun DiscordAccessPolicy.mostSpecificUserRule(
    userId: String,
    guildId: String,
    channelId: String,
): DiscordUserAccessRule? =
    userRules
        .filter {
            it.userId == userId &&
                (it.guildId == null || it.guildId == guildId) &&
                (it.channelId == null || it.channelId == channelId)
        }.maxByOrNull { (if (it.guildId == null) 0 else 1) + (if (it.channelId == null) 0 else 2) }

private fun requireDiscordId(
    raw: String,
    field: String,
): String =
    extractDiscordId(raw)
        ?: throw IllegalArgumentException("$field must be a Discord numeric ID or mention")

private fun optionalDiscordId(
    raw: String?,
    field: String,
): String? = raw?.trim()?.takeIf(String::isNotEmpty)?.let { requireDiscordId(it, field) }

internal fun extractDiscordId(raw: String): String? {
    val trimmed = raw.trim()
    return when {
        DISCORD_POLICY_SNOWFLAKE.matches(trimmed) -> trimmed
        DISCORD_USER_MENTION.matches(trimmed) -> DISCORD_USER_MENTION.matchEntire(trimmed)?.groupValues?.get(1)
        DISCORD_CHANNEL_MENTION.matches(trimmed) -> DISCORD_CHANNEL_MENTION.matchEntire(trimmed)?.groupValues?.get(1)
        else -> null
    }
}

private fun normalizeTopics(topics: List<String>): List<String> {
    require(topics.size <= 20) { "At most 20 Discord topics may be configured per user rule" }
    return topics
        .map(String::trim)
        .filter(String::isNotEmpty)
        .onEach { require(it.length <= 120) { "Discord topics must be at most 120 characters" } }
        .distinctBy(::normalizeForMatch)
}

private fun normalizeForMatch(value: String): String =
    Normalizer
        .normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

private fun failClosedRule() = DiscordUserAccessRule(userId = "0", effect = DiscordUserRuleEffect.ALLOW)

private val DISCORD_POLICY_SNOWFLAKE = Regex("[0-9]{1,20}")
private val DISCORD_USER_MENTION = Regex("<@!?(\\d{1,20})>")
private val DISCORD_CHANNEL_MENTION = Regex("<#(\\d{1,20})>")
