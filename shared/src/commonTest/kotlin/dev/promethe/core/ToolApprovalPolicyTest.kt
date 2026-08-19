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
        assertEquals(ToolApprovalRequirement.ALWAYS, ToolApprovalPolicy.contractFor("unknown_tool").approval)
        assertEquals(ToolEgress.UNKNOWN, ToolApprovalPolicy.contractFor("unknown_tool").egress)
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
            )

        sensitiveTools.forEach { toolName ->
            assertTrue(ToolApprovalPolicy.contractFor(toolName).explicit, toolName)
        }
        assertEquals(ToolRisk.WRITE, ToolApprovalPolicy.catalogRisk("checkpoint_save"))
        assertEquals(ToolRisk.READ, ToolApprovalPolicy.catalogRisk("list_agents"))
        assertEquals(ToolRisk.READ, ToolApprovalPolicy.catalogRisk("get_subtask_result"))
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
