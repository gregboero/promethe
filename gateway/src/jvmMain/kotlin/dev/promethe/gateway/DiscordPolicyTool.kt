package dev.promethe.gateway

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.promethe.api.DiscordUserRuleEffect
import dev.promethe.api.ToolCallOrigin
import dev.promethe.api.UpsertDiscordChannelRuleRequest
import dev.promethe.api.UpsertDiscordUserRuleRequest
import dev.promethe.core.currentToolInvocation
import kotlinx.serialization.Serializable

@Serializable
internal data class DiscordPolicyToolArgs(
    @property:LLMDescription(
        "Action: list, allow_user, deny_user, remove_user_rule, listen_channel, stop_listening_channel, or remove_channel_rule.",
    )
    val action: String,
    @property:LLMDescription("Discord user/channel numeric ID or mention. Not required for list.")
    val targetId: String = "",
    @property:LLMDescription("Allowed subject phrases for allow_user. Empty means every subject.")
    val topics: List<String> = emptyList(),
    @property:LLMDescription("Optional Discord guild numeric ID limiting a rule to one server.")
    val guildId: String? = null,
    @property:LLMDescription("Optional Discord channel numeric ID limiting a user rule to one channel.")
    val channelId: String? = null,
    @property:LLMDescription("Optional Promethe project ID associated with a listened channel.")
    val projectId: String? = null,
)

internal class DiscordPolicyTool(
    private val service: DiscordPolicyService,
    private val onPolicyChanged: suspend () -> Unit,
) : SimpleTool<DiscordPolicyToolArgs>(
        argsType = typeToken<DiscordPolicyToolArgs>(),
        name = "discord_policy",
        description =
            "Owner-only Discord access administration. Authorize or deny a Discord user, restrict an allowed user " +
                "to deterministic subject phrases, capture every message from a channel as Open Knowledge, stop capture, " +
                "associate that channel with a Promethe project, or list the current live policy. " +
                "Use Discord numeric IDs or mentions such as <@123> and <#456>.",
    ) {
    override suspend fun execute(args: DiscordPolicyToolArgs): String {
        val origin = currentToolInvocation()?.origin
        if (origin !in OWNER_POLICY_ORIGINS) {
            return "[BLOCKED] Discord policy can only be changed from an authenticated owner conversation."
        }

        return try {
            val result =
                when (args.action.trim().lowercase()) {
                    "list" -> {
                        return service.current().formatForOwner()
                    }

                    "allow_user" -> {
                        service.upsertUserRule(
                            args.targetId,
                            UpsertDiscordUserRuleRequest(
                                guildId = args.guildId,
                                channelId = args.channelId,
                                effect = DiscordUserRuleEffect.ALLOW,
                                allowedTopics = args.topics,
                            ),
                        )
                    }

                    "deny_user" -> {
                        service.upsertUserRule(
                            args.targetId,
                            UpsertDiscordUserRuleRequest(
                                guildId = args.guildId,
                                channelId = args.channelId,
                                effect = DiscordUserRuleEffect.DENY,
                            ),
                        )
                    }

                    "remove_user_rule" -> {
                        service.removeUserRule(args.targetId, args.guildId, args.channelId)
                    }

                    "listen_channel" -> {
                        service.upsertChannelRule(
                            args.targetId,
                            UpsertDiscordChannelRuleRequest(
                                guildId = args.guildId,
                                captureKnowledge = true,
                                projectId = args.projectId,
                            ),
                        )
                    }

                    "stop_listening_channel" -> {
                        service.upsertChannelRule(
                            args.targetId,
                            UpsertDiscordChannelRuleRequest(
                                guildId = args.guildId,
                                captureKnowledge = false,
                                projectId = args.projectId,
                            ),
                        )
                    }

                    "remove_channel_rule" -> {
                        service.removeChannelRule(args.targetId)
                    }

                    else -> {
                        return "[ERROR] Unknown Discord policy action '${args.action}'."
                    }
                }
            onPolicyChanged()
            "Discord policy updated immediately.\n${result.formatForOwner()}"
        } catch (error: IllegalArgumentException) {
            "[ERROR] ${error.message}"
        }
    }
}

private fun dev.promethe.api.DiscordAccessPolicy.formatForOwner(): String =
    buildString {
        appendLine("Discord live policy:")
        if (userRules.isEmpty()) {
            appendLine("- User rules: none (static configuration or unrestricted fallback applies)")
        } else {
            userRules.forEach { rule ->
                val scope = rule.channelId?.let { "channel=$it" } ?: rule.guildId?.let { "guild=$it" } ?: "global"
                val topics = rule.allowedTopics.takeIf { it.isNotEmpty() }?.joinToString() ?: "all subjects"
                appendLine("- User ${rule.userId}: ${rule.effect} ($scope; $topics)")
            }
        }
        if (channelRules.isEmpty()) {
            appendLine("- Channel rules: none")
        } else {
            channelRules.forEach { rule ->
                appendLine(
                    "- Channel ${rule.channelId}: capture=${rule.captureKnowledge}, project=${rule.projectId ?: "none"}",
                )
            }
        }
    }.trim()

private val OWNER_POLICY_ORIGINS = setOf(ToolCallOrigin.A2A)
