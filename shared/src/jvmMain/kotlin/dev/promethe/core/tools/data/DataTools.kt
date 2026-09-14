package dev.promethe.core.tools.data

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import dev.promethe.core.SecureJvmOutboundHttpClient
import dev.promethe.core.SecureJvmWorkspaceFileReader
import dev.promethe.core.SecureJvmWorkspaceFileWriter
import dev.promethe.core.WorkspaceFileReader
import dev.promethe.core.WorkspaceFileWriter
import dev.promethe.core.WorkspacePathPolicy
import dev.promethe.core.sandbox.SandboxedCommandRunner
import dev.promethe.core.sandbox.renderCommandOutput
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.NoSuchFileException
import java.nio.file.Path

// ── Args ─────────────────────────────────────────────────────────────

@Serializable
data class JsonQueryArgs(
    @property:LLMDescription("JSON string to query.")
    val json: String,
    @property:LLMDescription("JQ-style path (e.g. '.data.items[0].name', '.users[*].email').")
    val query: String,
)

@Serializable
data class SqlQueryArgs(
    @property:LLMDescription("SQL query to execute (SELECT only for safety).")
    val query: String,
    @property:LLMDescription("Database path (SQLite file path). Defaults to the agent's database.")
    val database: String = "",
)

@Serializable
data class CsvArgs(
    @property:LLMDescription("Action: 'read', 'query', 'write'. Default 'read'.")
    val action: String = "read",
    @property:LLMDescription("File path for the CSV file.")
    val path: String,
    @property:LLMDescription("For 'write': CSV content to write. For 'query': column filter (e.g. 'name=John').")
    val data: String = "",
    @property:LLMDescription("Maximum rows to return for 'read'. Default 100.")
    val maxRows: Int = 100,
)

@Serializable
data class PdfReaderArgs(
    @property:LLMDescription("Path to the PDF file to read.")
    val path: String,
    @property:LLMDescription("Maximum pages to extract. Default 10.")
    val maxPages: Int = 10,
)

@Serializable
data class ApiCallArgs(
    @property:LLMDescription("HTTP URL to call.")
    val url: String,
    @property:LLMDescription("HTTP method: GET, POST, PUT, DELETE, PATCH. Default GET.")
    val method: String = "GET",
    @property:LLMDescription("Request body (JSON string). Optional.")
    val body: String = "",
    @property:LLMDescription("HTTP headers as 'Key: Value' lines. Optional.")
    val headers: String = "",
)

@Serializable
data class TodoArgs(
    @property:LLMDescription("Action: 'list', 'add', 'done', 'remove'. Default 'list'.")
    val action: String = "list",
    @property:LLMDescription("Task description (for 'add') or task ID (for 'done'/'remove').")
    val value: String = "",
)

@Serializable
data class NotesArgs(
    @property:LLMDescription("Action: 'list', 'get', 'set', 'delete'. Default 'list'.")
    val action: String = "list",
    @property:LLMDescription("Note key/title.")
    val key: String = "",
    @property:LLMDescription("Note content (for 'set' action).")
    val content: String = "",
)

// ── Tools ────────────────────────────────────────────────────────────

class JsonQueryTool :
    SimpleTool<JsonQueryArgs>(
        argsType = typeToken<JsonQueryArgs>(),
        name = "json_query",
        description = "Query a JSON string with a JQ-style path expression. Supports dot notation and array indexing.",
    ) {
    override suspend fun execute(args: JsonQueryArgs): String =
        try {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(args.json)
            val result = navigateJson(json, args.query)
            result.toString()
        } catch (e: Exception) {
            "[ERROR] ${e.message}"
        }

    private fun navigateJson(
        element: kotlinx.serialization.json.JsonElement,
        path: String,
    ): kotlinx.serialization.json.JsonElement {
        if (path.isBlank() || path == ".") return element
        val clean = path.removePrefix(".")
        val parts = clean.split(".").filter { it.isNotBlank() }
        var current = element
        for (part in parts) {
            val arrayMatch = Regex("""(\w+)\[(\d+|\*)]""").matchEntire(part)
            if (arrayMatch != null) {
                val key = arrayMatch.groupValues[1]
                val idx = arrayMatch.groupValues[2]
                val obj = (current as? kotlinx.serialization.json.JsonObject)?.get(key)
                    ?: return kotlinx.serialization.json.JsonPrimitive("null")
                val arr = obj as? kotlinx.serialization.json.JsonArray
                    ?: return kotlinx.serialization.json.JsonPrimitive("null")
                current = if (idx == "*") {
                    arr
                } else {
                    arr.getOrNull(idx.toInt()) ?: kotlinx.serialization.json.JsonPrimitive("null")
                }
            } else {
                current = (current as? kotlinx.serialization.json.JsonObject)?.get(part)
                    ?: return kotlinx.serialization.json.JsonPrimitive("null")
            }
        }
        return current
    }
}

