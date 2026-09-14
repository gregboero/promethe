package dev.promethe.core

import kotlin.test.*
import kotlinx.serialization.json.*

class CampaignWireAuditTest {
    @Test fun `independent decoder distinguishes empty null and nonempty text without exporting values`() {
        for ((content, type, length) in listOf(Triple("\"\"", "text", 0), Triple("null", "null", null), Triple("\"private\"", "text", 7))) {
            val audit = CampaignWireAudit.inspect("""{"choices":[{"message":{"content":$content}}]}""")
            assertEquals(type, audit.getValue("contentType").jsonPrimitive.content)
            assertEquals(length, audit.getValue("contentCodePoints").jsonPrimitive.intOrNull)
            assertEquals(type == "text" && length == 0, audit.getValue("emptyText").jsonPrimitive.boolean)
            assertFalse(audit.toString().contains("private"))
        }
    }
}
