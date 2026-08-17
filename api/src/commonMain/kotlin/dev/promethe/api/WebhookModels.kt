package dev.promethe.api

import kotlinx.serialization.Serializable

// ── Webhook Channel Configuration ──

@Serializable
data class WebhookChannelConfig(
    val id: String = "",
    val name: String, // "telegram", "discord", "slack", "custom-crm"
    val type: String = "inbound", // "inbound", "outbound", "bidirectional"
    val secret: String = "", // Shared secret for signature verification
    val outboundUrl: String = "", // URL to POST events to (outbound channels)
    val enabled: Boolean = true,
    val headerName: String = "", // Custom header for auth (e.g., "X-Telegram-Token")
    val payloadTemplate: String = "", // Mustache-like template for outbound formatting
    val createdAt: Long = 0,
)

@Serializable
data class WebhookChannelListResponse(
    val channels: List<WebhookChannelConfig>,
)

// ── Inbound Webhook Event (received from external service) ──

@Serializable
data class WebhookEvent(
    val channel: String, // Channel name that received the event
    val eventType: String = "message", // "message", "reaction", "command", etc.
    val senderId: String = "", // External user ID
    val senderName: String = "", // External user display name
    val content: String = "", // Message content / payload
    val rawPayload: String = "", // Full raw JSON for custom processing
    val timestamp: Long = 0,
)

// ── Outbound Webhook Dispatch ──

@Serializable
data class WebhookDispatchRequest(
    val channel: String, // Target channel name
    val content: String, // Message to send
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class WebhookDispatchResponse(
    val success: Boolean,
    val channel: String,
    val statusCode: Int = 0,
    val error: String? = null,
)

// ══════════════════════════════════════════════════════════════
// Platform-Specific Webhook Payloads
// ══════════════════════════════════════════════════════════════

// ── WhatsApp Cloud API (Meta) ───────────────────────────────

@Serializable
data class WhatsAppWebhookPayload(
    val entry: List<WhatsAppEntry> = emptyList(),
)

@Serializable
data class WhatsAppEntry(
    val id: String = "",
    val changes: List<WhatsAppChange> = emptyList(),
)

@Serializable
data class WhatsAppChange(
    val value: WhatsAppValue? = null,
)

@Serializable
data class WhatsAppValue(
    val messages: List<WhatsAppMessage> = emptyList(),
    val metadata: WhatsAppMetadata? = null,
)

@Serializable
data class WhatsAppMessage(
    val id: String = "",
    val from: String = "",
    val type: String = "",
    val text: WhatsAppText? = null,
    val timestamp: String = "",
)

@Serializable
data class WhatsAppText(
    val body: String = "",
)

@Serializable
data class WhatsAppMetadata(
    val phone_number_id: String = "",
)

// ── Discord Interaction ─────────────────────────────────────

@Serializable
data class DiscordInteraction(
    val id: String = "",
    val type: Int = 0, // 1=PING, 2=APPLICATION_COMMAND
    val data: DiscordInteractionData? = null,
    val channel_id: String = "",
    val guild_id: String = "",
    val token: String = "",
    val member: DiscordInteractionMember? = null,
    val user: DiscordInteractionUser? = null,
)

@Serializable
data class DiscordInteractionMember(
    val user: DiscordInteractionUser? = null,
    val nick: String? = null,
)

@Serializable
data class DiscordInteractionUser(
    val id: String = "",
    val username: String = "",
    val global_name: String? = null,
)

@Serializable
data class DiscordInteractionData(
    val name: String = "",
    val options: List<DiscordOption> = emptyList(),
)

@Serializable
data class DiscordOption(
    val name: String = "",
    val value: String = "",
)

@Serializable
data class DiscordInteractionResponse(
    val type: Int, // 1=PONG, 4=CHANNEL_MESSAGE_WITH_SOURCE
    val data: DiscordResponseData? = null,
)

@Serializable
data class DiscordResponseData(
    val content: String,
    val flags: Int? = null,
)

// ── Slack Events API ────────────────────────────────────────

@Serializable
data class SlackEventPayload(
    val event_id: String = "",
    val type: String = "", // "url_verification" or "event_callback"
    val challenge: String = "",
    val event: SlackEvent? = null,
)

@Serializable
data class SlackEvent(
    val type: String = "", // "message", "app_mention"
    val text: String = "",
    val user: String = "",
    val channel: String = "",
    val ts: String = "",
)

@Serializable
data class SlackChallengeResponse(
    val challenge: String,
)

// ── Signal CLI REST API ─────────────────────────────────────

@Serializable
data class SignalWebhookPayload(
    val envelope: SignalEnvelope? = null,
    val account: String = "",
)

@Serializable
data class SignalEnvelope(
    val source: String = "",
    val sourceNumber: String = "",
    val sourceName: String = "",
    val sourceDevice: Int = 0,
    val timestamp: Long = 0,
    val dataMessage: SignalDataMessage? = null,
    val syncMessage: SignalSyncMessage? = null,
)

@Serializable
data class SignalDataMessage(
    val message: String = "",
    val timestamp: Long = 0,
    val groupInfo: SignalGroupInfo? = null,
)

@Serializable
data class SignalSyncMessage(
    val sentMessage: SignalSentMessage? = null,
)

@Serializable
data class SignalSentMessage(
    val destination: String? = null,
    val timestamp: Long = 0,
    val message: String? = null,
)

@Serializable
data class SignalGroupInfo(
    val groupId: String = "",
    val type: String = "",
)

@Serializable
data class SignalSendRequest(
    val message: String,
    val number: String,
    val recipients: List<String>,
)

@Serializable
data class SignalChannelConfig(
    val apiUrl: String = "http://localhost:8080",
    val senderNumber: String = "",
    val enabled: Boolean = true,
    val allowedNumbers: List<String> = emptyList(),
    val allowedGroups: List<String> = emptyList(),
)

// ── Matrix Appservice ───────────────────────────────────────

@Serializable
data class MatrixEvent(
    val event_id: String = "",
    val type: String = "",
    val sender: String = "",
    val room_id: String = "",
    val content: MatrixContent? = null,
)

@Serializable
data class MatrixContent(
    val msgtype: String = "", // "m.text"
    val body: String = "",
)
