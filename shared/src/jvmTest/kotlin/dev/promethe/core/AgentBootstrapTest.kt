package dev.promethe.core

import dev.promethe.db.DatabaseFactory
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class AgentBootstrapTest {
    @Test
    fun testAgentStackBootstrapping() =
        runTest {
            val credentials = CredentialsStore.Credentials(
                llmProvider = "ollama",
                llmModel = "llama3",
                llmApiKey = "test-key",
            )
            val stack = AgentBootstrap.create(credentials, DatabaseFactory.createInMemory())

            assertNotNull(stack.agent)
            assertNotNull(stack.database)
            assertNotNull(stack.llmAdapter)
            assertNotNull(stack.config)
            assertNotNull(stack.taskScheduler)
            assertNotNull(stack.hookManager)

            assertEquals("llama3", stack.config.modelName)
            assertEquals("ollama", stack.config.provider)
        }
}
