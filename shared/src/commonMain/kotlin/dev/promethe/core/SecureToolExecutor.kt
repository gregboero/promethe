package dev.promethe.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Entry point shared by every protocol that can invoke a Promethe tool. */
fun interface SecureToolExecutor {
    suspend fun execute(request: ToolExecutionRequest): String
}

typealias ToolCallOrigin = dev.promethe.api.ToolCallOrigin
typealias ToolExecutionRequest = dev.promethe.api.ToolInvocation
typealias ToolRisk = dev.promethe.api.ToolRisk

data class ToolPolicyDecision(
    val risk: ToolRisk,
    val mandatoryApproval: Boolean,
    val ownerOnly: Boolean = false,
)

/** Deterministic representation used when approvals are bound to arguments. */
fun canonicalJson(element: JsonElement): String =
    when (element) {
        is JsonObject -> {
            buildJsonObject {
                element.keys.sorted().forEach { key -> put(key, canonicalElement(element.getValue(key))) }
            }.toString()
        }

        is JsonArray -> {
            buildJsonArray { element.forEach { add(canonicalElement(it)) } }.toString()
        }

        else -> {
            element.toString()
        }
    }

private fun canonicalElement(element: JsonElement): JsonElement =
    when (element) {
        is JsonObject -> {
            buildJsonObject {
                element.keys.sorted().forEach { key -> put(key, canonicalElement(element.getValue(key))) }
            }
        }

        is JsonArray -> {
            buildJsonArray { element.forEach { add(canonicalElement(it)) } }
        }

        else -> {
            element
        }
    }
