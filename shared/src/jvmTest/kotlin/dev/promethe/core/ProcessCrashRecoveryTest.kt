package dev.promethe.core

import dev.promethe.api.AgentRunRecord
import dev.promethe.api.AgentRunStatus
import dev.promethe.db.DatabaseFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcessCrashRecoveryTest {
    @Test
    fun `abrupt child exit preserves committed intents and uncertain effects require review`() =
        runBlocking {
            for (phase in listOf("before", "during", "after")) {
                val directory = createTempDirectory("promethe-process-crash")
                try {
                    val url = "jdbc:sqlite:${directory.resolve("state.db")}"
                    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
                    val classpath = Path.of(System.getProperty("promethe.test.classpathFile")).toFile().readText().replace('\\', '/')
                    val arguments = directory.resolve("java.args").toFile()
                    arguments.writeText("-cp\n\"$classpath\"\n${RecoveryCrashFixture::class.java.name}\n\"${url.replace('\\', '/')}\"\n$phase\n")
                    val child = ProcessBuilder(java, "@${arguments.absolutePath}")
                        .redirectErrorStream(true).redirectOutput(directory.resolve("child.log").toFile()).start()
                    if (!child.waitFor(45, TimeUnit.SECONDS)) {
                        child.destroyForcibly()
                        error("Crash fixture timed out: $phase")
                    }
                    assertEquals(23, child.exitValue(), directory.resolve("child.log").toFile().readText().takeLast(2000))
                    val database = DatabaseFactory.create(url)
                    val runs = PersistentRunLedger(database)
                    val recovery = RunRecoveryService(runs, PersistentRunEventLedger(database), now = { 200 })
                    val assessment = recovery.auditInterruptedRuns().single()
                    if (phase == "during") {
                        assertEquals(RunRecoveryDisposition.NEEDS_REVIEW, assessment.disposition)
                        assertNull(recovery.claimResume("crash-run"))
                    } else {
                        assertEquals(RunRecoveryDisposition.RECOVERABLE, assessment.disposition)
                        if (phase == "after") {
                            val admission = PersistentToolIntentLedger(database).prepare(RecoveryCrashFixture.request(), ToolRisk.EXTERNAL_EFFECT, 210)
                            assertIs<ToolIntentAdmission.Replay>(admission)
                        }
                    }
                } finally {
                    directory.toFile().deleteRecursively()
                }
            }
        }
}

/** The child deliberately skips shutdown hooks, ensuring no in-memory ledger can satisfy the parent. */
object RecoveryCrashFixture {
    fun request() =
        ToolExecutionRequest(
            toolName = "send_message",
            arguments = buildJsonObject { put("message", "fixture") },
            sessionId = "crash-session",
            origin = ToolCallOrigin.A2A,
            runId = "crash-run",
            stepId = "crash-step",
        )

    @JvmStatic
    fun main(args: Array<String>) =
        runBlocking {
            val database = DatabaseFactory.create(args[0])
            assertTrue(
                PersistentRunLedger(database).begin(
                    AgentRunRecord(
                        runId = "crash-run",
                        sessionId = "crash-session",
                        origin = AgentExecutionOrigin.A2A.name,
                        requestFingerprint = "fixture",
                        status = AgentRunStatus.PENDING,
                        createdAt = 100,
                        updatedAt = 100,
                    ),
                ),
            )
            if (args[1] != "before") {
                val ledger = PersistentToolIntentLedger(database)
                val intent = assertIs<ToolIntentAdmission.Proceed>(ledger.prepare(request(), ToolRisk.EXTERNAL_EFFECT, 110))
                val id = requireNotNull(intent.intentId)
                assertTrue(ledger.markExecuting(id, 120))
                if (args[1] == "after") assertTrue(ledger.markSucceeded(id, "fixture sent", 130))
            }
            Runtime.getRuntime().halt(23)
        }
}
