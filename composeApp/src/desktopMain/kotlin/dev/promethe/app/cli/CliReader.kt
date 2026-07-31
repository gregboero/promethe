package dev.promethe.app.cli

import org.jline.reader.Candidate
import org.jline.reader.Completer
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.ParsedLine
import org.jline.terminal.TerminalBuilder

/**
 * CliReader — JLine3 wrapper for the Prométhé CLI REPL.
 *
 * Provides:
 * - Tab autocomplete for commands
 * - Persistent history (~/.promethe/cli_history)
 * - Colored prompt with turn counter
 * - Fallback to readlnOrNull() if JLine3 fails
 */
class CliReader(
    commands: List<String> = DEFAULT_COMMANDS,
) {
    private val jlineTerminal: org.jline.terminal.Terminal?
    private val reader: LineReader?
    private var turnCount = 0

    init {
        var terminal: org.jline.terminal.Terminal? = null
        var lineReader: LineReader? = null
        try {
            terminal = TerminalBuilder.builder()
                .system(true)
                .dumb(false)
                .build()

            val historyFile = java.nio.file.Path.of(
                System.getProperty("user.home"),
                ".promethe",
                "cli_history",
            )
            // Create parent directory if needed
            historyFile.parent?.let { java.nio.file.Files.createDirectories(it) }

            lineReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(CommandCompleter(commands))
                .variable(LineReader.HISTORY_FILE, historyFile)
                .option(LineReader.Option.AUTO_FRESH_LINE, true)
                .option(LineReader.Option.HISTORY_BEEP, false)
                .build()
        } catch (_: Exception) {
            // Fallback: no JLine3 (CI, pipe, Windows without conpty...)
            terminal = null
            lineReader = null
        }
        jlineTerminal = terminal
        reader = lineReader
    }

    /**
     * Read a line with colored prompt and autocomplete.
     * Returns null on EOF (Ctrl+D).
     */
    fun readLine(): String? {
        turnCount++
        val prompt = "\u001B[34m[$turnCount] You: \u001B[0m"

        return if (reader != null) {
            try {
                reader.readLine(prompt)
            } catch (_: org.jline.reader.UserInterruptException) {
                null // Ctrl+C
            } catch (_: org.jline.reader.EndOfFileException) {
                null // Ctrl+D
            }
        } else {
            print(prompt)
            readlnOrNull()
        }
    }

    /** Close JLine3 terminal cleanly. */
    fun close() {
        try {
            reader?.history?.save()
            jlineTerminal?.close()
        } catch (_: Exception) {
            // ignore
        }
    }

    /**
     * JLine3 Completer — autocomplete for Prométhé commands.
     */
    private class CommandCompleter(
        private val commands: List<String>,
    ) : Completer {
        override fun complete(
            reader: LineReader,
            line: ParsedLine,
            candidates: MutableList<Candidate>,
        ) {
            val word = line.word().lowercase()
            commands
                .filter { it.startsWith(word) }
                .forEach { cmd ->
                    candidates.add(
                        Candidate(
                            cmd, // value
                            cmd, // display
                            null, // group
                            DESCRIPTIONS[cmd], // description
                            null, // suffix
                            null, // key
                            true, // complete
                        ),
                    )
                }
        }
    }

    companion object {
        val DEFAULT_COMMANDS = listOf(
            "status",
            "sessions",
            "tools",
            "model",
            "clear",
            "help",
            "exit",
            "quit",
        )

        private val DESCRIPTIONS = mapOf(
            "status" to "System status",
            "sessions" to "Recent sessions",
            "tools" to "Available tools",
            "model" to "Active model",
            "clear" to "Clear screen",
            "help" to "Show help",
            "exit" to "Exit Prométhé",
            "quit" to "Exit Prométhé",
        )
    }
}
