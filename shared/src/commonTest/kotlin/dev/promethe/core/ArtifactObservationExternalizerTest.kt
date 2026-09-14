package dev.promethe.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtifactObservationExternalizerTest {
    @Test
    fun `small observations remain inline`() =
        runTest {
            val store = RecordingArtifactStore()
            val result = ArtifactObservationExternalizer(store, maxInlineBytes = 32).externalize("small", null, null, null, "test")

            assertEquals("small", result.text)
            assertNull(result.artifact)
            assertNull(store.lastWrite)
        }

    @Test
    fun `large unicode observations are stored exactly and rendered as a compact reference`() =
        runTest {
            val store = RecordingArtifactStore()
            val content = "debut-" + "é".repeat(40) + "-fin"
            val result =
                ArtifactObservationExternalizer(store, maxInlineBytes = 16).externalize(
                    content = content,
                    runId = "run-1",
                    stepId = "step-1",
                    intentId = "intent-1",
                    toolName = "web_search",
                )

            assertContentEquals(content.encodeToByteArray(), store.lastWrite?.content)
            assertEquals("run-1", store.lastWrite?.runId)
            assertEquals(RECORDING_HASH, result.artifact?.hash)
            assertTrue(result.text.contains("artifact://sha256/$RECORDING_HASH"))
            assertTrue(result.text.contains("bytes: ${content.encodeToByteArray().size}"))
        }

    @Test
    fun `artifact reader validates and bounds requested segments`() =
        runTest {
            val content = "0123456789".encodeToByteArray()
            val store = RecordingArtifactStore(content)
            val tool = ArtifactReadTool(store)

            val segment = tool.execute(ArtifactReadArgs(RECORDING_HASH, offset = 3, maxBytes = 4))

            assertTrue(segment.endsWith("\n3456"))
            assertTrue(segment.contains("bytes=3..7/10"))
            assertFailsWith<IllegalArgumentException> {
                tool.execute(ArtifactReadArgs("../escape", offset = 0, maxBytes = 4))
            }
            assertFailsWith<IllegalArgumentException> {
                tool.execute(ArtifactReadArgs(RECORDING_HASH, offset = 0, maxBytes = 4_097))
            }
        }

    private class RecordingArtifactStore(
        private val readableContent: ByteArray? = null,
    ) : ArtifactStore {
        var lastWrite: ArtifactWriteRequest? = null

        override suspend fun put(request: ArtifactWriteRequest): ArtifactReference {
            lastWrite = request
            return ArtifactReference(
                hash = RECORDING_HASH,
                uri = "artifact://sha256/$RECORDING_HASH",
                sizeBytes = request.content.size.toLong(),
                mediaType = request.mediaType,
            )
        }

        override suspend fun read(hash: String): ByteArray? {
            assertEquals(RECORDING_HASH, hash)
            return readableContent
        }
    }

    companion object {
        private const val RECORDING_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
