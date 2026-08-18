package dev.promethe.core

import io.github.oshai.kotlinlogging.KotlinLogging
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * JVM implementation backed by the OpenTelemetry Java SDK autoconfiguration
 * module. Only trace spans are enabled here: metrics and logs remain disabled
 * until they have their own bounded data model.
 */
actual object Tracing {
    private const val INSTRUMENTATION_SCOPE = "dev.promethe"
    private const val FLUSH_TIMEOUT_SECONDS = 5L
    private const val MAX_ATTRIBUTE_KEY_LENGTH = 96
    private const val MAX_ATTRIBUTE_VALUE_LENGTH = 128

    private val initializationLock = Any()

    @Volatile
    private var initialized = false

    @PublishedApi
    internal var telemetry: OpenTelemetry = OpenTelemetry.noop()

    @Volatile
    private var sdk: OpenTelemetrySdk? = null

    @PublishedApi
    internal val tracer: Tracer
        get() = telemetry.getTracer(INSTRUMENTATION_SCOPE)

    actual fun initialize(
        backend: String,
        endpoint: String,
        publicKey: String,
        secretKey: String,
    ) {
        synchronized(initializationLock) {
            if (initialized) return

            val properties = exporterProperties(backend, endpoint, publicKey, secretKey)
            if (properties == null) {
                initialized = true
                logger.info { "OpenTelemetry tracing disabled." }
                return
            }

            try {
                val configuredSdk =
                    AutoConfiguredOpenTelemetrySdk
                        .builder()
                        .addPropertiesSupplier { properties }
                        .build()
                        .openTelemetrySdk

                sdk = configuredSdk
                telemetry = configuredSdk
                initialized = true
                logger.info { "OpenTelemetry tracing initialized with '${properties["otel.traces.exporter"]}' exporter." }
            } catch (error: Exception) {
                initialized = true
                telemetry = OpenTelemetry.noop()
                logger.error(error) { "OpenTelemetry initialization failed; tracing is disabled." }
            }
        }
    }

    actual fun flush() {
        try {
            sdk?.sdkTracerProvider?.forceFlush()?.join(FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: Exception) {
            logger.warn(error) { "OpenTelemetry trace flush failed." }
        }
    }

    actual suspend fun <T> span(
        name: String,
        attributes: Map<String, Any>,
        block: suspend SpanScope.() -> T,
    ): T = executeSpan(name, attributes, block)

    @PublishedApi
    internal suspend fun <T> executeSpan(
        name: String,
        attributes: Map<String, Any>,
        block: suspend SpanScope.() -> T,
    ): T {
        val span = tracer.spanBuilder(safeSpanName(name)).startSpan()
        attributes.forEach { (key, value) -> setAttribute(span, key, value) }
        val context = Context.current().with(span)

        return try {
            withContext(context.asContextElement()) {
                SpanScope(span).block()
            }
        } catch (error: Throwable) {
            // Exception messages can include user content or secrets.
            span.setStatus(StatusCode.ERROR)
            throw error
        } finally {
            span.end()
        }
    }

    @PublishedApi
    internal fun exporterProperties(
        backend: String,
        endpoint: String,
        publicKey: String,
        secretKey: String,
    ): Map<String, String>? {
        val configuredBackend = backend.trim().lowercase(Locale.ROOT)
        val base =
            mutableMapOf(
                "otel.service.name" to "promethe",
                "otel.metrics.exporter" to "none",
                "otel.logs.exporter" to "none",
            )
        when (configuredBackend) {
            "none", "" -> {
                return null
            }

            "console" -> {
                base["otel.traces.exporter"] = "console"
            }

            "otlp" -> {
                base["otel.traces.exporter"] = "otlp"
                if (endpoint.isNotBlank()) {
                    val validated = validatedEndpoint(endpoint) ?: return null
                    base["otel.exporter.otlp.endpoint"] = validated
                }
            }

            "langfuse" -> {
                if (publicKey.isBlank() || secretKey.isBlank()) {
                    logger.warn { "Langfuse tracing requires both public and secret keys; tracing is disabled." }
                    return null
                }
                val host = validatedEndpoint(endpoint.ifBlank { "https://cloud.langfuse.com" }) ?: return null
                val otlpEndpoint =
                    if (host.endsWith("/api/public/otel")) host else "${host.trimEnd('/')}/api/public/otel"
                val credentials = Base64.getEncoder().encodeToString("$publicKey:$secretKey".toByteArray(Charsets.UTF_8))
                base["otel.traces.exporter"] = "otlp"
                base["otel.exporter.otlp.protocol"] = "http/protobuf"
                base["otel.exporter.otlp.endpoint"] = otlpEndpoint
                base["otel.exporter.otlp.headers"] =
                    "Authorization=Basic $credentials,x-langfuse-ingestion-version=4"
            }

            else -> {
                logger.warn { "Unsupported tracing backend '$backend'; tracing is disabled." }
                return null
            }
        }
        return base
    }

    private fun validatedEndpoint(endpoint: String): String? {
        if (endpoint.isBlank()) return null
        return try {
            val uri = URI(endpoint.trim())
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) {
                logger.warn { "Tracing endpoint must be an HTTP(S) URL without embedded credentials." }
                null
            } else {
                uri.toString().trimEnd('/')
            }
        } catch (_: Exception) {
            logger.warn { "Tracing endpoint is not a valid URL." }
            null
        }
    }

    @PublishedApi
    internal fun setAttribute(
        span: Span,
        key: String,
        value: Any,
    ) {
        val safeKey = safeAttributeKey(key) ?: return
        when (value) {
            is String -> safeMetadataValue(value)?.let { span.setAttribute(safeKey, it) }
            is Int -> span.setAttribute(safeKey, value.toLong())
            is Long -> span.setAttribute(safeKey, value)
            is Double -> span.setAttribute(safeKey, value)
            is Float -> span.setAttribute(safeKey, value.toDouble())
            is Boolean -> span.setAttribute(safeKey, value)
        }
    }

    @PublishedApi
    internal fun setStringAttribute(
        span: Span,
        key: String,
        value: String,
    ) {
        val safeKey = safeAttributeKey(key) ?: return
        safeMetadataValue(value)?.let { span.setAttribute(safeKey, it) }
    }

    @PublishedApi
    internal fun setLongAttribute(
        span: Span,
        key: String,
        value: Long,
    ) {
        safeAttributeKey(key)?.let { span.setAttribute(it, value) }
    }

    @PublishedApi
    internal fun setDoubleAttribute(
        span: Span,
        key: String,
        value: Double,
    ) {
        safeAttributeKey(key)?.let { span.setAttribute(it, value) }
    }

    @PublishedApi
    internal fun setBooleanAttribute(
        span: Span,
        key: String,
        value: Boolean,
    ) {
        safeAttributeKey(key)?.let { span.setAttribute(it, value) }
    }

    @PublishedApi
    internal fun safeSpanName(name: String): String =
        name
            .take(128)
            .takeIf { it.matches(Regex("[A-Za-z0-9._/-]+")) }
            ?: "promethe.operation"

    private fun safeAttributeKey(key: String): String? {
        val normalized = key.lowercase(Locale.ROOT)
        if (key.length > MAX_ATTRIBUTE_KEY_LENGTH || !key.matches(Regex("[A-Za-z0-9_.-]+"))) return null
        if (key == "tool.args_keys") return key
        return key.takeUnless { sensitiveAttributeTerms.any(normalized::contains) }
    }

    private fun safeMetadataValue(value: String): String? =
        value
            .takeIf { it.length <= MAX_ATTRIBUTE_VALUE_LENGTH }
            ?.takeIf { it.matches(Regex("[A-Za-z0-9._:/,@+=-]+")) }

    private val sensitiveAttributeTerms =
        listOf(
            "api_key",
            "apikey",
            "argument",
            "authorization",
            "body",
            "content",
            "cookie",
            "credential",
            "input",
            "message",
            "output",
            "password",
            "payload",
            "prompt",
            "query",
            "secret",
            "text",
            "token",
        )
}

actual class SpanScope
    @PublishedApi
    internal constructor(
        private val span: Span,
    ) {
        actual fun setAttribute(
            key: String,
            value: String,
        ) = Tracing.setStringAttribute(span, key, value)

        actual fun setAttribute(
            key: String,
            value: Int,
        ) = Tracing.setLongAttribute(span, key, value.toLong())

        actual fun setAttribute(
            key: String,
            value: Long,
        ) = Tracing.setLongAttribute(span, key, value)

        actual fun setAttribute(
            key: String,
            value: Double,
        ) = Tracing.setDoubleAttribute(span, key, value)

        actual fun setAttribute(
            key: String,
            value: Boolean,
        ) = Tracing.setBooleanAttribute(span, key, value)
    }
