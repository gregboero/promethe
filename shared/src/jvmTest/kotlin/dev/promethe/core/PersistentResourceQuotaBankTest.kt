package dev.promethe.core

import dev.promethe.db.DatabaseFactory
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

class PersistentResourceQuotaBankTest {
    private val ownerRule = ResourceQuotaRule("owner-llm", ResourceQuotaDimension.OWNER, GovernedResource.LLM_CALL, 10, 60)
    private val providerRule = ResourceQuotaRule("providers", ResourceQuotaDimension.PROVIDER, GovernedResource.LLM_CALL, 1, 60)
    private val openai = ResourceQuotaScope(provider = "openai")

    private fun temporary(block: suspend CoroutineScope.(java.nio.file.Path) -> Unit) =
        runBlocking {
            val directory = Files.createTempDirectory("resource-quotas")
            try {
                block(directory)
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test fun `all dimensions reserve together and denial charges none`() =
        temporary { directory ->
            val bank = PersistentResourceQuotaBank(directory.resolve("quotas.sqlite"), "owner", listOf(ownerRule, providerRule), now = { 1000 })
            assertNull(bank.reserve(GovernedResource.LLM_CALL, openai))
            assertEquals("providers", bank.reserve(GovernedResource.LLM_CALL, openai)?.ruleId)
            assertEquals(1, bank.snapshot().first { it.dimension == ResourceQuotaDimension.OWNER }.used)
            assertNull(bank.reserve(GovernedResource.LLM_CALL, ResourceQuotaScope(provider = "google")))
            assertEquals(2, bank.snapshot().first { it.dimension == ResourceQuotaDimension.OWNER }.used)
            assertEquals(2, bank.snapshot().count { it.dimension == ResourceQuotaDimension.PROVIDER })
        }

    @Test fun `separate bank instances never exceed concurrent shared quota`() =
        temporary { directory ->
            val path = directory.resolve("quotas.sqlite")
            val banks = List(4) { PersistentResourceQuotaBank(path, "owner", listOf(ownerRule), now = { 1000 }) }
            val results = (0 until 48).map { index -> async(Dispatchers.Default) { banks[index % 4].reserve(GovernedResource.LLM_CALL, openai) } }.awaitAll()
            assertEquals(10, results.count { it == null })
            assertEquals(10, banks[0].snapshot().single().used)
        }

    @Test fun `quota survives reopen and owner scopes are independent`() =
        temporary { directory ->
            val path = directory.resolve("quotas.sqlite")
            val rules = listOf(ownerRule.copy(maxStarts = 1))
            assertNull(PersistentResourceQuotaBank(path, "alice", rules, now = { 1000 }).reserve(GovernedResource.LLM_CALL, openai))
            assertNotNull(PersistentResourceQuotaBank(path, "alice", rules, now = { 1000 }).reserve(GovernedResource.LLM_CALL, openai))
            assertNull(PersistentResourceQuotaBank(path, "bob", rules, now = { 1000 }).reserve(GovernedResource.LLM_CALL, openai))
        }

    @Test fun `window rollover works and backward clock cannot reopen old window`() =
        temporary { directory ->
            val path = directory.resolve("quotas.sqlite")
            var now = 59_999L
            val rules = listOf(ownerRule.copy(maxStarts = 1))
            val bank = PersistentResourceQuotaBank(path, "owner", rules, now = { now })
            assertNull(bank.reserve(GovernedResource.LLM_CALL, openai))
            assertEquals(60_000, bank.snapshot().single().resetsAt)
            now = 60_000
            assertNull(bank.reserve(GovernedResource.LLM_CALL, openai))
            now = 1
            val reopened = PersistentResourceQuotaBank(path, "owner", rules, now = { now })
            assertEquals(60_000, reopened.reserve(GovernedResource.LLM_CALL, openai)?.windowStartedAt)
            assertEquals(1, reopened.snapshot().single().used)
        }

    @Test fun `tightening limits preserves consumption and zero denies all`() =
        temporary { directory ->
            val path = directory.resolve("quotas.sqlite")
            val bank = PersistentResourceQuotaBank(path, "owner", listOf(ownerRule), now = { 1000 })
            repeat(2) { assertNull(bank.reserve(GovernedResource.LLM_CALL, openai)) }
            val tighter = PersistentResourceQuotaBank(path, "owner", listOf(ownerRule.copy(maxStarts = 1)), now = { 1000 })
            assertEquals(2, tighter.reserve(GovernedResource.LLM_CALL, openai)?.used)
            val zero = PersistentResourceQuotaBank(path, "another", listOf(ownerRule.copy(maxStarts = 0)), now = { 1000 })
            assertEquals(0, zero.reserve(GovernedResource.LLM_CALL, openai)?.used)
        }

    @Test fun `wildcard tools are distinct and exact selectors do not catch other tools`() =
        temporary { directory ->
            val rules = listOf(ResourceQuotaRule("tool", ResourceQuotaDimension.TOOL, GovernedResource.TOOL_START, 1, selector = "*"))
            val bank = PersistentResourceQuotaBank(directory.resolve("quotas.sqlite"), "owner", rules, now = { 1000 })
            assertEquals(rules, bank.policies())
            for (tool in listOf("read_file", "json_query")) assertNull(bank.reserve(GovernedResource.TOOL_START, ResourceQuotaScope(toolName = tool)))
            assertNotNull(bank.reserve(GovernedResource.TOOL_START, ResourceQuotaScope(toolName = "read_file")))
            val exact = PersistentResourceQuotaBank(directory.resolve("exact.sqlite"), "owner", listOf(rules.single().copy(selector = "read_file", maxStarts = 0)), now = { 1000 })
            assertNull(exact.reserve(GovernedResource.TOOL_START, ResourceQuotaScope(toolName = "json_query")))
            assertNotNull(exact.reserve(GovernedResource.TOOL_START, ResourceQuotaScope(toolName = "read_file")))
        }

    @Test fun `provider normalization and missing identity fail closed`() =
        temporary { directory ->
            val bank = PersistentResourceQuotaBank(directory.resolve("quotas.sqlite"), "owner", listOf(providerRule.copy(selector = "OpenAI")), now = { 1000 })
            assertNull(bank.reserve(GovernedResource.LLM_CALL, ResourceQuotaScope(provider = " OpenAI ")))
            assertNotNull(bank.reserve(GovernedResource.LLM_CALL, openai))
            assertFailsWith<IllegalArgumentException> { bank.reserve(GovernedResource.LLM_CALL, ResourceQuotaScope()) }
            assertEquals(1, bank.snapshot().single().used)
        }

    @Test fun `invalid policies are rejected rather than silently disabling quotas`() {
        for (json in listOf(
            """[{"id":"bad","dimension":"PROVIDER","resource":"TOOL_START","maxStarts":1}]""",
            """[{"id":"bad","dimension":"OWNER","resource":"LLM_CALL","maxStarts":-1}]""",
            """[{"id":"bad","dimension":"OWNER","resource":"LLM_CALL","maxStarts":1,"windowSeconds":0}]""",
            """[{"id":"bad","dimension":"OWNER","resource":"LLM_CALL","maxStarts":1,"unknown":true}]""",
        )) {
            assertFailsWith<IllegalArgumentException> { PersistentResourceQuotaBank.parseRules(json) }
        }
        val item = """{"id":"duplicate","dimension":"OWNER","resource":"LLM_CALL","maxStarts":1}"""
        assertFailsWith<IllegalArgumentException> { PersistentResourceQuotaBank.parseRules("[$item,$item]") }
    }

    @Test fun `two runs and child share owner quota while run denial does not charge it`() =
        temporary { directory ->
            val bank = PersistentResourceQuotaBank(directory.resolve("quotas.sqlite"), "owner", listOf(ownerRule.copy(maxStarts = 2)), now = { 1000 })
            val registry = PersistentResourceGovernorRegistry(DatabaseFactory.createInMemory(), aggregateQuotas = bank)
            val first = assertIs<ResourceGovernorAcquisition.Acquired>(registry.acquire("r1", "s1", ResourceBudget(maxLlmCalls = 1))).governor
            assertIs<ResourceAdmission.Allowed>(registry.admit("r1", GovernedResource.LLM_CALL, openai))
            assertEquals(ResourceLimit.LLM_CALLS, assertIs<ResourceAdmission.Denied>(registry.admit("r1", GovernedResource.LLM_CALL, openai)).limit)
            assertEquals(1, bank.snapshot().single().used)
            registry.acquire("r2", "s2", ResourceBudget.DEFAULT)
            assertIs<ChildResourceBinding.Bound>(registry.bindChild("s2", "child"))
            registry.acquire("r3", "child", ResourceBudget.DEFAULT)
            assertIs<ResourceAdmission.Allowed>(registry.admit("r3", GovernedResource.LLM_CALL, openai))
            assertEquals(ResourceLimit.AGGREGATE_STARTS, assertIs<ResourceAdmission.Denied>(registry.admit("r2", GovernedResource.LLM_CALL, openai)).limit)
            assertEquals(1, first.snapshot().llmCallsStarted)
            registry.release("r1", "s1")
            registry.acquire("r4", "s1", ResourceBudget.DEFAULT)
            assertIs<ResourceAdmission.Denied>(registry.admit("r4", GovernedResource.LLM_CALL, openai))
        }

    @Test fun `sub agent starts share profile quota across independent parents`() =
        temporary { directory ->
            val rule = ResourceQuotaRule("children", ResourceQuotaDimension.OWNER, GovernedResource.SUB_AGENT, 1)
            val bank = PersistentResourceQuotaBank(directory.resolve("quotas.sqlite"), "owner", listOf(rule), now = { 1000 })
            val registry = PersistentResourceGovernorRegistry(DatabaseFactory.createInMemory(), aggregateQuotas = bank)
            registry.acquire("r1", "s1", ResourceBudget.DEFAULT)
            val second = assertIs<ResourceGovernorAcquisition.Acquired>(registry.acquire("r2", "s2", ResourceBudget.DEFAULT)).governor
            assertIs<ChildResourceBinding.Bound>(registry.bindChild("s1", "child1"))
            assertIs<ChildResourceBinding.Denied>(registry.bindChild("s2", "child2"))
            assertEquals(0, second.snapshot().subAgentsStarted)
            assertEquals(1, bank.snapshot().single().used)
            registry.unbindSession("child1")
            assertIs<ChildResourceBinding.Denied>(registry.bindChild("s2", "child2"))
        }

    @Test fun `failed run persistence keeps aggregate reservation and starts no work`() =
        temporary { directory ->
            val bank = PersistentResourceQuotaBank(directory.resolve("quotas.sqlite"), "owner", listOf(ownerRule), now = { 1000 })
            val governor = ResourceGovernor("run", ResourceBudget.DEFAULT, stateWriter = ResourceGovernorStateWriter { _, _ -> false }, aggregateQuotas = bank)
            assertFailsWith<IllegalStateException> { governor.admit(GovernedResource.LLM_CALL, openai) }
            assertEquals(1, bank.snapshot().single().used)
            assertEquals(0, governor.snapshot().llmCallsStarted)
        }

    @Test fun `calls without a run remain governed and quota data is diagnostic`() =
        temporary { directory ->
            val bank = PersistentResourceQuotaBank(directory.resolve("quotas.sqlite"), "owner", listOf(ownerRule.copy(maxStarts = 1)), now = { 1000 })
            val registry = PersistentResourceGovernorRegistry(DatabaseFactory.createInMemory(), aggregateQuotas = bank)
            assertNull(registry.admit(null, GovernedResource.LLM_CALL, openai))
            val denied = assertIs<ResourceAdmission.Denied>(registry.admit(null, GovernedResource.LLM_CALL, openai))
            assertTrue(denied.message().contains("owner-llm"))
            assertTrue(denied.message().contains("resetsAt=60000"))
            assertEquals(1, registry.quotaSnapshot().single().used)
        }
}
