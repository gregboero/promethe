package dev.promethe.core.tools.media

import dev.promethe.core.providers.CapabilityRouter
import dev.promethe.core.providers.ProviderRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class VideoToolsTest {
    private val emptyApiKeys: Map<String, String> = emptyMap()
    private val emptyRouter = CapabilityRouter(ProviderRegistry(emptyApiKeys))

    @Test
    fun testVideoGenerateError() =
        runTest {
            val client = HttpClient(MockEngine { _ -> respond("") })
            val tool = VideoGenerateTool(client, emptyApiKeys, emptyRouter, "build/tmp/video")

            // When no API keys are present, the router returns NotConfigured
            val result = tool.execute(VideoGenerateArgs(prompt = "sunset", duration = 4))
            assertTrue(result.contains("Aucun provider configuré"), "Should return not-configured message: $result")
        }

    @Test
    fun testVideoAnalyzeError() =
        runTest {
            val client = HttpClient(MockEngine { _ -> respond("") })
            val tool = VideoAnalyzeTool(client, emptyApiKeys, emptyRouter)

            val result = tool.execute(VideoAnalyzeArgs(url = "https://example.com/video.mp4"))
            assertTrue(result.contains("Aucun provider configuré"), "Should return not-configured message: $result")
        }
}
