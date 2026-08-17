package dev.promethe.core

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import io.ktor.client.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

// ── Argument data classes ──────────────────────────────────────────

@Serializable
data class FileReadArgs(
    @property:LLMDescription("Relative path to the file to read.")
    val path: String,
)

@Serializable
data class FileWriteArgs(
    @property:LLMDescription("Relative path to the file to write.")
    val path: String,
    @property:LLMDescription("Text content to write to the file.")
    val content: String,
)

@Serializable
data class HttpFetchArgs(
    @property:LLMDescription("URL to fetch.")
    val url: String,
)

fun interface WorkspaceFileWriter {
    suspend fun write(
        relativePath: String,
        content: String,
    )
}

fun interface WorkspaceFileReader {
    suspend fun read(relativePath: String): String
}

fun interface WorkspaceDirectoryResolver {
    fun resolve(requestedPath: String): String
}

// ── Tool implementations ───────────────────────────────────────────

class FileReadTool(
    private val fs: FileSystem,
    private val basePath: Path,
    private val pathResolver: WorkspacePathResolver = LexicalWorkspacePathResolver,
    private val secureReader: WorkspaceFileReader? = null,
) : SimpleTool<FileReadArgs>(
        argsType = typeToken<FileReadArgs>(),
        name = "read_file",
        description = "Reads the content of a file in the workspace. Argument 'path' must be relative to the workspace.",
    ) {
    override suspend fun execute(args: FileReadArgs): String {
        val pathString = projectScopedPath(args.path)

        secureReader?.let { reader ->
            return try {
                reader.read(pathString)
            } catch (error: Exception) {
                "[ERROR] Failed to read file securely: ${error.message}"
            }
        }

        // Sécurité : évite le path traversal (KMP friendly sans dépendances système)
        val targetFile = pathResolver.resolve(basePath, pathString)
        if (targetFile == null) {
            return "[ERROR] Access denied: path must be relative and inside the workspace directory"
        }
        if (!fs.exists(targetFile)) {
            return "[ERROR] File not found: $pathString"
        }

        return try {
            fs.source(targetFile).buffer().use { it.readUtf8() }
        } catch (e: Exception) {
            "[ERROR] Failed to read file: ${e.message}"
        }
    }
}

class FileWriteTool(
    private val fs: FileSystem,
    private val basePath: Path,
    private val pathResolver: WorkspacePathResolver = LexicalWorkspacePathResolver,
    private val secureWriter: WorkspaceFileWriter? = null,
) : SimpleTool<FileWriteArgs>(
        argsType = typeToken<FileWriteArgs>(),
        name = "write_file",
        description = "Writes content to a file in the workspace atomically. Argument 'path' must be relative to the workspace.",
    ) {
    private val logger = Log.create("FileWriteTool")

    override suspend fun execute(args: FileWriteArgs): String {
        val pathString = projectScopedPath(args.path)
        val content = args.content

        secureWriter?.let { writer ->
            return try {
                writer.write(pathString, content)
                "File written successfully to $pathString"
            } catch (error: Exception) {
                "[ERROR] Failed to write file securely: ${error.message}"
            }
        }

        // Sécurité : évite le path traversal (KMP friendly sans dépendances système)
        val targetFile = pathResolver.resolve(basePath, pathString)
        if (targetFile == null) {
            return "[ERROR] Access denied: path must be relative and inside the workspace directory"
        }
        val parentDir = targetFile.parent
        if (parentDir != null && !fs.exists(parentDir)) {
            fs.createDirectories(parentDir)
        }

        val randomSuffix = kotlin.random.Random.nextLong().toString(16)
        val tempFile = targetFile.parent?.let { it / ".${targetFile.name}.$randomSuffix.tmp" } ?: ".${targetFile.name}.$randomSuffix.tmp".toPath()
        val sink = fs.sink(tempFile).buffer()
        return try {
            sink.writeUtf8(content)
            sink.close() // Close before moving
            fs.atomicMove(tempFile, targetFile)
            "File written successfully to $pathString"
        } catch (e: Exception) {
            try {
                sink.close()
            } catch (closeEx: Exception) {
                logger.warn(closeEx) { "Failed to close sink during error recovery" }
            }
            if (fs.exists(tempFile)) {
                try {
                    fs.delete(tempFile)
                } catch (deleteEx: Exception) {
                    logger.warn(deleteEx) { "Failed to delete temp file: $tempFile" }
                }
            }
            "[ERROR] Failed to write file: ${e.message}"
        }
    }
}

class HttpFetchTool(
    private val httpClient: HttpClient,
    private val urlPolicy: OutboundUrlPolicy = LexicalOutboundUrlPolicy,
    private val fetcher: OutboundHttpFetcher = KtorOutboundHttpFetcher(httpClient),
) : SimpleTool<HttpFetchArgs>(
        argsType = typeToken<HttpFetchArgs>(),
        name = "http_fetch",
        description = "Fetches the text content of a URL via HTTP GET.",
    ) {
    override suspend fun execute(args: HttpFetchArgs): String {
        return try {
            var currentUrl = args.url
            repeat(MAX_REDIRECTS + 1) { redirectCount ->
                urlPolicy.rejectionReason(currentUrl)?.let { reason ->
                    return "[BLOCKED] HTTP destination rejected: $reason"
                }
                val response = fetcher.get(currentUrl)
                if (response.status !in 300..399) return response.body
                if (redirectCount == MAX_REDIRECTS) return "[ERROR] Too many HTTP redirects"
                val location = response.location
                    ?: return "[ERROR] Redirect response omitted the Location header"
                currentUrl = urlPolicy.resolveRedirect(currentUrl, location)
            }
            "[ERROR] Too many HTTP redirects"
        } catch (e: Exception) {
            "[ERROR] Failed HTTP request: ${e.message}"
        }
    }

    private companion object {
        const val MAX_REDIRECTS = 5
    }
}

private class KtorOutboundHttpFetcher(
    private val httpClient: HttpClient,
) : OutboundHttpFetcher {
    override suspend fun get(url: String): OutboundHttpResponse {
        val response =
            httpClient.get(url) {
                timeout {
                    requestTimeoutMillis = 15_000
                    connectTimeoutMillis = 5_000
                }
            }
        return OutboundHttpResponse(
            status = response.status.value,
            location = response.headers[HttpHeaders.Location],
            body = response.bodyAsText(),
        )
    }
}