class CsvTool(
    private val workDir: String,
    private val reader: WorkspaceFileReader,
    private val writer: WorkspaceFileWriter,
) : SimpleTool<CsvArgs>(
        argsType = typeToken<CsvArgs>(),
        name = "csv",
        description = "Read, write, or query CSV files.",
    ) {
    override suspend fun execute(args: CsvArgs): String {
        val file =
            WorkspacePathPolicy.resolve(workDir, args.path)
                ?: return "[ERROR] Access denied: path must stay inside the workspace"
        val relativePath = Path.of(workDir).toRealPath().relativize(file.toPath()).toString()
        return try {
            when (args.action.lowercase()) {
                "read" -> {
                    val lines = reader.read(relativePath).lines().take(args.maxRows.coerceAtLeast(0) + 1)
                    "Rows: ${(lines.size - 1).coerceAtLeast(0)}\n${lines.joinToString("\n")}"
                }

                "write" -> {
                    writer.write(relativePath, args.data)
                    "Written ${args.data.lines().size} lines to ${args.path}"
                }

                "query" -> {
                    val lines = reader.read(relativePath).lines()
                    if (lines.isEmpty()) return "Empty CSV."
                    val header = lines.first()
                    val cols = header.split(",").map { it.trim() }
                    val filterParts = args.data.split("=", limit = 2)
                    if (filterParts.size != 2) return "[ERROR] Query format: column=value"
                    val colIdx = cols.indexOfFirst { it.equals(filterParts[0].trim(), ignoreCase = true) }
                    if (colIdx < 0) return "[ERROR] Column '${filterParts[0]}' not found. Available: $cols"
                    val matches =
                        lines
                            .drop(1)
                            .filter { line ->
                                val cells = line.split(",")
                                cells
                                    .getOrNull(colIdx)
                                    ?.trim()
                                    ?.contains(filterParts[1].trim(), ignoreCase = true) == true
                            }.take(args.maxRows.coerceAtLeast(0))
                    "$header\n${matches.joinToString("\n")} (${matches.size} matches)"
                }

                else -> {
                    "[ERROR] Unknown action '${args.action}'. Use: read, write, query."
                }
            }
        } catch (_: NoSuchFileException) {
            "[ERROR] File not found: ${args.path}"
        } catch (e: Exception) {
            "[ERROR] Secure CSV access failed: ${e.message}"
        }
    }
}

class PdfReaderTool(
    private val workDir: String,
    private val sandboxRunner: SandboxedCommandRunner,
) : SimpleTool<PdfReaderArgs>(
        argsType = typeToken<PdfReaderArgs>(),
        name = "pdf_reader",
        description = "Extract text from PDF files (basic extraction, works for text-based PDFs).",
    ) {
    override suspend fun execute(args: PdfReaderArgs): String {
        val file =
            WorkspacePathPolicy.resolve(workDir, args.path)
                ?: return "[BLOCKED] PDF path must stay inside the workspace."
        if (!file.exists()) return "[ERROR] File not found: ${args.path}"
        if (!file.name.lowercase().endsWith(".pdf")) return "[ERROR] Not a PDF file."
        return try {
            val result =
                sandboxRunner.execute(
                    executable = "pdftotext",
                    arguments = listOf("-l", args.maxPages.coerceIn(1, 100).toString(), file.absolutePath, "-"),
                    workingDirectory = java.io.File(workDir).canonicalPath,
                    timeoutMillis = 30_000,
                )
            if (result.exitCode == 0 && result.errorCode == null) {
                val output = result.stdout
                val truncated = if (output.length > 10_000) output.take(10_000) + "\n--- [TRUNCATED] ---" else output
                truncated.ifBlank { "[INFO] PDF appears to be image-based (no extractable text)." }
            } else {
                result.renderCommandOutput()
            }
        } catch (e: Exception) {
            "[ERROR] PDF extraction failed: ${e.message}"
        }
    }
}

class ApiCallTool(
    private val httpClient: SecureJvmOutboundHttpClient,
) : SimpleTool<ApiCallArgs>(
        argsType = typeToken<ApiCallArgs>(),
        name = "api_call",
        description = "Make HTTP API calls (GET/POST/PUT/DELETE/PATCH) with custom headers and body.",
    ) {
    override suspend fun execute(args: ApiCallArgs): String =
        try {
            val method =
                when (args.method.uppercase()) {
                    "GET" -> HttpMethod.Get
                    "POST" -> HttpMethod.Post
                    "PUT" -> HttpMethod.Put
                    "DELETE" -> HttpMethod.Delete
                    "PATCH" -> HttpMethod.Patch
                    else -> return "[ERROR] Unsupported HTTP method '${args.method}'."
                }
            val headers =
                args.headers
                    .lines()
                    .filter { it.isNotBlank() }
                    .associate { line ->
                        val parts = line.split(":", limit = 2)
                        require(parts.size == 2 && parts[0].isNotBlank()) { "invalid HTTP header line" }
                        parts[0].trim() to parts[1].trim()
                    }
            val response =
                httpClient.requestFollowingRedirects(
                    method = method,
                    url = args.url,
                    headers = headers,
                    body = args.body.takeIf { it.isNotBlank() },
                )
            val truncated =
                if (response.body.length > 8000) {
                    response.body.take(8000) + "\n--- [TRUNCATED] ---"
                } else {
                    response.body
                }
            "[${response.status}] $truncated"
        } catch (e: Exception) {
            "[ERROR] HTTP request failed: ${e.message}"
        }
}

