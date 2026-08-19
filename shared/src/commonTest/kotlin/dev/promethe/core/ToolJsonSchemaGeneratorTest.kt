package dev.promethe.core

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolJsonSchemaGeneratorTest {
    @Test
    fun `generates rich draft 2020-12 schemas without degrading types`() {
        val descriptor =
            ToolDescriptor(
                name = "rich_tool",
                description = "Rich schema",
                requiredParameters =
                    listOf(
                        parameter(
                            "payload",
                            ToolParameterType.Object(
                                properties =
                                    listOf(
                                        parameter("tags", ToolParameterType.List(ToolParameterType.String)),
                                        parameter("mode", ToolParameterType.Enum(arrayOf("KeepCase", "UPPER"))),
                                        parameter("count", ToolParameterType.Integer),
                                    ),
                                requiredProperties = listOf("mode", "count"),
                            ),
                        ),
                    ),
                optionalParameters =
                    listOf(
                        parameter(
                            "nullable",
                            ToolParameterType.AnyOf(
                                arrayOf(
                                    parameter("value", ToolParameterType.String),
                                    parameter("none", ToolParameterType.Null),
                                ),
                            ),
                        ),
                    ),
            )

        val schema = ToolJsonSchemaGenerator.inputSchema(descriptor)

        assertEquals(ToolJsonSchemaGenerator.DIALECT, schema["\$schema"]?.jsonPrimitive?.content)
        assertEquals(listOf("nullable", "payload"), schema["properties"]!!.jsonObject.keys.toList())
        assertEquals(listOf("payload"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonPrimitive(false), schema["additionalProperties"])

        val payload = schema["properties"]!!.jsonObject["payload"]!!.jsonObject
        assertEquals(listOf("count", "mode", "tags"), payload["properties"]!!.jsonObject.keys.toList())
        assertEquals(listOf("count", "mode"), payload["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonPrimitive(false), payload["additionalProperties"])
        assertEquals(
            listOf("KeepCase", "UPPER"),
            payload["properties"]!!.jsonObject["mode"]!!.jsonObject["enum"]!!.jsonArray.map {
                it.jsonPrimitive.content
            },
        )
        assertEquals(
            "string",
            payload["properties"]!!.jsonObject["tags"]!!.jsonObject["items"]!!.jsonObject["type"]
                ?.jsonPrimitive
                ?.content,
        )

        val nullable = schema["properties"]!!.jsonObject["nullable"]!!.jsonObject
        assertEquals(
            listOf("string", "null"),
            nullable["anyOf"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content },
        )
    }

    @Test
    fun `uses typed additional properties when provided`() {
        val schema =
            ToolJsonSchemaGenerator.parameterSchema(
                ToolParameterType.Object(
                    properties = emptyList(),
                    additionalProperties = true,
                    additionalPropertiesType = ToolParameterType.String,
                ),
            )

        assertEquals("string", schema["additionalProperties"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `canonicalizes property and required ordering`() {
        val alpha = parameter("alpha", ToolParameterType.String)
        val zeta = parameter("zeta", ToolParameterType.Boolean)
        val first =
            ToolDescriptor(
                name = "ordered",
                description = "Ordered",
                requiredParameters = listOf(zeta, alpha),
            )
        val second =
            ToolDescriptor(
                name = "ordered",
                description = "Ordered",
                requiredParameters = listOf(alpha, zeta),
            )

        assertEquals(
            ToolJsonSchemaGenerator.inputSchema(first),
            ToolJsonSchemaGenerator.inputSchema(second),
        )
    }

    private fun parameter(
        name: String,
        type: ToolParameterType,
    ) = ToolParameterDescriptor(name = name, description = "$name description", type = type)
}
