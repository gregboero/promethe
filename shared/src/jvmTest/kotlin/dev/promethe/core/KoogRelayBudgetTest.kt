package dev.promethe.core

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

class KoogRelayBudgetTest {
    @Test fun `HTTP retry is denied and ambiguous usage keeps its reservation`() =
        fixture { store, directory ->
            var sends = 0
            CampaignKoogRelay(store, "private-key", directory) {
                sends++
                CampaignHttpResponse(503, "private-provider-error")
            }.use { relay ->
                relay.arm("first")
                assertEquals(400, send(relay))
                assertEquals(400, send(relay))
                assertEquals(1, sends)
                assertEquals(1, relay.blocked)
                val receipt = Files.list(directory).use { files -> Json.parseToJsonElement(Files.readString(files.filter { it.fileName.toString().startsWith("request-") }.findFirst().orElseThrow())).jsonObject }
                assertEquals("reserved_uncertain", receipt["accountingState"]?.jsonPrimitive?.content)
                assertFalse(receipt.toString().contains("private"))
                val reservation = receipt.getValue("reservationMicroUsd").jsonPrimitive.long
                assertFalse(store.reserve("still-charged", 5_000_001 - reservation))
            }
        }

    @Test fun `exhausted shared budget never calls upstream`() =
        fixture { store, directory ->
            assertTrue(store.reserve("existing", 5_000_000))
            CampaignKoogRelay(store, "private-key", directory) { error("must never send") }.use { relay ->
                relay.arm("blocked")
                assertEquals(400, send(relay))
                assertEquals(0, relay.sends)
            }
        }

    private fun send(relay: CampaignKoogRelay): Int =
        HttpClient.newHttpClient().use { client ->
            client.send(HttpRequest.newBuilder(URI("${relay.baseUrl}/v1/responses")).POST(HttpRequest.BodyPublishers.ofString("""{"model":"gpt-5.6-terra","max_output_tokens":4096,"reasoning":{"effort":"none"},"store":false,"parallel_tool_calls":false,"input":[]}""")).build(), HttpResponse.BodyHandlers.ofString()).statusCode()
        }

    private fun fixture(block: (HarnessStore, Path) -> Unit) {
        val directory = Files.createTempDirectory("koog-relay-budget")
        try {
            block(HarnessStore(directory.resolve("budget.sqlite")), directory)
        } finally {
            check(directory.toAbsolutePath().startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()))
            directory.toFile().deleteRecursively()
        }
    }
}