class TodoTool(
    private val reader: SecureJvmWorkspaceFileReader,
    private val writer: SecureJvmWorkspaceFileWriter,
) : SimpleTool<TodoArgs>(
        argsType = typeToken<TodoArgs>(),
        name = "todo",
        description = "Manage a persistent task/todo list (stored in workspace).",
    ) {
    override suspend fun execute(args: TodoArgs): String {
        return try {
            val todos = readOrEmpty(TODO_FILE).lines().filter { it.isNotEmpty() }.toMutableList()
            when (args.action.lowercase()) {
                "list" -> {
                    if (todos.isEmpty()) return "No tasks."
                    todos.mapIndexed { i, task -> "${i + 1}. $task" }.joinToString("\n")
                }

                "add" -> {
                    if (args.value.isBlank()) return "[ERROR] Task description required."
                    todos.add("[ ] ${args.value}")
                    writer.write(TODO_FILE, todos.joinToString("\n"))
                    "Added task #${todos.size}: ${args.value}"
                }

                "done" -> {
                    val index = args.value.toIntOrNull()?.minus(1) ?: return "[ERROR] Task number required."
                    if (index !in todos.indices) return "[ERROR] Invalid task number."
                    todos[index] = todos[index].replace("[ ]", "[x]")
                    writer.write(TODO_FILE, todos.joinToString("\n"))
                    "Marked task #${index + 1} as done."
                }

                "remove" -> {
                    val index = args.value.toIntOrNull()?.minus(1) ?: return "[ERROR] Task number required."
                    if (index !in todos.indices) return "[ERROR] Invalid task number."
                    val removed = todos.removeAt(index)
                    writer.write(TODO_FILE, todos.joinToString("\n"))
                    "Removed: $removed"
                }

                else -> {
                    "[ERROR] Unknown action '${args.action}'. Use: list, add, done, remove."
                }
            }
        } catch (e: Exception) {
            "[ERROR] Secure todo storage is unavailable: ${e.message}"
        }
    }

    private suspend fun readOrEmpty(path: String): String =
        try {
            reader.read(path)
        } catch (_: NoSuchFileException) {
            ""
        }

    private companion object {
        const val TODO_FILE = "todos.txt"
    }
}

class NotesTool(
    private val reader: SecureJvmWorkspaceFileReader,
    private val writer: SecureJvmWorkspaceFileWriter,
) : SimpleTool<NotesArgs>(
        argsType = typeToken<NotesArgs>(),
        name = "notes",
        description = "Persistent scratchpad for notes. CRUD operations with key-value storage.",
    ) {
    override suspend fun execute(args: NotesArgs): String {
        if (args.action.lowercase() != "list" && !isSafeNoteKey(args.key)) {
            return "[ERROR] Note key must contain only letters, digits, dot, underscore or dash."
        }
        return try {
            val notes = readState().notes.toMutableMap()
            when (args.action.lowercase()) {
                "list" -> {
                    if (notes.isEmpty()) "No notes." else "Notes:\n${notes.keys.sorted().joinToString("\n") { "- $it" }}"
                }

                "get" -> {
                    notes[args.key] ?: return "[ERROR] Note '${args.key}' not found."
                }

                "set" -> {
                    notes[args.key] = args.content
                    writer.write(NOTES_FILE, json.encodeToString(NotesState(notes)))
                    "Saved note '${args.key}' (${args.content.length} chars)."
                }

                "delete" -> {
                    if (notes.remove(args.key) == null) return "[ERROR] Note '${args.key}' not found."
                    writer.write(NOTES_FILE, json.encodeToString(NotesState(notes)))
                    "Deleted note '${args.key}'."
                }

                else -> {
                    "[ERROR] Unknown action '${args.action}'. Use: list, get, set, delete."
                }
            }
        } catch (e: Exception) {
            "[ERROR] Secure notes storage is unavailable: ${e.message}"
        }
    }

    private suspend fun readState(): NotesState =
        try {
            json.decodeFromString(reader.read(NOTES_FILE))
        } catch (_: NoSuchFileException) {
            NotesState()
        }

    private fun isSafeNoteKey(key: String): Boolean =
        key.length in 1..100 &&
            key !in setOf(".", "..") &&
            key.all { it.isLetterOrDigit() || it in setOf('.', '_', '-') }

    @Serializable
    private data class NotesState(
        val notes: Map<String, String> = emptyMap(),
    )

    private companion object {
        const val NOTES_FILE = "notes.json"
        val json = Json { ignoreUnknownKeys = false }
    }
}
