package dev.promethe.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.charset
import io.ktor.http.contentType
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path
import okio.Path.Companion.toPath
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files

data class PinnedOutboundTarget(
    val connectionUrl: String,
    val hostHeader: String,
    val tlsServerName: String,
)

enum class OutboundHttpErrorCode {
    RESPONSE_TOO_LARGE,
}

class OutboundHttpResponseException(
    val errorCode: OutboundHttpErrorCode,
    val limitBytes: Int,
) : IOException("${errorCode.name}: response exceeds the configured $limitBytes-byte limit")

object CanonicalWorkspacePathResolver : WorkspacePathResolver {
    override fun resolve(
        basePath: Path,
        requested: String,
    ): Path? =
        runCatching {
            val root = File(basePath.toString()).toPath().toRealPath()
            val candidate = root.resolve(requested).normalize()
            if (!candidate.startsWith(root)) return null
            if (root.relativize(candidate).firstOrNull()?.toString()?.lowercase() in WorkspacePathPolicy.protectedNames) {
                return null
            }

            var existingAncestor = candidate
            while (!Files.exists(existingAncestor)) {
                existingAncestor = existingAncestor.parent ?: return null
            }
            val realAncestor = existingAncestor.toRealPath()
            if (!realAncestor.startsWith(root)) return null

            val resolved = realAncestor.resolve(existingAncestor.relativize(candidate)).normalize()
            resolved.takeIf { it.startsWith(root) }?.toString()?.toPath()
        }.getOrNull()
}

class JvmOutboundUrlPolicy(
    private val resolver: (String) -> Array<InetAddress> = InetAddress::getAllByName,
) : OutboundUrlPolicy {
    override suspend fun rejectionReason(url: String): String? {
        val error = runCatching { resolvePublicTarget(url) }.exceptionOrNull() ?: return null
        return error.message ?: "invalid or unresolvable URL"
    }

    suspend fun resolvePublicTarget(url: String): PinnedOutboundTarget =
        withContext(Dispatchers.IO) {
            val uri = runCatching { URI(url) }.getOrElse { throw IllegalArgumentException("invalid URL") }
            require(uri.scheme?.lowercase() in setOf("http", "https")) { "only HTTP and HTTPS are allowed" }
            require(uri.rawUserInfo == null) { "URLs containing credentials are blocked" }
            val host = uri.host?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("URL host is missing")
            require(!host.equals("localhost", ignoreCase = true)) { "loopback destinations are blocked" }
            val addresses = runCatching { resolver(host).toList() }.getOrElse {
                throw IllegalArgumentException("invalid or unresolvable URL")
            }
            require(addresses.isNotEmpty()) { "invalid or unresolvable URL" }
            require(addresses.none(::isNonPublicAddress)) { "private, local, or special-use destinations are blocked" }

            val address = addresses.first().hostAddress.substringBefore('%')
            val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
            val connectionUri = URI(uri.scheme, null, address, uri.port, path, uri.rawQuery, null)
            val defaultPort = if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80
            val displayHost = if (host.contains(':')) "[$host]" else host
            val hostHeader = if (uri.port == -1 || uri.port == defaultPort) displayHost else "$displayHost:${uri.port}"
            PinnedOutboundTarget(connectionUri.toASCIIString(), hostHeader, host)
        }

    override fun resolveRedirect(
        currentUrl: String,
        location: String,
    ): String = URI(currentUrl).resolve(location).toString()

    private fun isNonPublicAddress(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }

        val bytes = address.address.map { it.toInt() and 0xff }
        return when (address) {
            is Inet4Address -> {
                bytes[0] == 0 ||
                    bytes[0] == 10 ||
                    bytes[0] == 127 ||
                    (bytes[0] == 100 && bytes[1] in 64..127) ||
                    (bytes[0] == 169 && bytes[1] == 254) ||
                    (bytes[0] == 172 && bytes[1] in 16..31) ||
                    (bytes[0] == 192 && bytes[1] == 168) ||
                    (bytes[0] == 192 && bytes[1] == 0 && bytes[2] in setOf(0, 2)) ||
                    (bytes[0] == 192 && bytes[1] == 88 && bytes[2] == 99) ||
                    (bytes[0] == 198 && bytes[1] in 18..19) ||
                    (bytes[0] == 198 && bytes[1] == 51 && bytes[2] == 100) ||
                    (bytes[0] == 203 && bytes[1] == 0 && bytes[2] == 113) ||
                    bytes[0] >= 224
            }

            is Inet6Address -> {
                bytes.firstOrNull() in setOf(0xfc, 0xfd)
            }

            else -> {
                true
            }
        }
    }
}

