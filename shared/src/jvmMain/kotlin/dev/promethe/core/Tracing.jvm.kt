package dev.promethe.core

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * JVM actual implementation of Tracing.
 *
 * Tracy compiler plugin temporarily disabled (incompatible with Kotlin 2.4.0).
 * Using no-op stubs until Tracy releases a Kotlin 2.4.0-compatible version.
 *
 * TODO: Re-enable Tracy when it supports Kotlin 2.4.0:
 *   1. Uncomment tracy plugin + dependency in shared/build.gradle.kts
 *   2. Restore Tracy imports and TracingManager calls below
 */
actual object Tracing {
    private var initialized = false

    actual fun initialize(backend: String) {
        if (initialized) return
        if (backend == "none") return
        initialized = true

        // Tracy disabled — log warning
        logger.warn {
            "Tracy tracing temporarily disabled (Kotlin 2.4.0 upgrade). Backend '$backend' will be available when Tracy supports Kotlin 2.4."
        }
    }

    actual fun flush() {
        // No-op while Tracy is disabled
    }

    actual inline fun <T> span(
        name: String,
        attributes: Map<String, Any>,
        block: SpanScope.() -> T,
    ): T {
        // Pass-through without tracing while Tracy is disabled
        val scope = SpanScope()
        return scope.block()
    }
}

/**
 * JVM actual SpanScope — no-op while Tracy is disabled.
 */
actual class SpanScope {
    actual fun setAttribute(
        key: String,
        value: String,
    ) { /* no-op */ }

    actual fun setAttribute(
        key: String,
        value: Int,
    ) { /* no-op */ }

    actual fun setAttribute(
        key: String,
        value: Long,
    ) { /* no-op */ }

    actual fun setAttribute(
        key: String,
        value: Double,
    ) { /* no-op */ }

    actual fun setAttribute(
        key: String,
        value: Boolean,
    ) { /* no-op */ }
}
