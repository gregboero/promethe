package dev.promethe.core

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import dev.promethe.db.MessageRow
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class NativeToolHistoryTest {
    private fun turn(
        id: String,
        result: String = "same",
    ) = PendingToolTurn(
        Message.Assistant(listOf(MessagePart.Reasoning(content = "", encrypted = "state-$id", id = "reason-$id"), MessagePart.Tool.Call(id = id, tool = "query", args = "{\"id\":\"$id\"}")), ResponseMetaInfo.Empty),
        id,
        "query",
        result,
    )

    private fun row(
        id: Int,
        role: String,
        text: String,
    ) = MessageRow(id, "session", role, text, id.toLong())

    @Test fun `compression preserves typed exchanges and mixed conversation with identical observations`() =
        runBlocking {
            val history = NativeToolHistory()
            history.record(3, turn("one"))
            history.record(6, turn("two", "Error: failed"))
            val rows = listOf(row(1, "user", "old"), row(2, "assistant", "old reply"), row(3, "system", "Observation: same"), row(4, "assistant", "explanation"), row(5, "user", "followup"), row(6, "system", "Observation: same"))
            val (messages, pending) = history.prepare(rows) { prefix ->
                assertEquals(rows.take(2).map { it.role to it.content }, prefix)
                listOf("user" to "compressed prefix")
            }
            val prompt = KoogLlmAdapter(AgentConfig()).buildPrompt("base", messages, pending)
            assertEquals(listOf(Message.Role.System, Message.Role.User, Message.Role.Assistant, null, Message.Role.Assistant, Message.Role.User, Message.Role.Assistant, null), prompt.messages.map { if (it.parts.any { part -> part is MessagePart.Tool.Result }) null else it.role })
            val parts = prompt.messages.flatMap { it.parts }
            assertEquals(listOf("one", "two"), parts.filterIsInstance<MessagePart.Tool.Call>().map { it.id })
            assertEquals(listOf("one", "two"), parts.filterIsInstance<MessagePart.Tool.Result>().map { it.id })
            assertEquals(listOf(false, true), parts.filterIsInstance<MessagePart.Tool.Result>().map { it.isError })
            assertEquals(listOf("state-one", "state-two"), parts.filterIsInstance<MessagePart.Reasoning>().map { it.encrypted })
        }

    @Test fun `filtered observations cannot reappear from typed history and state is execution local`() =
        runBlocking {
            val history = NativeToolHistory()
            history.record(2, turn("hidden"))
            history.record(3, turn("visible"))
            val visible = listOf(row(1, "user", "task"), row(3, "system", "Observation: same"))
            val (messages, pending) = history.prepare(visible) { it }
            val prompt = KoogLlmAdapter(AgentConfig()).buildPrompt("base", messages, pending)
            assertEquals(listOf("visible"), prompt.messages.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Call>().map { it.id })
            assertNull(NativeToolHistory().prepare(visible) { it }.second)
            assertNull(history.prepare(visible.take(1)) { it }.second)
        }
}
