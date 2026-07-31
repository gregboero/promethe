package dev.promethe.api.a2ui

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class A2UISerializationTest {
    private val sampleNode = UiNode(
        type = "card",
        id = "user-card",
        props = mapOf(
            "elevation" to JsonPrimitive(4),
            "title" to JsonPrimitive("User Info"),
        ),
        bindings = mapOf("subtitle" to "user.email"),
        handlers = mapOf(
            "onTap" to ActionDef(
                action = "view_profile",
                params = mapOf("userId" to "123"),
            ),
        ),
        children = listOf(
            UiNode(
                type = "text",
                props = mapOf("style" to JsonPrimitive("headline")),
                bindings = mapOf("text" to "user.name"),
            ),
            UiNode(
                type = "button",
                props = mapOf("label" to JsonPrimitive("Edit")),
                handlers = mapOf(
                    "onTap" to ActionDef(action = "edit_user"),
                ),
            ),
        ),
    )

    @Test
    fun jsonRoundTrip() {
        val json = Json { prettyPrint = false }
        val encoded = json.encodeToString(sampleNode)
        val decoded = json.decodeFromString<UiNode>(encoded)
        assertEquals(sampleNode, decoded)
    }

    @Test
    fun protobufRoundTrip() {
        // ProtoBuf can't handle Map<String, JsonElement> (polymorphic),
        // so test with bindings/handlers only (Map<String, String>)
        val simpleNode = UiNode(
            type = "button",
            id = "btn-1",
            bindings = mapOf("label" to "user.name"),
            handlers = mapOf("onTap" to ActionDef(action = "click", params = mapOf("id" to "1"))),
            children = listOf(UiNode(type = "text", id = "txt-1")),
        )
        val encoded = ProtoBuf.encodeToByteArray(simpleNode)
        val decoded = ProtoBuf.decodeFromByteArray<UiNode>(encoded)
        assertEquals(simpleNode.type, decoded.type)
        assertEquals(simpleNode.id, decoded.id)
        assertEquals(simpleNode.bindings, decoded.bindings)
        assertEquals(simpleNode.children.size, decoded.children.size)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun protobufIsSmallerThanJson() {
        val simpleNode = UiNode(
            type = "card",
            id = "card-1",
            bindings = mapOf("title" to "data.title", "subtitle" to "data.subtitle"),
            handlers = mapOf("onTap" to ActionDef(action = "open", params = mapOf("target" to "/detail"))),
            children = listOf(
                UiNode(type = "text", id = "t1", bindings = mapOf("text" to "data.body")),
                UiNode(type = "button", id = "b1"),
            ),
        )
        val json = Json { prettyPrint = false }
        val jsonBytes = json.encodeToString(simpleNode).encodeToByteArray()
        val protoBytes = ProtoBuf.encodeToByteArray(simpleNode)
        println("JSON: ${jsonBytes.size} bytes, ProtoBuf: ${protoBytes.size} bytes")
        assertTrue(
            protoBytes.size < jsonBytes.size,
            "ProtoBuf (${protoBytes.size}) should be smaller than JSON (${jsonBytes.size})",
        )
    }

    @Test
    fun actionDefRoundTrip() {
        val action = ActionDef(
            action = "submit_form",
            params = mapOf("formId" to "contact", "redirect" to "/thanks"),
        )
        val json = Json.encodeToString(action)
        val decoded = Json.decodeFromString<ActionDef>(json)
        assertEquals(action, decoded)
    }

    @Test
    fun emptyNodeRoundTrip() {
        val minimal = UiNode(type = "spacer")
        val json = Json.encodeToString(minimal)
        val decoded = Json.decodeFromString<UiNode>(json)
        assertEquals(minimal, decoded)
        assertEquals("", decoded.id)
        assertEquals(emptyMap(), decoded.props)
        assertEquals(emptyList(), decoded.children)
    }
}
