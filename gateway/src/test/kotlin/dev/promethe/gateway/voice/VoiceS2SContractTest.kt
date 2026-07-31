package dev.promethe.gateway.voice

import dev.promethe.api.voice.VoiceSessionConfig
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class VoiceS2SContractTest {
    @Test
    fun `voice task surface exposes only promethe agent`() {
        val schemas = listOf(prometheAgentToolSchema())

        assertEquals(listOf(PROMETHE_AGENT_TOOL), schemas.map { it.name })
        val required = schemas.single().parameters.getValue("required").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("task"), required)
    }

    @Test
    fun `realtime defaults use current live models`() {
        val providers = VoiceProviderRegistry.allProviders()
        val openAI = providers.single { it.id == "openai_realtime" }
        val gemini = providers.single { it.id == "gemini_live" }

        assertEquals("gpt-realtime-2.1", openAI.defaultModels(VoiceCapability.S2S).first())
        assertEquals("gemini-3.1-flash-live-preview", gemini.defaultModels(VoiceCapability.S2S).single())
        assertFalse(gemini.defaultModels(VoiceCapability.S2S).any { "2.5" in it || "2.0" in it })
    }

    @Test
    fun `gemini live setup and tool response use current websocket contract`() {
        val client = HttpClient()
        try {
            val relay = GeminiLiveRelay(client, "test-key")
            val setup = Json.parseToJsonElement(
                relay.buildSetupMessage(
                    VoiceSessionConfig(systemInstructions = "Be concise"),
                    listOf(prometheAgentToolSchema()),
                ),
            ).jsonObject.getValue("setup").jsonObject

            assertEquals("models/gemini-3.1-flash-live-preview", setup.getValue("model").jsonPrimitive.content)
            assertTrue("inputAudioTranscription" in setup)
            assertTrue("outputAudioTranscription" in setup)
            assertFalse("input_audio_transcription" in setup)
            assertEquals(
                listOf("AUDIO"),
                setup.getValue("generationConfig").jsonObject
                    .getValue("responseModalities").jsonArray
                    .map { it.jsonPrimitive.content },
            )

            val response = Json.parseToJsonElement(
                relay.buildToolResponse("call-1", PROMETHE_AGENT_TOOL, "done"),
            ).jsonObject.getValue("toolResponse").jsonObject
                .getValue("functionResponses").jsonArray.single().jsonObject
            assertEquals("call-1", response.getValue("id").jsonPrimitive.content)
            assertEquals(PROMETHE_AGENT_TOOL, response.getValue("name").jsonPrimitive.content)
            assertEquals("done", response.getValue("response").jsonObject.getValue("output").jsonPrimitive.content)
        } finally {
            client.close()
        }
    }
}
