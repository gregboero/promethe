package dev.promethe.gateway

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.typeToken
import dev.promethe.api.voice.ToolSchema
import dev.promethe.core.McpProtocol
import dev.promethe.core.SecureToolExecutor
import dev.promethe.core.ToolJsonSchemaGenerator
import dev.promethe.core.ToolRegistry
import dev.promethe.gateway.mcp.McpToolExporter
import dev.promethe.gateway.voice.ToolSchemaExporter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ToolSchemaParityTest {
    @AfterTest
    fun cleanup() =
        runTest {
            ToolRegistry.clear()
        }

    @Test
    fun `MCP and voice export the same canonical input schema`() =
        runTest {
            val tool = ReadFileTool()
            ToolRegistry.register(tool)

            val mcpResponse =
                McpToolExporter(secureToolExecutor = SecureToolExecutor { "unused" }).dispatch(
                    request =
                        buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("id", 1)
                            put("method", "tools/list")
                        },
                    protocolVersion = McpProtocol.MODERN_VERSION,
                )
            val mcpSchema =
                mcpResponse!!["result"]!!.jsonObject["tools"]!!.jsonArray
                    .single { it.jsonObject["name"]!!.jsonPrimitive.content == tool.name }
                    .jsonObject["inputSchema"]!!.jsonObject
            val voiceSchema = ToolSchemaExporter.exportFromRegistry().single { it.name == tool.name }.parameters

            assertEquals(ToolJsonSchemaGenerator.inputSchema(tool.descriptor), mcpSchema)
            assertEquals(mcpSchema, voiceSchema)

            val exportedSchemas = ToolSchemaExporter.exportFromRegistry()
            val openAiSchema =
                ToolSchemaExporter.toOpenAITools(exportedSchemas)
                    .single { it.jsonObject["name"]!!.jsonPrimitive.content == tool.name }
                    .jsonObject["parameters"]!!.jsonObject
            val geminiSchema =
                ToolSchemaExporter.toGeminiTools(exportedSchemas)
                    .single()
                    .jsonObject["functionDeclarations"]!!.jsonArray
                    .single { it.jsonObject["name"]!!.jsonPrimitive.content == tool.name }
                    .jsonObject["parameters"]!!.jsonObject
            assertFalse("\$schema" in openAiSchema)
            assertFalse("\$schema" in geminiSchema)
        }

    @Test
    fun `Gemini receives its supported schema subset without degrading the canonical source`() {
        val descriptor =
            ToolDescriptor(
                name = "rich_voice_tool",
                description = "Rich voice tool",
                requiredParameters =
                    listOf(
                        parameter(
                            "payload",
                            ToolParameterType.Object(
                                properties =
                                    listOf(
                                        parameter("mode", ToolParameterType.Enum(arrayOf("KeepCase", "UPPER"))),
                                    ),
                                requiredProperties = listOf("mode"),
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
        val canonical = ToolJsonSchemaGenerator.inputSchema(descriptor)
        val schema = ToolSchema(descriptor.name, descriptor.description, canonical)

        val openAi =
            ToolSchemaExporter.toOpenAITools(listOf(schema)).single().jsonObject["parameters"]!!.jsonObject
        val gemini =
            ToolSchemaExporter.toGeminiTools(listOf(schema)).single().jsonObject["functionDeclarations"]!!
                .jsonArray.single().jsonObject["parameters"]!!.jsonObject

        assertEquals(JsonPrimitive(false), canonical["additionalProperties"])
        assertEquals(JsonPrimitive(false), openAi["additionalProperties"])
        assertFalse("additionalProperties" in gemini)
        val geminiPayload = gemini["properties"]!!.jsonObject["payload"]!!.jsonObject
        assertFalse("additionalProperties" in geminiPayload)
        assertEquals(
            listOf("KeepCase", "UPPER"),
            geminiPayload["properties"]!!.jsonObject["mode"]!!.jsonObject["enum"]!!.jsonArray.map {
                it.jsonPrimitive.content
            },
        )
        val geminiNullable = gemini["properties"]!!.jsonObject["nullable"]!!.jsonObject
        assertEquals("STRING", geminiNullable["type"]!!.jsonPrimitive.content)
        assertEquals(true, geminiNullable["nullable"]!!.jsonPrimitive.content.toBooleanStrict())
        assertFalse("anyOf" in geminiNullable)
    }

    @Serializable
    private data class ReadFileArgs(
        val path: String,
        val line: Int? = null,
    )

    private class ReadFileTool :
        SimpleTool<ReadFileArgs>(
            argsType = typeToken<ReadFileArgs>(),
            name = "read_file",
            description = "Read a file",
        ) {
        override suspend fun execute(args: ReadFileArgs): String = "unused"
    }

    private fun parameter(
        name: String,
        type: ToolParameterType,
    ) = ToolParameterDescriptor(name = name, description = "$name description", type = type)
}
