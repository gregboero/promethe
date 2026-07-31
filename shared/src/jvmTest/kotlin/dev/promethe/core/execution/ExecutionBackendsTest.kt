package dev.promethe.core.execution

import kotlin.test.*
import kotlinx.coroutines.test.runTest

class ExecutionBackendsTest {
    @Test
    fun testBackendFactoryCreatesProperBackends() {
        val singularity = ExecutionBackendFactory.create("singularity")
        assertTrue(singularity is SingularityBackend)
        assertEquals("singularity", singularity.name)

        val modal = ExecutionBackendFactory.create("modal")
        assertTrue(modal is ModalBackend)
        assertEquals("modal", modal.name)

        val daytona = ExecutionBackendFactory.create("daytona")
        assertTrue(daytona is DaytonaBackend)
        assertEquals("daytona", daytona.name)

        assertFailsWith<IllegalArgumentException> {
            ExecutionBackendFactory.create("invalid-backend-name")
        }
    }

    @Test
    fun testModalBackendErrorWhenUnconfigured() =
        runTest {
            val backend = ModalBackend(tokenId = "", tokenSecret = "")
            assertFalse(backend.isAvailable())
            val result = backend.execute("echo", listOf("hello"), 1000, 1000)
            assertTrue(
                result.contains("[ERROR] MODAL_TOKEN_ID and MODAL_TOKEN_SECRET not configured"),
                "Should return config error: $result",
            )
        }

    @Test
    fun testDaytonaBackendErrorWhenUnconfigured() =
        runTest {
            val backend = DaytonaBackend(apiKey = "")
            assertFalse(backend.isAvailable())
            val result = backend.execute("echo", listOf("hello"), 1000, 1000)
            assertTrue(result.contains("[ERROR] DAYTONA_API_KEY not configured"), "Should return config error: $result")
        }

    @Test
    fun testSingularityBackendFallback() =
        runTest {
            val backend = SingularityBackend(imagePath = "test.sif", bindPaths = listOf("/tmp"))
            // If singularity is not installed, it should return false
            assertFalse(backend.isAvailable())
        }
}
