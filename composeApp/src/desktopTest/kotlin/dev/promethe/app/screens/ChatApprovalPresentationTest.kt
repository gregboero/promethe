package dev.promethe.app.screens

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatApprovalPresentationTest {
    @Test
    fun `pending approvals are filtered strictly by session`() {
        val response =
            Json.parseToJsonElement(
                """
                {
                  "pending": [
                    {
                      "id": "approval-current",
                      "toolName": "shell",
                      "args": "{\"command\":\"pwd\"}",
                      "sessionId": "session-current",
                      "persistentAllowed": false,
                      "createdAt": 123
                    },
                    {
                      "id": "approval-other",
                      "toolName": "write_file",
                      "args": "{\"path\":\"notes.txt\"}",
                      "sessionId": "session-other",
                      "createdAt": 456
                    }
                  ]
                }
                """.trimIndent(),
            ).jsonObject

        val approvals = pendingChatApprovalsForSession(response, "session-current")

        assertEquals(1, approvals.size)
        assertEquals("approval-current", approvals.single().id)
        assertEquals("shell", approvals.single().toolName)
        assertEquals("{\"command\":\"pwd\"}", approvals.single().arguments)
        assertEquals(false, approvals.single().persistentAllowed)
    }

    @Test
    fun `configuration approvals expose the persistent choice`() {
        val response =
            Json.parseToJsonElement(
                """
                {
                  "pending": [{
                    "id": "approval-config",
                    "toolName": "discord_policy",
                    "args": "{\"action\":\"listen_channel\",\"targetId\":\"123\"}",
                    "sessionId": "session-current",
                    "persistentAllowed": true,
                    "createdAt": 123
                  }]
                }
                """.trimIndent(),
            ).jsonObject

        assertEquals(true, pendingChatApprovalsForSession(response, "session-current").single().persistentAllowed)
    }

    @Test
    fun `malformed approvals are ignored`() {
        val response =
            Json.parseToJsonElement(
                """
                {
                  "pending": [
                    {"toolName": "shell", "sessionId": "session-current"},
                    {"id": "missing-tool", "sessionId": "session-current"},
                    {"id": "blank-tool", "toolName": "", "sessionId": "session-current"}
                  ]
                }
                """.trimIndent(),
            ).jsonObject

        assertEquals(emptyList(), pendingChatApprovalsForSession(response, "session-current"))
    }
}
