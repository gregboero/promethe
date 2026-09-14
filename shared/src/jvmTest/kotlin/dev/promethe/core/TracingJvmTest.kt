package dev.promethe.core

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TracingJvmTest {
    @Test
    fun `span context survives coroutine execution and exports metadata`() =
        runTest {
            val exporter = InMemorySpanExporter.create()
            val provider =
                SdkTracerProvider
                    .builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build()
            val previousTelemetry = Tracing.telemetry
            Tracing.telemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build()

            try {
                Tracing.span(
                    name = "agent.llm_call",
                    attributes =
                        mapOf(
                            "gen_ai.request.model" to "test-model",
                            "gen_ai.prompt" to "must-not-be-exported",
                        ),
                ) {
                    assertTrue(Span.current().spanContext.isValid)
                    setAttribute("agent.history_size", 2)
                    setAttribute("gen_ai.response.content", "must-not-be-exported")
                }

                assertFalse(Span.current().spanContext.isValid)
                assertEquals("agent.llm_call", exporter.finishedSpanItems.single().name)
            } finally {
                Tracing.telemetry = previousTelemetry
                provider.shutdown()
            }
        }

    @Test
    fun `console backend selects the local logging exporter`() {
        val properties = requireNotNull(Tracing.exporterProperties("console", "", "", ""))
        assertEquals("console", properties["otel.traces.exporter"])
    }

    @Test
    fun `invalid OTLP endpoint fails closed`() {
        assertNull(Tracing.exporterProperties("otlp", "ftp://collector.example", "", ""))
    }

    @Test
    fun `langfuse settings produce an authenticated OTLP HTTP exporter`() {
        val properties =
            requireNotNull(
                Tracing.exporterProperties(
                    backend = "langfuse",
                    endpoint = "https://cloud.langfuse.com/",
                    publicKey = "pk-test",
                    secretKey = "sk-test",
                ),
            )

        assertEquals("otlp", properties["otel.traces.exporter"])
        assertEquals("http/protobuf", properties["otel.exporter.otlp.protocol"])
        assertEquals(
            "https://cloud.langfuse.com/api/public/otel",
            properties["otel.exporter.otlp.endpoint"],
        )
        assertTrue(properties.getValue("otel.exporter.otlp.headers").contains("x-langfuse-ingestion-version=4"))
    }

    @Test
    fun `sensitive string attributes are excluded from exported spans`() {
        val exporter = InMemorySpanExporter.create()
        val provider =
            SdkTracerProvider
                .builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()
        val telemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build()
        val span = telemetry.getTracer("test").spanBuilder("test.span").startSpan()

        Tracing.setAttribute(span, "gen_ai.request.model", "test-model")
        Tracing.setAttribute(span, "gen_ai.prompt", "never-export-this")
        Tracing.setStringAttribute(span, "gen_ai.response.content", "never-export-this")
        span.end()

        val exported = exporter.finishedSpanItems.single()
        assertEquals("test-model", exported.attributes.get(AttributeKey.stringKey("gen_ai.request.model")))
        assertFalse(exported.attributes.asMap().keys.any { it.key.contains("prompt") })
        assertFalse(exported.attributes.asMap().keys.any { it.key.contains("content") })
        provider.shutdown()
    }
}
