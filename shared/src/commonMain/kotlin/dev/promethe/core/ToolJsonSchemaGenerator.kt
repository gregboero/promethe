package dev.promethe.core

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Builds deterministic JSON Schema 2020-12 documents from Koog tool descriptors. */
object ToolJsonSchemaGenerator {
    const val DIALECT = "https://json-schema.org/draft/2020-12/schema"

    fun inputSchema(descriptor: ToolDescriptor): JsonObject =
        objectSchema(
            properties = descriptor.requiredParameters + descriptor.optionalParameters,
            requiredProperties = descriptor.requiredParameters.map { it.name },
            additionalProperties = JsonPrimitive(false),
            includeDialect = true,
        )

    fun parameterSchema(type: ToolParameterType): JsonObject = type.toJsonSchema()

    private fun ToolParameterType.toJsonSchema(): JsonObject =
        when (this) {
            ToolParameterType.Boolean -> {
                typeSchema("boolean")
            }

            ToolParameterType.Float -> {
                typeSchema("number")
            }

            ToolParameterType.Integer -> {
                typeSchema("integer")
            }

            ToolParameterType.Null -> {
                typeSchema("null")
            }

            ToolParameterType.String -> {
                typeSchema("string")
            }

            is ToolParameterType.Enum -> {
                buildJsonObject {
                    put("type", "string")
                    put("enum", JsonArray(entries.map(::JsonPrimitive)))
                }
            }

            is ToolParameterType.List -> {
                buildJsonObject {
                    put("type", "array")
                    put("items", itemsType.toJsonSchema())
                }
            }

            is ToolParameterType.Object -> {
                objectSchema(
                    properties = properties,
                    requiredProperties = requiredProperties,
                    additionalProperties =
                        additionalPropertiesType?.toJsonSchema()
                            ?: JsonPrimitive(additionalProperties ?: false),
                )
            }

            is ToolParameterType.AnyOf -> {
                buildJsonObject {
                    put(
                        "anyOf",
                        buildJsonArray {
                            types.forEach { add(it.type.toJsonSchema().withDescription(it.description)) }
                        },
                    )
                }
            }
        }

    private fun objectSchema(
        properties: List<ToolParameterDescriptor>,
        requiredProperties: List<String>,
        additionalProperties: JsonElement,
        includeDialect: Boolean = false,
    ): JsonObject {
        val propertyNames = properties.map { it.name }
        require(propertyNames.distinct().size == propertyNames.size) {
            "Tool schema contains duplicate property names"
        }
        require(requiredProperties.all { it in propertyNames }) {
            "Tool schema marks an unknown property as required"
        }

        return buildJsonObject {
            if (includeDialect) put("\$schema", DIALECT)
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    properties.sortedBy { it.name }.forEach { property ->
                        put(property.name, property.type.toJsonSchema().withDescription(property.description))
                    }
                },
            )
            requiredProperties.distinct().sorted().takeIf { it.isNotEmpty() }?.let { required ->
                put("required", JsonArray(required.map(::JsonPrimitive)))
            }
            put("additionalProperties", additionalProperties)
        }
    }

    private fun typeSchema(type: String): JsonObject = buildJsonObject { put("type", type) }

    private fun JsonObject.withDescription(description: String): JsonObject =
        buildJsonObject {
            entries.forEach { (key, value) -> put(key, value) }
            put("description", description)
        }
}
