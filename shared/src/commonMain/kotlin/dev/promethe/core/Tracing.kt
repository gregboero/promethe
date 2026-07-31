package dev.promethe.core

/**
 * KMP-compatible tracing abstraction.
 * On JVM: delegates to JetBrains Tracy (OpenTelemetry).
 * On other targets: no-op.
 */
expect object Tracing {
    /**
     * Initialize the tracing backend. Call once at startup.
     * @param backend "console", "langfuse", or "otlp"
     */
    fun initialize(backend: String = "console")

    /**
     * Flush all pending traces. Call before shutdown.
     */
    fun flush()

    /**
     * Execute [block] inside a named tracing span.
     * Attributes can be set via the [SpanScope] receiver.
     */
    inline fun <T> span(
        name: String,
        attributes: Map<String, Any> = emptyMap(),
        block: SpanScope.() -> T,
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
