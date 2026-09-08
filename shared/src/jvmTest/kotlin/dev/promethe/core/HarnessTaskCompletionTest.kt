package dev.promethe.core

import kotlin.test.*

class HarnessTaskCompletionTest {
    @Test fun `completion requires observed pages and exact integer array shape`() {
        assertEquals("empty_final_response", HarnessTaskCompletion.assess(" ", emptyList(), 2).reason)
        assertEquals("missing_page_reads", HarnessTaskCompletion.assess("[1,2]", listOf(0), 2).reason)
        assertEquals("duplicate_page_read", HarnessTaskCompletion.assess("[1,2]", listOf(0, 0, 1), 2).reason)
        assertEquals("unexpected_page_read", HarnessTaskCompletion.assess("[1,2]", listOf(0, 1, 2), 2).reason)
        assertEquals("invalid_answer_format", HarnessTaskCompletion.assess("done", listOf(0, 1), 2).reason)
        assertEquals("answer_count_mismatch", HarnessTaskCompletion.assess("[1]", listOf(0, 1), 2).reason)
        for (text in listOf("[1,\"2\"]", "[1,null]", "[1,2.5]", "[1,{}]")) {
            assertEquals("non_integer_answer", HarnessTaskCompletion.assess(text, listOf(0, 1), 2).reason)
        }
    }

    @Test fun `completion does not pretend to establish answer correctness`() {
        assertTrue(HarnessTaskCompletion.assess("[999,-12]", listOf(1, 0), 2).complete)
        assertEquals("task_missing_page_reads", assertFailsWith<AgentExecutionException> { HarnessTaskCompletion.requireComplete("[1,2]", listOf(0), 2) }.code)
    }
}
