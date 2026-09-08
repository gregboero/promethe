package dev.promethe.core

import kotlinx.serialization.json.*

internal data class CampaignHttpResponse(
    val status: Int,
    val body: String,
    val requestId: String? = null,
)

internal data class CampaignResponseInspection(
    val text: String?,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val diagnostics: JsonObject,
    val errorCode: String?,
)

/** Decode only the LAB text protocol, retaining an allowlist of metadata, never error bodies. */
internal object CampaignResponseDiagnostics {
    fun inspect(response: CampaignHttpResponse): CampaignResponseInspection {
        val body = if (response.body.length <= 1024 * 1024) runCatching { Json.parseToJsonElement(response.body) as? JsonObject }.getOrNull() else null
        val choices = body?.get("choices") as? JsonArray
        val choice = choices?.singleOrNull() as? JsonObject
        val message = choice?.get("message") as? JsonObject
        val content = message?.get("content")
        val text = (content as? JsonPrimitive)?.takeIf { it.isString }?.content
        val contentState = when {
            message == null || "content" !in message -> "missing"
            content == JsonNull -> "null"
            text == null -> "invalid"
            text.isEmpty() -> "empty"
            text.isBlank() -> "blank"
            else -> "text"
        }
        val finish = (choice?.get("finish_reason") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val refusal = message?.get("refusal")?.let { it != JsonNull } == true
        val toolCalls = message?.get("tool_calls") as? JsonArray
        val legacyCall = message?.get("function_call")?.let { it != JsonNull } == true
        val usage = body?.get("usage") as? JsonObject

        fun number(value: JsonElement?): Long? = (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        val input = number(usage?.get("prompt_tokens"))?.takeIf { it in 0..Int.MAX_VALUE.toLong() }
        val output = number(usage?.get("completion_tokens"))?.takeIf { it in 0..4096L }
        val usageStatus = when {
            usage == null -> "missing"
            input == null || output == null -> "invalid"
            else -> "valid"
        }

        fun identifier(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.matches(Regex("[A-Za-z0-9._:/-]{1,128}")) }
        val diagnostics = buildJsonObject {
            put("httpStatus", response.status)
            put("requestId", response.requestId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) })
            put("completionId", identifier(body?.get("id")))
            put("responseModel", identifier(body?.get("model")))
            put("choiceCount", choices?.size?.let(::JsonPrimitive) ?: JsonNull)
            put("finishReason", finish?.takeIf { it.matches(Regex("[a-z_]{1,32}")) })
            put("contentState", contentState)
            put("contentCharacters", text?.length?.let(::JsonPrimitive) ?: JsonNull)
            put("refusalPresent", refusal)
            put("toolCallCount", toolCalls?.size ?: 0)
            put("legacyFunctionCallPresent", legacyCall)
            put("usageStatus", usageStatus)
            val reasoning = number((usage?.get("completion_tokens_details") as? JsonObject)?.get("reasoning_tokens"))?.takeIf { output != null && it in 0..output }
            val cached = number((usage?.get("prompt_tokens_details") as? JsonObject)?.get("cached_tokens"))?.takeIf { input != null && it in 0..input }
            put("reasoningTokens", reasoning?.let(::JsonPrimitive) ?: JsonNull)
            put("cachedInputTokens", cached?.let(::JsonPrimitive) ?: JsonNull)
        }
        val error = when {
            response.status != 200 -> "campaign_http_error"
            body == null -> "campaign_invalid_json"
            choices?.size != 1 || message == null -> "campaign_invalid_response_shape"
            usageStatus != "valid" -> "campaign_${usageStatus}_usage"
            refusal -> "campaign_refusal"
            finish == "content_filter" -> "campaign_content_filtered"
            finish == "length" -> "campaign_completion_length"
            finish in setOf("tool_calls", "function_call") || !toolCalls.isNullOrEmpty() || legacyCall -> "campaign_unsupported_tool_call"
            finish != "stop" -> "campaign_unknown_finish_reason"
            contentState == "invalid" -> "campaign_invalid_content"
            text.isNullOrBlank() -> "campaign_empty_content"
            else -> null
        }
        return CampaignResponseInspection(text, input, output, diagnostics, error)
    }
}
