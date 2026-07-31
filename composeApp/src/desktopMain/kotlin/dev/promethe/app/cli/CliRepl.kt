package dev.promethe.app.cli

import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.terminal.Terminal
import dev.promethe.api.ChatEvent
import dev.promethe.app.network.A2AChatClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * CliRepl — interactive command-line REPL for Prométhé.
 *
 * Uses [A2AChatClient] (A2A JSON-RPC protocol) to communicate with the gateway,
 * ensuring the same execution pipeline as Desktop, WASM, and all other clients.
 *
 * Supports two modes:
 * - Embedded: gateway started in-process, connect to localhost
 * - Remote (--connect): connect to an existing gateway
 */
class CliRepl(
    private val gatewayUrl: String,
    private val credential: String = "",
) {
    private val t = Terminal()
    private val reader = CliReader()
    private val sessionId = "cli-${System.currentTimeMillis()}"

    fun run() {
        printBanner()

        val client = A2AChatClient(baseUrl = gatewayUrl, apiKey = credential)

        // ── REPL loop ──
        while (true) {
            val input = reader.readLine() ?: break
            if (input.isBlank()) continue

            // Handle special commands
            when (input.trim().lowercase()) {
                "exit", "quit" -> {
                    break
                }

                "clear" -> {
                    t.print("\u001B[2J\u001B[H")
                    continue
                }

                "help", "?" -> {
                    showHelp()
                    continue
                }

                "status" -> {
                    runBlocking { showStatus() }
                    continue
                }
            }

            // Send message to agent via A2A
            t.println(yellow("\n💭 Thinking..."))
            try {
                runBlocking {
                    client.sendMessageStreaming(sessionId, input).collect { event ->
                        renderEvent(event)
                    }
                }
            } catch (e: Exception) {
                t.println(red("❌ Error: ${e.message}"))
                logger.error(e) { "CLI execution error" }
            }
            t.println()
        }

        reader.close()
        t.println(gray("\n👋 Au revoir !"))
    }

    private fun renderEvent(event: ChatEvent) {
        when (event.type) {
            "thought" -> {
                event.content?.let { t.println(gray("  🧠 $it")) }
            }

            "action" -> {
                event.content?.let { t.println(magenta("  ⚡ $it")) }
            }

            "observation" -> {
                event.content?.let {
                    val preview = it.take(200).replace("\n", " ")
                    t.println(gray("  👁 $preview"))
                }
            }

            "response" -> {
                event.content?.let {
                    t.println()
                    t.println(green(it))
                }
            }

            "error" -> {
                event.content?.let { t.println(red("  ❌ $it")) }
            }

            else -> {
                // Status updates, artifacts, etc.
                event.content?.let { t.println(gray("  ℹ $it")) }
            }
        }
    }

    private suspend fun showStatus() {
        val httpClient = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO)
        try {
            val response: io.ktor.client.statement.HttpResponse =
                httpClient.get("$gatewayUrl/api/v1/status") {
                    if (credential.isNotBlank()) {
                        headers.append("Authorization", "Bearer $credential")
                    }
                }
            val body = response.bodyAsText()
            t.println(bold(yellow("\n📊 System Status")))
            t.println(yellow(body))
        } catch (e: Exception) {
            t.println(red("  Failed to fetch status: ${e.message}"))
        } finally {
            httpClient.close()
        }
    }

    private fun showHelp() {
        t.println()
        t.println(bold(blue("📖 Available Commands")))
        t.println()
        t.println(white("  Chat:"))
        t.println(gray("    <message>       Send a message to the agent"))
        t.println()
        t.println(white("  Information:"))
        t.println(gray("    status          System status"))
        t.println()
        t.println(white("  System:"))
        t.println(gray("    clear           Clear screen"))
        t.println(gray("    help / ?        This help"))
        t.println(gray("    exit / quit     Exit Prométhé"))
    }

    private fun printBanner() {
        t.println(
            bold(
                blue(
                    """
                    ╔═══════════════════════════════════════╗
                    ║        ⚡ Prométhé CLI                ║
                    ║   Connected to: $gatewayUrl
                    ╚═══════════════════════════════════════╝
                    """.trimIndent(),
                ),
            ),
        )
        t.println(gray("  Type 'help' for commands, 'exit' to quit\n"))
    }
}
