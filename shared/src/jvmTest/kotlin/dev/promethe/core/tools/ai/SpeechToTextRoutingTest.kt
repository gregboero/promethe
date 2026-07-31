package dev.promethe.core.tools.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * speech_to_text provider routing: only OpenAI-compatible endpoints are
 * implemented — anything else must refuse explicitly instead of silently
 * hitting Whisper with the wrong credentials.
 */
class SpeechToTextRoutingTest {
    private val tmp = System.getProperty("java.io.tmpdir")

    @Test
    fun `unsupported provider refuses explicitly`() =
        runTest {
            val tool =
                SpeechToTextTool(
                    httpClient = HttpClient(),
                    apiKeys = mapOf("openai" to "sk-test"),
                    workDir = tmp,
                    sttConfig = SttConfig(provider = "deepgram", apiKey = "dg-key"),
                )
            val result = tool.execute(SpeechToTextArgs(path = "nope.wav"))
            assertTrue(result.startsWith("[ERROR] STT provider 'deepgram'"), "Got: $result")
        }

    @Test
    fun `default config without api key reports missing key`() =
        runTest {
            val tool =
                SpeechToTextTool(
                    httpClient = HttpClient(),
                    apiKeys = emptyMap(),
                    workDir = tmp,
                )
            val result = tool.execute(SpeechToTextArgs(path = "nope.wav"))
            assertTrue(result.contains("No API key configured"), "Got: $result")
        }

    @Test
    fun `litellm provider is accepted as openai-compatible`() =
        runTest {
            val tool =
                SpeechToTextTool(
                    httpClient = HttpClient(),
                    apiKeys = emptyMap(),
                    workDir = tmp,
                    sttConfig = SttConfig(provider = "litellm", baseUrl = "http://localhost:4000", apiKey = "sk-x"),
                )
            // Passes the provider gate; fails later on the missing file — proving routing accepted it.
            val result = tool.execute(SpeechToTextArgs(path = "definitely-missing.wav"))
            assertTrue(result.contains("File not found"), "Got: $result")
        }

    @Test
    fun `transcription uses the injected HTTP client and keeps the key out of process arguments`() =
        runTest {
            val workspace = Files.createTempDirectory("promethe-stt")
            val audio = workspace.resolve("sample.wav")
            Files.write(audio, byteArrayOf(1, 2, 3))
            var authorization = ""
            val client =
                HttpClient(
                    MockEngine { request ->
                        authorization = request.headers[HttpHeaders.Authorization].orEmpty()
                        respond("{\"text\":\"transcribed\"}", HttpStatusCode.OK)
                    },
                )
            val tool =
                SpeechToTextTool(
                    httpClient = client,
                    apiKeys = emptyMap(),
                    workDir = workspace.toString(),
                    sttConfig = SttConfig(provider = "openai", baseUrl = "https://stt.test", apiKey = "secret"),
                )

            val result = tool.execute(SpeechToTextArgs(path = "sample.wav"))

            assertEquals("transcribed", result)
            assertEquals("Bearer secret", authorization)
        }
}
