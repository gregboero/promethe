package dev.promethe.core

import dev.promethe.api.ToolCallOrigin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class ToolInvocationContextTest {
    @Test
    fun `tool invocation is available only inside its coroutine context`() =
        runBlocking {
            assertNull(currentToolInvocation())
            val request = ToolExecutionRequest("codex_delegate", buildJsonObject {}, "session-42", ToolCallOrigin.A2A)
            withContext(ToolInvocationContext(request)) {
                assertEquals("session-42", currentToolInvocation()?.sessionId)
            }
            assertNull(currentToolInvocation())
        }

    @Test
    fun `relative paths are scoped to the active project workspace`() {
        assertEquals("projects/project-1", scopePathToWorkspace(".", "projects/project-1"))
        assertEquals("projects/project-1/src/Main.kt", scopePathToWorkspace("src/Main.kt", "projects/project-1"))
        assertEquals("C:\\selected\\file.txt", scopePathToWorkspace("C:\\selected\\file.txt", "projects/project-1"))
        assertFailsWith<IllegalArgumentException> {
            scopePathToWorkspace("../other-project", "projects/project-1")
        }
    }
}
