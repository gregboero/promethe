package dev.promethe.api

import kotlinx.serialization.Serializable

const val DISCORD_ACCESS_POLICY_SETTING_KEY = "discord_access_policy_v1"

@Serializable
enum class DiscordUserRuleEffect {
    ALLOW,
    DENY,
}

@Serializable
data class DiscordUserAccessRule(
    val userId: String,
    val guildId: String? = null,
    val channelId: String? = null,
    val effect: DiscordUserRuleEffect = DiscordUserRuleEffect.ALLOW,
    val allowedTopics: List<String> = emptyList(),
)

@Serializable
data class DiscordChannelListenRule(
    val channelId: String,
    val guildId: String? = null,
    val captureKnowledge: Boolean = true,
    val projectId: String? = null,
)

@Serializable
data class DiscordAccessPolicy(
    val userRules: List<DiscordUserAccessRule> = emptyList(),
    val channelRules: List<DiscordChannelListenRule> = emptyList(),
    val updatedAt: Long = 0,
)

@Serializable
data class UpsertDiscordUserRuleRequest(
    val guildId: String? = null,
    val channelId: String? = null,
    val effect: DiscordUserRuleEffect = DiscordUserRuleEffect.ALLOW,
    val allowedTopics: List<String> = emptyList(),
)

@Serializable
data class UpsertDiscordChannelRuleRequest(
    val guildId: String? = null,
    val captureKnowledge: Boolean = true,
    val projectId: String? = null,
)
