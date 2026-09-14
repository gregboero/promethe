package dev.promethe.core

/**
 * KMP-compatible tracing abstraction.
 * On JVM: delegates to the OpenTelemetry SDK.
 * On other targets: no-op.
 */
expect object Tracing {
    /**
     * Initialize the tracing backend. Call once at startup.
     * @param backend "none", "console", "langfuse", or "otlp"
     */
    fun initialize(
        backend: String = "console",
        endpoint: String = "",
        publicKey: String = "",
        secretKey: String = "",
    )

    /**
     * Flush all pending traces. Call before shutdown.
     */
    fun flush()

    /**
     * Execute [block] inside a named tracing span.
     * Attributes can be set via the [SpanScope] receiver.
     */
    suspend fun <T> span(
        name: String,
        attributes: Map<String, Any> = emptyMap(),
        block: suspend SpanScope.() -> T,
    ): T
}

/**
 * Scope for setting attributes on the current span.
 */
expect class SpanScope {
    fun setAttribute(
        key: String,
        value: String,
    )

    fun setAttribute(
        key: String,
        value: Int,
    )

    fun setAttribute(
        key: String,
        value: Long,
    )

    fun setAttribute(
        key: String,
        value: Double,
    )

    fun setAttribute(
        key: String,
        value: Boolean,
    )
}
