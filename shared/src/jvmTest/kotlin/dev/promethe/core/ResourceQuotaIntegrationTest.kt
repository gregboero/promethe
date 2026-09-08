package dev.promethe.core

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMProvider
import com.sun.net.httpserver.HttpServer
import dev.promethe.core.tools.builtin.TokenBudgetArgs
import dev.promethe.core.tools.builtin.TokenBudgetTool
import dev.promethe.db.DatabaseFactory
import io.ktor.client.HttpClient
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject

/** Real Koog HTTP client pointed only at localhost; no credentials or paid provider. */
class ResourceQuotaIntegrationTest {
    private class LocalProvider(
        private val status: Int = 200,
    ) : AutoCloseable {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val sdk: OpenAILLMClient

        init {
            server.createContext("/") { exchange ->
                requests.incrementAndGet()
                exchange.requestBody.use { it.readBytes() }
                val body = if (status == 200) {
                    """{"id":"fixture","object":"chat.completion","created":1,"model":"gpt-4o","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
                } else {
                    """{"error":{"message":"local fixture rejection","type":"invalid_request_error","code":"fixture"}}"""
                }
                val bytes = body.encodeToByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            sdk = OpenAILLMClient(apiKey = "local-fixture-only", settings = OpenAIClientSettings(baseUrl = "http://127.0.0.1:${server.address.port}"), httpClientFactory = getHttpClientFactory())
        }

        fun adapter(
            registry: ResourceGovernorRegistry,
            model: String = "gpt-4o",
        ) = KoogLlmAdapter(
            AgentConfig(provider = "openai", modelName = model, fallbackChainEnabled = true),
            resourceGovernors = registry,
            executor = MultiLLMPromptExecutor(LLMProvider.OpenAI to sdk),
        )

        override fun close() {
            sdk.close()
            server.stop(0)
        }
    }

    @Test fun `second unbound completion is refused before HTTP and without fallback`() =
        runBlocking<Unit> {
            val directory = Files.createTempDirectory("quota-http")
            try {
                val bank = PersistentResourceQuotaBank(directory.resolve("quota.sqlite"), "owner", listOf(ResourceQuotaRule("all-models", ResourceQuotaDimension.OWNER, GovernedResource.LLM_CALL, 1)))
                val registry = PersistentResourceGovernorRegistry(DatabaseFactory.createInMemory(), aggregateQuotas = bank)
                LocalProvider().use { provider ->
                    val adapter = provider.adapter(registry)
                    assertEquals("ok", adapter.completeWithProfile("test", listOf("user" to "hello"), "openai", "gpt-4o", 0.0).content)
                    val denied = assertFailsWith<AgentExecutionException> { adapter.completeWithProfile("test", listOf("user" to "hello again"), "openai", "gpt-4o", 0.0) }
                    assertEquals("resource_budget_exceeded", denied.code)
                    assertEquals(1, provider.requests.get())
                    val text = TokenBudgetTool(adapter, registry).execute(TokenBudgetArgs())
                    assertTrue(text.contains("all-models") && text.contains("1/1") && text.contains("resetsAt="))
                }
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test fun `cross provider fallback checks actual fallback quota before dispatch`() =
        runBlocking<Unit> {
            val directory = Files.createTempDirectory("quota-fallback")
            try {
                val rules = listOf(
                    ResourceQuotaRule("owner", ResourceQuotaDimension.OWNER, GovernedResource.LLM_CALL, 5),
                    ResourceQuotaRule("blocked-google", ResourceQuotaDimension.PROVIDER, GovernedResource.LLM_CALL, 0, selector = "google"),
                )
                val bank = PersistentResourceQuotaBank(directory.resolve("quota.sqlite"), "owner", rules)
                val registry = PersistentResourceGovernorRegistry(DatabaseFactory.createInMemory(), aggregateQuotas = bank)
                LocalProvider(400).use { provider ->
                    val adapter = provider.adapter(registry, "gpt-5.6-terra")
                    val denied = assertFailsWith<AgentExecutionException> { adapter.completeWithProfile("test", listOf("user" to "hello"), "openai", "gpt-5.6-terra", 0.0) }
                    assertEquals("resource_budget_exceeded", denied.code)
                    assertTrue(denied.message.orEmpty().contains("blocked-google"))
                    assertEquals(1, provider.requests.get())
                    assertEquals(1L, bank.snapshot().first { it.ruleId == "owner" }.used)
                    assertEquals(0L, bank.snapshot().first { it.ruleId == "blocked-google" }.used)
                }
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test fun `quota storage failure and cancellation never invoke provider or fallback`() =
        runBlocking<Unit> {
            for (cancelled in listOf(false, true)) {
                val bank = object : ResourceQuotaBank {
                    override suspend fun reserve(
                        resource: GovernedResource,
                        scope: ResourceQuotaScope,
                    ): ResourceQuotaUsage? {
                        if (cancelled) throw CancellationException("cancelled admission")
                        error("quota storage failure")
                    }

                    override suspend fun snapshot(): List<ResourceQuotaUsage> = emptyList()
                }
                val registry = PersistentResourceGovernorRegistry(DatabaseFactory.createInMemory(), aggregateQuotas = bank)
                LocalProvider().use { provider ->
                    val adapter = provider.adapter(registry)
                    if (cancelled) {
                        assertFailsWith<CancellationException> { adapter.completeWithProfile("test", listOf("user" to "hello"), "openai", "gpt-4o", 0.0) }
                    } else {
                        val denied = assertFailsWith<AgentExecutionException> { adapter.completeWithProfile("test", listOf("user" to "hello"), "openai", "gpt-4o", 0.0) }
                        assertEquals("resource_budget_unavailable", denied.code)
                    }
                    assertEquals(0, provider.requests.get())
                }
            }
        }

    @Test fun `tool quota storage failure blocks side effects without run context`() =
        runBlocking<Unit> {
            val client = HttpClient()
            try {
                var starts = 0
                val bank = object : ResourceQuotaBank {
                    override suspend fun reserve(
                        resource: GovernedResource,
                        scope: ResourceQuotaScope,
                    ): ResourceQuotaUsage? = error("unavailable storage")

                    override suspend fun snapshot(): List<ResourceQuotaUsage> = emptyList()
                }
                val registry = PersistentResourceGovernorRegistry(DatabaseFactory.createInMemory(), aggregateQuotas = bank)
                ToolRegistry.register(object : SimpleTool<HarnessArguments>(typeToken<HarnessArguments>(), "json_query", "Local quota fixture") {
                    override suspend fun execute(args: HarnessArguments): String {
                        starts++
                        return "ok"
                    }
                })
                val gate = object : ApprovalGate {
                    override suspend fun check(
                        toolName: String,
                        args: String,
                        sessionId: String,
                    ) = ApprovalGate.ApprovalResult(true, "test fixture")
                }
                val executor = ActionExecutor(AgentConfig(), client, approvalGate = gate, resourceGovernors = registry)
                val result = executor.execute(ToolExecutionRequest("json_query", buildJsonObject {}, "session"))
                assertTrue(result.contains("[BLOCKED]") && result.contains("admission could not be recorded"))
                assertEquals(0, starts)
            } finally {
                ToolRegistry.clear()
                client.close()
            }
        }

    @Test fun `tool starts across runs stop at aggregate quota and diagnostic stays accessible`() =
        runBlocking<Unit> {
            val directory = Files.createTempDirectory("quota-tools")
            val client = HttpClient()
            try {
                val bank = PersistentResourceQuotaBank(directory.resolve("quota.sqlite"), "owner", listOf(ResourceQuotaRule("json-tool", ResourceQuotaDimension.TOOL, GovernedResource.TOOL_START, 1, selector = "json_query")))
                val registry = PersistentResourceGovernorRegistry(DatabaseFactory.createInMemory(), aggregateQuotas = bank)
                var starts = 0
                ToolRegistry.register(object : SimpleTool<HarnessArguments>(typeToken<HarnessArguments>(), "json_query", "Local quota fixture") {
                    override suspend fun execute(args: HarnessArguments): String {
                        starts++
                        return "ok"
                    }
                })
                val gate = object : ApprovalGate {
                    override suspend fun check(
                        toolName: String,
                        args: String,
                        sessionId: String,
                    ) = ApprovalGate.ApprovalResult(true, "test fixture")
                }
                val executor = ActionExecutor(AgentConfig(), client, approvalGate = gate, resourceGovernors = registry)
                for (index in 1..2) {
                    registry.acquire("r$index", "s$index", ResourceBudget.DEFAULT)
                    val request = ToolExecutionRequest("json_query", buildJsonObject {}, "s$index", runId = "r$index")
                    val result = executor.execute(request)
                    if (index == 1) assertTrue(result.contains("ok")) else assertTrue(result.contains("[BLOCKED]") && result.contains("json-tool"))
                }
                assertEquals(1, starts)
                assertEquals(1L, registry.quotaSnapshot().single().used)
                assertIs<ResourceAdmission.Denied>(registry.admit(null, GovernedResource.TOOL_START, ResourceQuotaScope(toolName = "json_query")))
            } finally {
                ToolRegistry.clear()
                client.close()
                directory.toFile().deleteRecursively()
            }
        }
}
