package dev.promethe.core

import dev.promethe.api.ToolApprovalRequirement
import dev.promethe.api.ToolContractSource
import dev.promethe.api.ToolEgress
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolApprovalPolicyTest {
    @Test
    fun `unknown and MCP tools require approval by default`() {
        val arguments = buildJsonObject {}

        assertEquals(ToolRisk.EXTERNAL_EFFECT, ToolApprovalPolicy.evaluate("unknown_tool", arguments).risk)
        assertTrue(ToolApprovalPolicy.requiresMandatoryApproval("unknown_tool", arguments))
        assertEquals(ToolRisk.EXTERNAL_EFFECT, ToolApprovalPolicy.catalogRisk("mcp_server_delete_everything"))
        assertTrue(ToolApprovalPolicy.requiresMandatoryApproval("mcp_server_delete_everything", arguments))
        assertFalse(ToolApprovalPolicy.contractFor("unknown_tool").explicit)
        assertEquals(ToolContractSource.FALLBACK, ToolApprovalPolicy.contractFor("unknown_tool").source)
        assertEquals(ToolContractSource.MCP, ToolApprovalPolicy.contractFor("mcp_server_delete_everything").source)
        assertTrue(ToolApprovalPolicy.contractFor("mcp_server_delete_everything").explicit)
        assertEquals(ToolApprovalRequirement.ALWAYS, ToolApprovalPolicy.contractFor("unknown_tool").approval)
        assertEquals(ToolEgress.UNKNOWN, ToolApprovalPolicy.contractFor("unknown_tool").egress)
        assertEquals(ToolEgress.REMOTE_SERVICE, ToolApprovalPolicy.contractFor("mcp_server_delete_everything").egress)
    }

    @Test
    fun `known read tools remain read only`() {
        val decision = ToolApprovalPolicy.evaluate("read_file", buildJsonObject {})

        assertEquals(ToolRisk.READ, decision.risk)
        assertFalse(decision.mandatoryApproval)
    }

    @Test
    fun `csv read is read only`() {
        val decision =
            ToolApprovalPolicy.evaluate(
                "csv",
                buildJsonObject {
                    put("action", "read")
                    put("path", "reports/input.csv")
                    put("maxRows", 25)
                },
            )

        assertEquals(ToolRisk.READ, decision.risk)
        assertFalse(decision.mandatoryApproval)
    }

    @Test
    fun `csv write requires mandatory approval`() {
        val decision =
            ToolApprovalPolicy.evaluate(
                "csv",
                buildJsonObject {
                    put("action", "write")
                    put("path", "reports/output.csv")
                    put("data", "name,value\nalpha,1")
                },
            )

        assertEquals(ToolRisk.WRITE, decision.risk)
        assertTrue(decision.mandatoryApproval)
        assertTrue(
            ToolApprovalPolicy.requiresMandatoryApproval(
                "csv",
                buildJsonObject {
                    put("action", "write")
                    put("path", "reports/output.csv")
                    put("data", "name,value\nalpha,1")
                },
            ),
        )
        assertEquals(ToolRisk.WRITE, ToolApprovalPolicy.catalogRisk("csv"))
    }

    @Test
    fun `csv unknown action fails closed as write`() {
        val decision =
            ToolApprovalPolicy.evaluate(
                "csv",
                buildJsonObject {
                    put("action", "truncate")
                    put("path", "reports/output.csv")
                },
            )

        assertEquals(ToolRisk.WRITE, decision.risk)
        assertTrue(decision.mandatoryApproval)
    }

    @Test
    fun `Discord policy reads are safe but mutations require approval`() {
        val read = ToolApprovalPolicy.evaluate("discord_policy", buildJsonObject { put("action", "list") })
        val mutation = ToolApprovalPolicy.evaluate("discord_policy", buildJsonObject { put("action", "allow_user") })

        assertEquals(ToolRisk.READ, read.risk)
        assertFalse(read.mandatoryApproval)
        assertEquals(ToolRisk.CONFIG_CHANGE, mutation.risk)
        assertTrue(mutation.mandatoryApproval)
        assertTrue(read.ownerOnly)
        assertTrue(ToolApprovalPolicy.contractFor("discord_policy").ownerOnly)
    }

    @Test
    fun `sensitive tools have explicit fail closed contracts`() {
        val sensitiveTools =
            listOf(
                "generate_image",
                "analyze_image",
                "text_to_speech",
                "video_generate",
                "video_analyze",
                "api_call",
                "web_screenshot",
                "browser_navigate",
                "checkpoint_save",
                "render_ui",
                "autonomous_goal",
                "mixture_of_agents",
                "list_agents",
                "get_subtask_result",
                "delegate_task",
                "todo",
                "notes",
            )

        sensitiveTools.forEach { toolName ->
            assertTrue(ToolApprovalPolicy.contractFor(toolName).explicit, toolName)
        }
        assertEquals(ToolRisk.WRITE, ToolApprovalPolicy.catalogRisk("checkpoint_save"))
        assertEquals(ToolRisk.READ, ToolApprovalPolicy.catalogRisk("list_agents"))
        assertEquals(ToolRisk.READ, ToolApprovalPolicy.catalogRisk("get_subtask_result"))
    }

    @Test
    fun `todo and notes reads are safe but mutations require approval`() {
        val todoList = ToolApprovalPolicy.evaluate("todo", buildJsonObject { put("action", "list") })
        val todoRemove = ToolApprovalPolicy.evaluate("todo", buildJsonObject { put("action", "remove") })
        val noteGet = ToolApprovalPolicy.evaluate("notes", buildJsonObject { put("action", "get") })
        val noteSet = ToolApprovalPolicy.evaluate("notes", buildJsonObject { put("action", "set") })

        assertEquals(ToolRisk.READ, todoList.risk)
        assertFalse(todoList.mandatoryApproval)
        assertEquals(ToolRisk.DESTRUCTIVE, todoRemove.risk)
        assertTrue(todoRemove.mandatoryApproval)
        assertEquals(ToolRisk.READ, noteGet.risk)
        assertFalse(noteGet.mandatoryApproval)
        assertEquals(ToolRisk.WRITE, noteSet.risk)
        assertTrue(noteSet.mandatoryApproval)
    }

    @Test
    fun `contract audit accepts approved dynamic families and rejects unknown tools`() {
        val accepted = ToolContractRegistry.audit(listOf("read_file", "todo", "mcp_server_tool", "acp_agent_capability"))
        val rejected = ToolContractRegistry.audit(listOf("unknown_dynamic_tool"))

        assertTrue(accepted.valid, accepted.issues.joinToString())
        assertFalse(rejected.valid)
        assertEquals(ToolContractCoverageIssueType.MISSING_EXPLICIT_CONTRACT, rejected.issues.first().type)
    }

    @Test
    fun `unknown composite operations fail closed`() {
        val decision =
            ToolApprovalPolicy.evaluate(
                "github",
                buildJsonObject { put("action", "delete_repository") },
            )

        assertEquals(ToolRisk.EXTERNAL_EFFECT, decision.risk)
        assertTrue(decision.mandatoryApproval)
    }

    @Test
    fun `only an explicit qualified MCP certification grants read risk`() {
        val toolName = "mcp_test_certified_lookup"
        val arguments = buildJsonObject {}

        try {
            ToolApprovalPolicy.certifyReadOnlyMcpTool(toolName)
            assertEquals(ToolRisk.READ, ToolApprovalPolicy.evaluate(toolName, arguments).risk)
            assertFalse(ToolApprovalPolicy.requiresMandatoryApproval(toolName, arguments))
        } finally {
            ToolApprovalPolicy.revokeReadOnlyMcpToolCertification(toolName)
        }

        assertEquals(ToolRisk.EXTERNAL_EFFECT, ToolApprovalPolicy.evaluate(toolName, arguments).risk)
        assertFailsWith<IllegalArgumentException> {
            ToolApprovalPolicy.certifyReadOnlyMcpTool("not_mcp")
        }
    }
}
