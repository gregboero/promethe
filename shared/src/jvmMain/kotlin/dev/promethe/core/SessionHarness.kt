package dev.promethe.core

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Model-controlled revisions affect presentation only, scoped to one session. */
class SessionHarness(
    private val store: HarnessStore,
    private val runner: HarnessRunner,
    private val artifacts: ArtifactStore,
    private val preferSmallerObservations: Boolean = true,
) : HarnessControl {
    override val language: HarnessLanguage get() = runner.language
    private val locks = Array(256) { Mutex() }
    private val steps = ConcurrentHashMap<String, Pair<String?, String?>>()

    private fun lock(session: String) = locks[session.hashCode() and 255]

    override suspend fun command(
        request: ToolExecutionRequest,
        operation: String,
        args: HarnessArguments,
    ): String =
        lock(request.sessionId).withLock {
            try {
                require(request.sessionId.isNotBlank() && request.sessionId != "unknown") { "Explicit session required" }
                require(request.origin in setOf(ToolCallOrigin.AGENT, ToolCallOrigin.A2A) && request.dataTrust == dev.promethe.api.PolicyDataTrust.TRUSTED) { "Local trusted owner session required" }
                val session = request.sessionId
                val state = store.state(session)
                when (operation) {
                    "inspect" -> {
                        "Active=${state.active}; pending=${state.pending}; previous=${state.previous}. Language=$language. Source is $sourceDescription. Validation input/output pairs: ${Json.encodeToString(FIXTURES.map { mapOf("input" to it.first, "expected" to it.second) })}. Return unknown formats unchanged. Original tool status/provenance remain immutable. No network or file operations. If choosing to change: call harness_propose with source, toolName and baseRevision=${state.active}; then harness_evaluate and harness_activate using the returned revision id."
                    }

                    "propose" -> {
                        require(args.source.isNotBlank() && args.source.encodeToByteArray().size <= 32 * 1024)
                        require(args.toolName in setOf("read_file", "json_query", "harness_fixture")) { "Unsupported processor target" }
                        require(args.baseRevision == state.active && state.pending == null) { "Stale base or pending activation" }
                        val revision = HarnessRevision(UUID.randomUUID().toString(), session, args.baseRevision, args.source, sha256(args.source), args.toolName, System.currentTimeMillis(), language = language)
                        store.put(revision)
                        store.event(session, request.runId, revision.id, "proposed")
                        Json.encodeToString(revision.copy(source = ""))
                    }

                    "evaluate" -> {
                        val revision = verified(session, args.revision)
                        val passed = evaluate(revision)
                        store.validate(session, revision.id, passed)
                        store.event(session, request.runId, revision.id, if (passed) "validated" else "rejected")
                        "revision=${revision.id}; passed=$passed"
                    }

                    "activate" -> {
                        val revision = verified(session, args.revision)
                        require(revision.validated && revision.baseRevision == state.active && state.pending == null) { "Unvalidated or stale revision" }
                        store.update(session, state, state.copy(pending = revision.id, run = request.runId), "activation_requested", revision.id)
                        "Pending ${revision.id}; activates at next step"
                    }

                    "rollback" -> {
                        require(state.previous != null) { "No previous revision" }
                        store.update(session, state, state.copy(pending = state.previous), "rollback_requested", state.previous)
                        "Rollback scheduled for next step"
                    }

                    "disable" -> {
                        store.update(session, state, state.copy(pending = DISABLED), "disable_requested")
                        "Disable scheduled for next step"
                    }

                    else -> {
                        error("Unknown harness operation")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                "[ERROR] ${error.message}"
            }
        }

    override suspend fun beginStep(
        sessionId: String,
        runId: String?,
        stepId: String?,
    ) = lock(sessionId).withLock {
        if (steps[sessionId] == (runId to stepId)) return@withLock
        var state = store.state(sessionId)
        if (state.run != null && state.run != runId) {
            runner.endSession(sessionId)
            store.update(sessionId, state, HarnessStore.State(run = runId), "new_run_reset")
            state = store.state(sessionId)
        }
        val restoring = !steps.containsKey(sessionId)
        val next = state.pending ?: if (restoring) state.active else null
        if (next != null) {
            val candidate = if (next == DISABLED) null else runCatching { verified(sessionId, next) }.getOrNull()
            val passed = next == DISABLED || (candidate != null && evaluate(candidate))
            if (passed) {
                if (next == DISABLED) runner.endSession(sessionId)
                store.update(sessionId, state, state.copy(active = next.takeUnless { it == DISABLED }, previous = if (next == state.active) state.previous else state.active, pending = null, run = runId), if (restoring && next == state.active) "restored" else "activated")
            } else {
                store.update(sessionId, state, state.copy(active = if (restoring) null else state.active, pending = null), "activation_failed", next)
            }
        }
        steps[sessionId] = runId to stepId
    }

    override suspend fun revisionKey(sessionId: String): String = store.state(sessionId).active ?: "original"

    override suspend fun process(
        request: ToolExecutionRequest,
        raw: String,
    ): ProcessedObservation =
        lock(request.sessionId).withLock {
            val state = store.state(request.sessionId)
            val id = state.active ?: return@withLock ProcessedObservation(raw)
            try {
                val revision = verified(request.sessionId, id)
                if (revision.toolName != request.toolName || raw.encodeToByteArray().size > 256 * 1024) return@withLock ProcessedObservation(raw)
                // A byte heuristic, not a token/cost guarantee. Avoid a subprocess for tiny results.
                if (preferSmallerObservations && raw.encodeToByteArray().size < 256) {
                    store.event(request.sessionId, request.runId, id, "bypassed_small")
                    return@withLock ProcessedObservation(raw)
                }
                val reference = artifacts.put(ArtifactWriteRequest(raw.encodeToByteArray(), "text/plain", "harness_raw_observation", request.runId, request.stepId, toolName = request.toolName))
                val transformed = runner.execute(revision.source, HarnessObservation(request.toolName, raw), request.sessionId)
                require(transformed.encodeToByteArray().size <= 64 * 1024)
                val presented = "$transformed\n[harness revision=$id; raw=${reference.uri}; presentation only]"
                if (preferSmallerObservations && presented.encodeToByteArray().size >= raw.encodeToByteArray().size) {
                    store.event(request.sessionId, request.runId, id, "bypassed_no_saving")
                    return@withLock ProcessedObservation(raw)
                }
                store.event(request.sessionId, request.runId, id, "processed:${reference.hash}")
                ProcessedObservation(presented, id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val previous = state.previous?.let { runCatching { verified(request.sessionId, it) }.getOrNull() }
                val restored = previous?.takeIf { evaluate(it) }?.id
                store.update(request.sessionId, state, state.copy(active = restored, previous = null, pending = null), "processor_failed_rollback", id)
                ProcessedObservation(raw)
            }
        }

    override suspend fun endSession(sessionId: String) =
        lock(sessionId).withLock {
            try {
                val state = store.state(sessionId)
                store.update(sessionId, state, HarnessStore.State(), "session_closed")
            } finally {
                steps.remove(sessionId)
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { runner.endSession(sessionId) }
            }
            Unit
        }

    private fun verified(
        session: String,
        id: String,
    ): HarnessRevision =
        store.get(session, id).also {
            check(it.language == language) { "Revision language does not match the configured runner" }
            check(it.hash == sha256(it.source)) { "Revision integrity mismatch" }
        }

    private suspend fun evaluate(revision: HarnessRevision): Boolean =
        try {
            runner.executeBatch(revision.source, FIXTURES.map { HarnessObservation(revision.toolName, it.first) }, revision.sessionId) == FIXTURES.map { it.second }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

    companion object {
        private const val DISABLED = "disabled"
        private val FIXTURES = listOf(
            "{\"answer\":42,\"noise\":\"irrelevant\"}" to "42",
            "{\"noise\":\"answer=wrong\",\"answer\":\"été\"}" to "été",
            "id,answer\n1,17" to "17",
            "DATA answer=29" to "29",
            "unstructured text" to "unstructured text",
        )

        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
