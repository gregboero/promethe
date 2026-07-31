package dev.promethe.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import java.net.InetAddress
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceSecurityTest {
    @Test
    fun `canonical workspace resolver blocks parent and sibling escapes`() {
        val root = createTempDirectory("promethe-workspace")
        val sibling = root.parent.resolve("${root.fileName}-sibling").createDirectories()

        assertNotNull(CanonicalWorkspacePathResolver.resolve(root.toString().toPath(), "inside.txt"))
        assertNull(CanonicalWorkspacePathResolver.resolve(root.toString().toPath(), "../${sibling.fileName}/outside.txt"))
    }

    @Test
    fun `canonical workspace resolver blocks symlink escapes when supported`() {
        val root = createTempDirectory("promethe-workspace")
        val outside = createTempDirectory("promethe-outside")
        val link = root.resolve("outside-link")
        if (runCatching { Files.createSymbolicLink(link, outside) }.isFailure) return

        assertNull(CanonicalWorkspacePathResolver.resolve(root.toString().toPath(), "outside-link/secret.txt"))
    }

    @Test
    fun `workspace resolvers block protected metadata directories`() {
        val root = createTempDirectory("promethe-workspace")

        listOf(".git", ".promethe", ".codex", ".agents").forEach { protectedName ->
            assertNull(CanonicalWorkspacePathResolver.resolve(root.toString().toPath(), "$protectedName/secret.txt"))
            assertNull(WorkspacePathPolicy.resolve(root.toString(), "$protectedName/secret.txt"))
        }
    }

    @Test
    fun `file workspace policy blocks symlink escapes when supported`() {
        val root = createTempDirectory("promethe-workspace")
        val outside = createTempDirectory("promethe-outside")
        val link = root.resolve("outside-link")
        if (runCatching { Files.createSymbolicLink(link, outside) }.isFailure) return

        assertNull(WorkspacePathPolicy.resolve(root.toString(), "outside-link/secret.txt"))
    }

    @Test
    fun `secure writer writes relative to directory handles and blocks protected paths`() =
        runTest {
            val root = createTempDirectory("promethe-workspace")
            root.resolve("nested").createDirectories()
            val writer = SecureJvmWorkspaceFileWriter(root.toString())
            val reader = SecureJvmWorkspaceFileReader(root.toString())

            val writeResult = runCatching { writer.write("nested/result.txt", "safe content") }
            if (writeResult.exceptionOrNull()?.message?.contains("unavailable on this filesystem") == true) {
                return@runTest
            }
            writeResult.getOrThrow()

            assertEquals("safe content", Files.readString(root.resolve("nested/result.txt")))
            assertEquals("safe content", reader.read("nested/result.txt"))
            assertFailsWith<IllegalArgumentException> {
                writer.write(".promethe/secret.txt", "secret")
            }
            assertFailsWith<IllegalArgumentException> {
                reader.read(".git/config")
            }
        }

    @Test
    fun `secure writer blocks symlink parents when supported`() =
        runTest {
            val root = createTempDirectory("promethe-workspace")
            val outside = createTempDirectory("promethe-outside")
            val link = root.resolve("outside-link")
            if (runCatching { Files.createSymbolicLink(link, outside) }.isFailure) return@runTest
            val writer = SecureJvmWorkspaceFileWriter(root.toString())

            assertFailsWith<Exception> {
                writer.write("outside-link/secret.txt", "secret")
            }
            assertTrue(Files.notExists(outside.resolve("secret.txt")))
        }

    @Test
    fun `outbound policy blocks local private and special-use addresses`() =
        runTest {
            val policy = JvmOutboundUrlPolicy { host -> arrayOf(InetAddress.getByName(host)) }

            assertNotNull(policy.rejectionReason("http://127.0.0.1/admin"))
            assertNotNull(policy.rejectionReason("http://10.0.0.5/internal"))
            assertNotNull(policy.rejectionReason("http://169.254.169.254/latest/meta-data"))
            assertNotNull(policy.rejectionReason("file:///etc/passwd"))
            assertTrue(policy.rejectionReason("https://93.184.216.34/") == null)
        }

    @Test
    fun `outbound target pins the validated address while preserving host and TLS name`() =
        runTest {
            val policy = JvmOutboundUrlPolicy { arrayOf(InetAddress.getByName("93.184.216.34")) }

            val target = policy.resolvePublicTarget("https://example.com:8443/docs?q=1")

            assertEquals("https://93.184.216.34:8443/docs?q=1", target.connectionUrl)
            assertEquals("example.com:8443", target.hostHeader)
            assertEquals("example.com", target.tlsServerName)
        }

    @Test
    fun `outbound policy rejects mixed public and private DNS answers`() =
        runTest {
            val policy =
                JvmOutboundUrlPolicy {
                    arrayOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("127.0.0.1"))
                }

            assertNotNull(policy.rejectionReason("https://example.com/"))
        }

    @Test
    fun `outbound fetcher revalidates DNS after every redirect`() =
        runTest {
            var dnsLookups = 0
            var requests = 0
            val policy =
                JvmOutboundUrlPolicy {
                    dnsLookups++
                    val address = if (dnsLookups == 1) "93.184.216.34" else "127.0.0.1"
                    arrayOf(InetAddress.getByName(address))
                }
            val fetcher =
                PinnedJvmOutboundHttpFetcher(policy) {
                    HttpClient(
                        MockEngine {
                            requests++
                            respond(
                                content = "",
                                status = HttpStatusCode.Found,
                                headers = headersOf(HttpHeaders.Location, "/next"),
                            )
                        },
                    ) {
                        followRedirects = false
                    }
                }

            assertFailsWith<IllegalArgumentException> {
                fetcher.getFollowingRedirects("https://example.com/start")
            }
            assertEquals(2, dnsLookups)
            assertEquals(1, requests)
        }

    @Test
    fun `authenticated POST refuses cross-origin redirects`() =
        runTest {
            var requests = 0
            val policy = JvmOutboundUrlPolicy { arrayOf(InetAddress.getByName("93.184.216.34")) }
            val fetcher =
                PinnedJvmOutboundHttpFetcher(policy) {
                    HttpClient(
                        MockEngine {
                            requests++
                            respond(
                                content = "",
                                status = HttpStatusCode.TemporaryRedirect,
                                headers = headersOf(HttpHeaders.Location, "https://other.example/rpc"),
                            )
                        },
                    ) {
                        followRedirects = false
                    }
                }

            assertFailsWith<IllegalArgumentException> {
                fetcher.postJsonFollowingRedirects(
                    url = "https://mcp.example/rpc",
                    headers = mapOf("Authorization" to "Bearer secret"),
                    body = "{}",
                )
            }
            assertEquals(1, requests)
        }

    @Test
    fun `outbound fetcher rejects declared oversized response before reading its body`() =
        runTest {
            val limitBytes = 32
            val secretMarker = "body-must-not-be-read"
            val policy = JvmOutboundUrlPolicy { arrayOf(InetAddress.getByName("93.184.216.34")) }
            val fetcher =
                PinnedJvmOutboundHttpFetcher(
                    policy = policy,
                    maxResponseBytes = limitBytes,
                    clientFactory = {
                        HttpClient(
                            MockEngine {
                                respond(
                                    content = ByteReadChannel(secretMarker),
                                    headers = headersOf(HttpHeaders.ContentLength, (limitBytes + 1).toString()),
                                )
                            },
                        )
                    },
                )

            val failure =
                assertFailsWith<OutboundHttpResponseException> {
                    fetcher.get("https://example.com/oversized")
                }

            assertEquals(OutboundHttpErrorCode.RESPONSE_TOO_LARGE, failure.errorCode)
            assertEquals(limitBytes, failure.limitBytes)
            assertTrue(secretMarker !in failure.message.orEmpty())
        }

    @Test
    fun `outbound fetcher rejects chunked response as soon as it crosses the byte limit`() =
        runTest {
            val limitBytes = 32
            val secretMarker = "sensitive-response-content"
            val policy = JvmOutboundUrlPolicy { arrayOf(InetAddress.getByName("93.184.216.34")) }
            val fetcher =
                PinnedJvmOutboundHttpFetcher(
                    policy = policy,
                    maxResponseBytes = limitBytes,
                    clientFactory = {
                        HttpClient(
                            MockEngine {
                                respond(content = "x".repeat(limitBytes) + secretMarker)
                            },
                        )
                    },
                )

            val failure =
                assertFailsWith<OutboundHttpResponseException> {
                    fetcher.get("https://example.com/chunked")
                }

            assertEquals(OutboundHttpErrorCode.RESPONSE_TOO_LARGE, failure.errorCode)
            assertEquals(limitBytes, failure.limitBytes)
            assertTrue(secretMarker !in failure.message.orEmpty())
        }

    @Test
    fun `outbound fetcher accepts body just under limit and honors response charset`() =
        runTest {
            val expected = "caf\u00e9"
            val encoded = expected.toByteArray(Charsets.ISO_8859_1)
            val policy = JvmOutboundUrlPolicy { arrayOf(InetAddress.getByName("93.184.216.34")) }
            val fetcher =
                PinnedJvmOutboundHttpFetcher(
                    policy = policy,
                    maxResponseBytes = encoded.size + 1,
                    clientFactory = {
                        HttpClient(
                            MockEngine {
                                respond(
                                    content = encoded,
                                    headers =
                                        headersOf(
                                            HttpHeaders.ContentType,
                                            "text/plain; charset=ISO-8859-1",
                                        ),
                                )
                            },
                        )
                    },
                )

            val response = fetcher.get("https://example.com/encoded")

            assertEquals(expected, response.body)
        }

    @Test
    fun `outbound fetcher bounds redirect response bodies too`() =
        runTest {
            val limitBytes = 16
            val policy = JvmOutboundUrlPolicy { arrayOf(InetAddress.getByName("93.184.216.34")) }
            val fetcher =
                PinnedJvmOutboundHttpFetcher(
                    policy = policy,
                    maxResponseBytes = limitBytes,
                    clientFactory = {
                        HttpClient(
                            MockEngine {
                                respond(
                                    content = "r".repeat(limitBytes + 1),
                                    status = HttpStatusCode.Found,
                                    headers = headersOf(HttpHeaders.Location, "/next"),
                                )
                            },
                        ) {
                            followRedirects = false
                        }
                    },
                )

            val failure =
                assertFailsWith<OutboundHttpResponseException> {
                    fetcher.getFollowingRedirects("https://example.com/start")
                }

            assertEquals(OutboundHttpErrorCode.RESPONSE_TOO_LARGE, failure.errorCode)
        }
}
