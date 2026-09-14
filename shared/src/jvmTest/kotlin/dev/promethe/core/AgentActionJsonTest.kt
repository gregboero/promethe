package dev.promethe.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.*

class AgentActionJsonTest {
    @Test fun `trailing model text and fences do not hide a complete action`() {
        val action = """{"action":{"tool_name":"json_query","args":{"page":3}}}"""
        for (text in listOf(action, "$action trailing marker", "```json\n$action\n```", "Arguments {page}:\n```json\n$action\n```", "Read next:\n$action\nDone")) {
            assertEquals(action, AgentActionJson.extract(text))
        }
    }

    @Test fun `braces quotes and escapes inside generated source preserve the full object`() {
        val source = "return '} { \\\" quoted';"
        val action = buildJsonObject {
            putJsonObject("action") {
                put("tool_name", "harness_propose")
                putJsonObject("args") { put("source", source) }
            }
        }.toString()
        assertEquals(action, AgentActionJson.extract("$action suffix"))
        assertNull(AgentActionJson.extract(action.dropLast(1)))
        assertNull(AgentActionJson.extract("Only text"))
    }
}
