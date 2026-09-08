package dev.promethe.core

import dev.promethe.api.*
import dev.promethe.core.tools.builtin.SkillLoadArgs
import dev.promethe.core.tools.builtin.SkillLoadTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.buffer
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class SkillEvaluationLifecycleTest {
    private class Fixture : AutoCloseable {
        val fs = FakeFileSystem()
        val root = "/skills".toPath()
        val writer = SkillWriter(fs, root)
        val loader = SkillLoader(fs, root)
        val suite = SkillEvaluationSuite("invoice", listOf(SkillEvaluationCase("first", "invoice 2", "4"), SkillEvaluationCase("second", "invoice 3", "6")))

        suspend fun current() = loader.listSkills().single()

        suspend fun create(): SkillEntry {
            assertNotNull(writer.write(SkillEntry("invoice", content = "For invoice tasks, multiply the given integer by 2. Return only the number.")))
            val draft = current()
            assertEquals(SkillLifecycle.DRAFT, draft.contract.lifecycle)
            writer.configureEvaluations("invoice", requireNotNull(draft.contract.revisionHash), listOf(suite))
            return current()
        }

        fun evaluator(
            subject: SkillEvaluationSubject = SkillEvaluationSubject { _, input -> (input.substringAfterLast(' ').toInt() * 2).toString() },
            timeout: Long = 1000,
        ) = SkillEvaluationService(loader, writer.governance, subject, "deterministic-fixture", timeout)

        suspend fun evaluate(): SkillEvaluationRun = evaluator().evaluate("invoice", requireNotNull(current().contract.revisionHash))

        suspend fun promote(target: SkillLifecycle): okio.Path? {
            val skill = current()
            val run = writer.governance.validRun(skill)
            return writer.update(
                skill.copy(
                    contract = skill.contract.copy(
                        lifecycle = target,
                        evaluatedRunId = run?.id,
                        reviewedContentHash = skill.contract.contentHash,
                        reviewedRevisionHash = skill.contract.revisionHash,
                        reviewNote = "Reviewed the two independent invoice cases",
                        reviewedAt = "2026-09-08",
                    ),
                ),
                expectedRevisionHash = skill.contract.revisionHash,
            )
        }

        override fun close() {
            fs.checkNoOpenFiles()
            fs.close()
        }
    }

    @Test fun `activation requires current evidence and persists across loader restart`() =
        runBlocking<Unit> {
            Fixture().use { f ->
                f.create()
                assertNull(f.promote(SkillLifecycle.CANDIDATE))
                val run = f.evaluate()
                assertEquals(SkillEvaluationStatus.PASSED, run.status)
                assertEquals(2, run.results.size)
                assertNotNull(f.promote(SkillLifecycle.CANDIDATE))
                assertNotNull(f.promote(SkillLifecycle.ACTIVE))
                val reopened = SkillLoader(f.fs, f.root)
                assertEquals(listOf("invoice"), reopened.listExecutableSkills().map { it.name })
                assertTrue(SkillLoadTool(reopened).execute(SkillLoadArgs("invoice")).contains("multiply"))
                assertEquals(run.id, f.writer.governance.state(f.current()).latestRun?.id)
            }
        }

    @Test fun `failed second task blocks promotion even if first passes`() =
        runBlocking<Unit> {
            Fixture().use { f ->
                val skill = f.create()
                val run = f.evaluator(SkillEvaluationSubject { _, _ -> "4" }).evaluate("invoice", skill.contract.revisionHash!!)
                assertEquals(listOf(SkillEvaluationStatus.PASSED, SkillEvaluationStatus.FAILED), run.results.map { it.status })
                assertNull(f.promote(SkillLifecycle.CANDIDATE))
                assertTrue(f.loader.listExecutableSkills().isEmpty())
            }
        }

    @Test fun `editing metadata or cases invalidates evidence as well as editing body`() =
        runBlocking<Unit> {
            Fixture().use { f ->
                f.create()
                f.evaluate()
                f.promote(SkillLifecycle.CANDIDATE)
                f.promote(SkillLifecycle.ACTIVE)
                val old = f.current()
                assertNotNull(f.writer.update(old.copy(description = "changed description"), expectedRevisionHash = old.contract.revisionHash))
                assertEquals(SkillLifecycle.QUARANTINED, f.current().contract.lifecycle)
                assertNull(f.writer.governance.validRun(f.current()))
                f.evaluate()
                f.promote(SkillLifecycle.CANDIDATE)
                f.promote(SkillLifecycle.ACTIVE)
                val before = f.current()
                f.writer.configureEvaluations("invoice", before.contract.revisionHash!!, listOf(f.suite.copy(cases = f.suite.cases.map { it.copy(expectedOutput = "wrong") })))
                assertNull(f.writer.governance.validRun(f.current()))
                assertTrue(f.loader.listExecutableSkills().isEmpty())
            }
        }

    @Test fun `same size out of band edits cannot use cached active skill or strip managed marker`() =
        runBlocking<Unit> {
            Fixture().use { f ->
                f.create()
                f.evaluate()
                f.promote(SkillLifecycle.CANDIDATE)
                f.promote(SkillLifecycle.ACTIVE)
                assertEquals(1, f.loader.listExecutableSkills().size)
                val path = f.root / "invoice" / "SKILL.md"
                val raw = f.fs.source(path).buffer().use { it.readUtf8() }
                f.fs.sink(path).buffer().use { it.writeUtf8(raw.replace("multiply", "subtract")) }
                assertTrue(f.loader.listExecutableSkills().isEmpty())
                f.fs.sink(path).buffer().use { it.writeUtf8("---\nname: invoice\nlifecycle: ACTIVE\n---\nUnreviewed instructions") }
                assertTrue(f.loader.listExecutableSkills().isEmpty())
            }
        }

    @Test fun `edit during evaluation leaves a historical result that cannot promote new revision`() =
        runBlocking<Unit> {
            Fixture().use { f ->
                val original = f.create()
                var edited = false
                val run = f.evaluator(
                    SkillEvaluationSubject { _, input ->
                        if (!edited) {
                            edited = true
                            f.writer.update(f.current().copy(content = "Changed during evaluation"))
                        }
                        (input.substringAfterLast(' ').toInt() * 2).toString()
                    },
                ).evaluate("invoice", original.contract.revisionHash!!)
                assertEquals(SkillEvaluationStatus.PASSED, run.status)
                assertNotEquals(run.revisionHash, f.current().contract.revisionHash)
                assertNull(f.promote(SkillLifecycle.CANDIDATE))
            }
        }

    @Test fun `failed rerun revokes previous pass and interrupted run is fail closed`() =
        runBlocking<Unit> {
            Fixture().use { f ->
                f.create()
                f.evaluate()
                f.promote(SkillLifecycle.CANDIDATE)
                f.promote(SkillLifecycle.ACTIVE)
                val skill = f.current()
                val run = f.evaluator(SkillEvaluationSubject { _, _ -> "wrong" }).evaluate("invoice", skill.contract.revisionHash!!)
                assertEquals(SkillEvaluationStatus.FAILED, run.status)
                assertTrue(f.loader.listExecutableSkills().isEmpty())
                f.writer.governance.beginRun("invoice", run.copy(id = "interrupted", status = SkillEvaluationStatus.RUNNING, results = emptyList()))
                assertFalse(SkillGovernanceStore(f.fs, f.root).state(f.current()).canPromote)
            }
        }

    @Test fun `cancellation timeout and evaluator exception never grant evidence`() =
        runBlocking<Unit> {
            Fixture().use { f ->
                val skill = f.create()
                assertFailsWith<CancellationException> { f.evaluator(SkillEvaluationSubject { _, _ -> throw CancellationException("cancel") }).evaluate("invoice", skill.contract.revisionHash!!) }
                assertEquals(SkillEvaluationStatus.CANCELLED, f.writer.governance.latestRun("invoice")?.status)
                val timed = f.evaluator(
                    SkillEvaluationSubject { _, _ ->
                        delay(200)
                        "4"
                    },
                    10,
                ).evaluate("invoice", skill.contract.revisionHash!!)
                assertEquals(SkillEvaluationStatus.ERROR, timed.status)
                val error = f.evaluator(SkillEvaluationSubject { _, _ -> error("fixture error") }).evaluate("invoice", skill.contract.revisionHash!!)
                assertEquals(SkillEvaluationStatus.ERROR, error.status)
                assertNull(f.promote(SkillLifecycle.CANDIDATE))
            }
        }

    @Test fun `rollback restores snapshot and suites in a fresh quarantined revision`() =
        runBlocking<Unit> {
            Fixture().use { f ->
                f.create()
                f.evaluate()
                f.promote(SkillLifecycle.CANDIDATE)
                f.promote(SkillLifecycle.ACTIVE)
                val active = f.current()
                val version = f.writer.governance.state(active).versions.first { it.lifecycle == SkillLifecycle.ACTIVE }
                f.writer.update(active.copy(content = "Broken procedure"))
                val changed = f.current()
                f.writer.restore("invoice", changed.contract.revisionHash!!, version.id)
                assertEquals(active.content, f.current().content)
                assertNotEquals(active.contract.revisionHash, f.current().contract.revisionHash)
                assertEquals(SkillLifecycle.QUARANTINED, f.current().contract.lifecycle)
                assertNull(f.promote(SkillLifecycle.CANDIDATE))
                f.evaluate()
                assertNotNull(f.promote(SkillLifecycle.CANDIDATE))
                assertNotNull(f.promote(SkillLifecycle.ACTIVE))
                assertEquals(1, f.loader.listExecutableSkills().size)
            }
        }

    @Test fun `parallel stale writer updates have one winner`() =
        runBlocking<Unit> {
            Fixture().use { f ->
                val current = f.create()
                val writes = (1..2).map { index -> async { SkillWriter(f.fs, f.root).update(current.copy(content = "Revision $index"), expectedRevisionHash = current.contract.revisionHash) } }.awaitAll()
                assertEquals(1, writes.count { it != null })
            }
        }

    @Test fun `suite validation rejects empty duplicate and missing tasks before evaluator`() =
        runBlocking<Unit> {
            Fixture().use { f ->
                val skill = f.create()
                for (suites in listOf(emptyList(), listOf(f.suite, f.suite), listOf(f.suite.copy(cases = listOf(f.suite.cases.first()))), listOf(f.suite.copy(cases = f.suite.cases.map { it.copy(input = "duplicate") })))) {
                    assertFailsWith<IllegalArgumentException> { f.writer.configureEvaluations("invoice", skill.contract.revisionHash!!, suites) }
                }
                f.writer.update(skill.copy(contract = skill.contract.copy(evalSuite = listOf("missing"))))
                var calls = 0
                assertFailsWith<IllegalArgumentException> {
                    f.evaluator(
                        SkillEvaluationSubject { _, _ ->
                            calls++
                            ""
                        },
                    ).evaluate("invoice", f.current().contract.revisionHash!!)
                }
                assertEquals(0, calls)
            }
        }
}
