package dev.promethe.gateway.voice

import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import dev.promethe.api.voice.ToolSchema
import dev.promethe.core.Log
import dev.promethe.core.PolicyEffect
import dev.promethe.core.PolicyKernel
import dev.promethe.core.ToolApprovalPolicy
import dev.promethe.core.ToolCallOrigin
import dev.promethe.core.ToolExecutionRequest
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
        return tools.mapNotNull { tool ->
            try {
                val desc = tool.descriptor
                val params = buildJsonObject {
                    put("type", "object")
                    val properties = buildJsonObject {
                        for (p in desc.requiredParameters + desc.optionalParameters) {
                            put(
                                p.name,
                                buildJsonObject {
                                    putAll(parameterTypeToJson(p.type))
                                    put("description", p.name)
                                },
                            )
                        }
                    }
                    put("properties", properties)
                    val required = buildJsonArray {
                        for (p in desc.requiredParameters) {
                            add(p.name)
                        }
                    }
                    if (required.isNotEmpty()) {
                        put("required", required)
                    }
                }
                ToolSchema(
                    name = desc.name,
                    description = desc.description,
                    parameters = params,
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
        buildJsonObject {
            for ((key, value) in obj) {
                when {
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

                    else -> {
                        put(key, value)
                    }
                }
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
                        put("parameters", schema.parameters)
                    },
                )
            }
        }

    /**
     * Convert Koog [ToolParameterType] to JSON Schema properties map.
     * Follows the same pattern as Anthropic/Gemini LLM clients.
     */
    @OptIn(InternalAgentToolsApi::class)
    private fun parameterTypeToJson(type: ToolParameterType): Map<String, JsonElement> =
        when (type) {
            ToolParameterType.Boolean -> {
                mapOf("type" to JsonPrimitive("boolean"))
            }

            ToolParameterType.Float -> {
                mapOf("type" to JsonPrimitive("number"))
            }

            ToolParameterType.Integer -> {
                mapOf("type" to JsonPrimitive("integer"))
            }

            ToolParameterType.String -> {
                mapOf("type" to JsonPrimitive("string"))
            }

            ToolParameterType.Null -> {
                mapOf("type" to JsonPrimitive("string"))
            }

            is ToolParameterType.Enum -> {
                mapOf(
                    "type" to JsonPrimitive("string"),
                    "enum" to JsonArray(type.entries.map { JsonPrimitive(it.lowercase()) }),
                )
            }

            is ToolParameterType.List -> {
                mapOf(
                    "type" to JsonPrimitive("array"),
                    "items" to JsonObject(parameterTypeToJson(type.itemsType)),
                )
            }

            is ToolParameterType.Object -> {
                val propertiesMap = buildJsonObject {
                    for (prop in type.properties) {
                        put(
                            prop.name,
                            buildJsonObject {
                                putAll(parameterTypeToJson(prop.type))
                                put("description", prop.description)
                            },
                        )
                    }
                }
                val result = mutableMapOf<String, JsonElement>(
                    "type" to JsonPrimitive("object"),
                    "properties" to propertiesMap,
                )
                if (type.requiredProperties.isNotEmpty()) {
                    result["required"] = JsonArray(type.requiredProperties.map { JsonPrimitive(it) })
                }
                result
            }

            is ToolParameterType.AnyOf -> {
                // Fallback: use string type for union types
                mapOf("type" to JsonPrimitive("string"))
            }
        }
}

/** Helper to merge a map into a JsonObjectBuilder. */
private fun JsonObjectBuilder.putAll(map: Map<String, JsonElement>) {
    for ((k, v) in map) put(k, v)
}