class PinnedJvmOutboundHttpFetcher(
    private val policy: JvmOutboundUrlPolicy,
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    private val clientFactory: (PinnedOutboundTarget) -> HttpClient = ::createPinnedClient,
) : SecureJvmOutboundHttpClient {
    init {
        require(maxResponseBytes in 1..MAX_CONFIGURABLE_RESPONSE_BYTES) {
            "maxResponseBytes must be between 1 and $MAX_CONFIGURABLE_RESPONSE_BYTES"
        }
    }

    override suspend fun get(url: String): OutboundHttpResponse =
        executeRequest(
            method = HttpMethod.Get,
            url = url,
            headers = emptyMap(),
            body = null,
        )

    override suspend fun getFollowingRedirects(
        url: String,
        headers: Map<String, String>,
        maxRedirects: Int,
    ): OutboundHttpResponse =
        executeFollowingRedirects(
            method = HttpMethod.Get,
            initialUrl = url,
            headers = headers,
            body = null,
            maxRedirects = maxRedirects,
            allowCrossOriginRedirects = true,
        )

    override suspend fun postJsonFollowingRedirects(
        url: String,
        headers: Map<String, String>,
        body: String,
        maxRedirects: Int,
    ): OutboundHttpResponse =
        executeFollowingRedirects(
            method = HttpMethod.Post,
            initialUrl = url,
            headers = headers,
            body = body,
            maxRedirects = maxRedirects,
            allowCrossOriginRedirects = false,
        )

    override suspend fun requestFollowingRedirects(
        method: HttpMethod,
        url: String,
        headers: Map<String, String>,
        body: String?,
        maxRedirects: Int,
    ): OutboundHttpResponse =
        executeFollowingRedirects(
            method = method,
            initialUrl = url,
            headers = headers,
            body = body,
            maxRedirects = maxRedirects,
            allowCrossOriginRedirects = false,
        )

    private suspend fun executeFollowingRedirects(
        method: HttpMethod,
        initialUrl: String,
        headers: Map<String, String>,
        body: String?,
        maxRedirects: Int,
        allowCrossOriginRedirects: Boolean,
    ): OutboundHttpResponse {
        require(maxRedirects in 0..MAX_REDIRECTS) { "maxRedirects must be between 0 and $MAX_REDIRECTS" }
        var currentUrl = initialUrl
        repeat(maxRedirects + 1) { redirectCount ->
            val response = executeRequest(method, currentUrl, headers, body)
            if (response.status !in 300..399) return response
            if (redirectCount == maxRedirects) {
                throw IllegalArgumentException("too many HTTP redirects")
            }
            val location =
                response.location
                    ?: throw IllegalArgumentException("redirect response omitted the Location header")
            val redirectedUrl = policy.resolveRedirect(currentUrl, location)
            if (!allowCrossOriginRedirects && !hasSameOrigin(currentUrl, redirectedUrl)) {
                throw IllegalArgumentException("cross-origin redirects are blocked for authenticated requests")
            }
            currentUrl = redirectedUrl
        }
        error("unreachable")
    }

    private suspend fun executeRequest(
        method: HttpMethod,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): OutboundHttpResponse {
        val target = policy.resolvePublicTarget(url)
        validateHeaders(headers)
        val client = clientFactory(target)
        return try {
            client
                .prepareRequest(target.connectionUrl) {
                    this.method = method
                    headers.forEach { (name, value) -> header(name, value) }
                    header(HttpHeaders.Host, target.hostHeader)
                    if (body != null) {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                    timeout {
                        requestTimeoutMillis = 15_000
                        connectTimeoutMillis = 5_000
                        socketTimeoutMillis = 15_000
                    }
                }
                .execute { response ->
                    OutboundHttpResponse(
                        status = response.status.value,
                        location = response.headers[HttpHeaders.Location],
                        body = response.readBodyWithinLimit(),
                    )
                }
        } finally {
            client.close()
        }
    }

    private suspend fun HttpResponse.readBodyWithinLimit(): String {
        val declaredLength = headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declaredLength != null && declaredLength > maxResponseBytes) {
            throw responseTooLarge()
        }

        val channel = bodyAsChannel()
        val initialCapacity =
            declaredLength
                ?.coerceAtMost(maxResponseBytes.toLong())
                ?.toInt()
                ?: minOf(READ_BUFFER_BYTES, maxResponseBytes)
        val output = ByteArrayOutputStream(initialCapacity)
        val buffer = ByteArray(minOf(READ_BUFFER_BYTES, maxResponseBytes + 1))
        var totalBytes = 0

        while (true) {
            val bytesToRead = minOf(buffer.size, maxResponseBytes - totalBytes + 1)
            val bytesRead = channel.readAvailable(buffer, 0, bytesToRead)
            if (bytesRead == -1) break
            if (bytesRead == 0) continue
            if (totalBytes + bytesRead > maxResponseBytes) {
                val failure = responseTooLarge()
                channel.cancel(failure)
                throw failure
            }
            output.write(buffer, 0, bytesRead)
            totalBytes += bytesRead
        }

        val responseCharset = charset() ?: Charsets.UTF_8
        return output.toByteArray().toString(responseCharset)
    }

    private fun responseTooLarge(): OutboundHttpResponseException =
        OutboundHttpResponseException(
            errorCode = OutboundHttpErrorCode.RESPONSE_TOO_LARGE,
            limitBytes = maxResponseBytes,
        )

    private fun validateHeaders(headers: Map<String, String>) {
        headers.forEach { (name, value) ->
            require(name.lowercase() !in FORBIDDEN_HEADERS) { "header '$name' cannot be overridden" }
            require('\r' !in name && '\n' !in name && '\r' !in value && '\n' !in value) {
                "invalid HTTP header"
            }
        }
    }

    private fun hasSameOrigin(
        firstUrl: String,
        secondUrl: String,
    ): Boolean {
        val first = URI(firstUrl)
        val second = URI(secondUrl)
        return first.scheme.equals(second.scheme, ignoreCase = true) &&
            first.host.equals(second.host, ignoreCase = true) &&
            effectivePort(first) == effectivePort(second)
    }

    private fun effectivePort(uri: URI): Int =
        when {
            uri.port != -1 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }

    private companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        const val MAX_CONFIGURABLE_RESPONSE_BYTES = 64 * 1024 * 1024
        const val READ_BUFFER_BYTES = 8 * 1024
        const val MAX_REDIRECTS = 5
        val FORBIDDEN_HEADERS =
            setOf(
                "connection",
                "content-length",
                "host",
                "proxy-authorization",
                "proxy-connection",
                "te",
                "trailer",
                "transfer-encoding",
                "upgrade",
            )

        fun createPinnedClient(target: PinnedOutboundTarget): HttpClient =
            HttpClient(CIO) {
                followRedirects = false
                install(HttpTimeout)
                engine {
                    requestTimeout = 15_000
                    endpoint {
                        connectTimeout = 5_000
                        socketTimeout = 15_000
                    }
                    https { serverName = target.tlsServerName }
                }
            }
    }
}

interface SecureJvmOutboundHttpClient : OutboundHttpFetcher {
    suspend fun getFollowingRedirects(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxRedirects: Int = 5,
    ): OutboundHttpResponse

    suspend fun postJsonFollowingRedirects(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String,
        maxRedirects: Int = 5,
    ): OutboundHttpResponse

    suspend fun requestFollowingRedirects(
        method: HttpMethod,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        maxRedirects: Int = 5,
    ): OutboundHttpResponse =
        when (method) {
            HttpMethod.Get -> {
                getFollowingRedirects(url, headers, maxRedirects)
            }

            HttpMethod.Post -> {
                postJsonFollowingRedirects(
                    url = url,
                    headers = headers,
                    body = body.orEmpty(),
                    maxRedirects = maxRedirects,
                )
            }

            else -> {
                error("HTTP method ${method.value} is unavailable for this secure client")
            }
        }
}
