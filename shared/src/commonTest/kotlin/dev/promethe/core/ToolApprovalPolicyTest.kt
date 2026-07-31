package dev.promethe.core

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
