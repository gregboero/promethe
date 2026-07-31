package dev.promethe.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpStdioTransportTest {
    @Test
    fun `stdio fails closed until the sandbox supports persistent sessions`() =
        runTest {
            val transport = McpStdioTransport(command = "npx", env = mapOf("API_KEY" to "secret"))

            val error = assertFailsWith<McpStdioUnavailableException> { transport.initialize() }

            assertEquals(McpStdioUnavailableReason.SANDBOX_PROTOCOL_V2_REQUIRED, error.reason)
            assertTrue(error.message.orEmpty().contains("SANDBOX_PROTOCOL_V2_REQUIRED"))
            assertFalse(transport.isRunning)
        }
}
