package dev.promethe.core

import io.ktor.http.Url
import okio.Path

fun interface WorkspacePathResolver {
    fun resolve(
        basePath: Path,
        requested: String,
    ): Path?
}

/** Portable fallback; JVM production uses [CanonicalWorkspacePathResolver]. */
object LexicalWorkspacePathResolver : WorkspacePathResolver {
    override fun resolve(
        basePath: Path,
        requested: String,
    ): Path? {
        val normalized = requested.replace('\\', '/')
        if (normalized.startsWith("/") || normalized.contains(":") || normalized.split('/').contains("..")) return null
        return basePath / requested
    }
}

interface OutboundUrlPolicy {
    suspend fun rejectionReason(url: String): String?

    fun resolveRedirect(
        currentUrl: String,
        location: String,
    ): String
}

data class OutboundHttpResponse(
    val status: Int,
    val location: String?,
    val body: String,
)

fun interface OutboundHttpFetcher {
    suspend fun get(url: String): OutboundHttpResponse
}

/** Portable baseline; JVM production additionally resolves and checks every IP. */
object LexicalOutboundUrlPolicy : OutboundUrlPolicy {
    override suspend fun rejectionReason(url: String): String? =
        runCatching {
            val parsed = Url(url)
            when {
                parsed.protocol.name !in setOf("http", "https") -> "only HTTP and HTTPS are allowed"
                parsed.host.lowercase() in setOf("localhost", "localhost.localdomain") -> "loopback destinations are blocked"
                parsed.host == "127.0.0.1" || parsed.host == "::1" -> "loopback destinations are blocked"
                else -> null
            }
        }.getOrElse { "invalid URL" }

    override fun resolveRedirect(
        currentUrl: String,
        location: String,
    ): String =
        if (location.startsWith("http://") || location.startsWith("https://")) {
            location
        } else {
            val base = Url(currentUrl)
            val prefix = "${base.protocol.name}://${base.host}${if (base.port == base.protocol.defaultPort) "" else ":${base.port}"}"
            if (location.startsWith('/')) "$prefix$location" else "$prefix/${location.removePrefix("./")}"
        }
}
