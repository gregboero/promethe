package dev.promethe.gateway.voice

import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import dev.promethe.api.voice.ToolSchema
import dev.promethe.core.Log
import dev.promethe.core.PolicyEffect
import dev.promethe.core.PolicyKernel
import dev.promethe.core.ToolApprovalPolicy
import dev.promethe.core.ToolCallOrigin
import dev.promethe.core.ToolExecutionRequest
import dev.promethe.core.ToolJsonSchemaGenerator
import dev.promethe.core.ToolRegistry
import kotlinx.serialization.json.*

private val logger = Log.create("ToolSchemaExporter")

/**
 * ToolSchemaExporter — converts [ToolRegistry] tools into portable [ToolSchema]
 * objects that can be injected into voice provider setup messages.
 *
 * Each provider has its own tool declaration format:
 * - Gemini: `tools[].functionDeclarations[]`
 * - OpenAI: `session.tools[]` with type "function"
 *
 * This exporter handles the conversion so VoiceRelay implementations
 * don't need to know about the ToolRegistry internals.
 */
object ToolSchemaExporter {
    /**
     * Export all registered tools as portable [ToolSchema] objects.
     */
    @OptIn(InternalAgentToolsApi::class)
    suspend fun exportFromRegistry(policyKernel: PolicyKernel = PolicyKernel()): List<ToolSchema> {
        val tools =
            ToolRegistry.listTools().filter { tool ->
                val request =
                    ToolExecutionRequest(
                        toolName = tool.name,
                        arguments = JsonObject(emptyMap()),
                        origin = ToolCallOrigin.VOICE,
                    )
                policyKernel.evaluate(request, ToolApprovalPolicy.contractFor(tool.name)).effect == PolicyEffect.ALLOW
            }
        return tools.sortedBy { it.name }.mapNotNull { tool ->
            try {
                val desc = tool.descriptor
                ToolSchema(
                    name = desc.name,
                    description = desc.description,
                    parameters = ToolJsonSchemaGenerator.inputSchema(desc),
                )
            } catch (e: Exception) {
                logger.debug(e) { "Failed to export tool ${tool.name}" }
                null
            }
        }
    }

    /**
     * Convert tool schemas to Gemini `functionDeclarations` format.
     *
     * Output format:
     * ```json
     * [{ "functionDeclarations": [...] }]
     * ```
     */
    fun toGeminiTools(schemas: List<ToolSchema>): JsonArray =
        buildJsonArray {
            add(
                buildJsonObject {
                    put(
                        "functionDeclarations",
                        buildJsonArray {
                            for (schema in schemas) {
                                // Sanitize: Gemini rejects tools with empty properties
                                val params = schema.parameters
                                val props = params["properties"]?.jsonObject
                                if (props != null && props.isEmpty()) continue

                                add(
                                    buildJsonObject {
                                        put("name", schema.name)
                                        put("description", schema.description)
                                        // Gemini requires uppercase types and no empty required arrays
                                        put("parameters", geminiSanitizeSchema(params))
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }

    /**
     * Recursively sanitize a JSON Schema object for Gemini:
     * - Convert type values to UPPERCASE (string → STRING, etc.)
     * - Remove empty "required" arrays
     * - Recurse into "properties" and "items"
     */
    private fun geminiSanitizeSchema(obj: JsonObject): JsonObject =
        nullableGeminiSchema(obj) ?: buildJsonObject {
            for ((key, value) in obj) {
                when {
                    key == "\$schema" -> {}

                    key == "type" && value is JsonPrimitive -> {
                        put("type", value.content.uppercase())
                    }

                    key == "required" && value is JsonArray && value.isEmpty() -> {}

                    // skip empty required arrays
                    key == "properties" && value is JsonObject -> {
                        put(
                            "properties",
                            buildJsonObject {
                                for ((propName, propValue) in value) {
                                    if (propValue is JsonObject) {
                                        put(propName, geminiSanitizeSchema(propValue))
                                    } else {
                                        put(propName, propValue)
                                    }
                                }
                            },
                        )
                    }

                    key == "items" && value is JsonObject -> {
                        put("items", geminiSanitizeSchema(value))
                    }

                    key == "anyOf" && value is JsonArray -> {
                        put(
                            "anyOf",
                            buildJsonArray {
                                value.forEach { item ->
                                    add(if (item is JsonObject) geminiSanitizeSchema(item) else item)
                                }
                            },
                        )
                    }

                    key == "additionalProperties" -> {}

                    else -> {
                        put(key, value)
                    }
                }
            }
        }

    private fun nullableGeminiSchema(obj: JsonObject): JsonObject? {
        val alternatives = obj["anyOf"] as? JsonArray ?: return null
        if (alternatives.size != 2 || alternatives.any { it !is JsonObject }) return null
        val schemas = alternatives.map { it.jsonObject }
        if (schemas.count { it["type"]?.jsonPrimitive?.content == "null" } != 1) return null
        val valueSchema = schemas.single { it["type"]?.jsonPrimitive?.content != "null" }
        return buildJsonObject {
            geminiSanitizeSchema(valueSchema).forEach { (key, value) -> put(key, value) }
            put("nullable", true)
            obj["description"]?.let { put("description", it) }
        }
    }

    /**
     * Convert tool schemas to OpenAI Realtime `session.tools` format.
     *
     * Output format:
     * ```json
     * [{ "type": "function", "name": "...", "description": "...", "parameters": {...} }]
     * ```
     */
    fun toOpenAITools(schemas: List<ToolSchema>): JsonArray =
        buildJsonArray {
            for (schema in schemas) {
                add(
                    buildJsonObject {
                        put("type", "function")
                        put("name", schema.name)
                        put("description", schema.description)
                        put("parameters", schema.parameters.withoutDialect())
                    },
                )
            }
        }

    private fun JsonObject.withoutDialect(): JsonObject = JsonObject(filterKeys { it != "\$schema" })
}
