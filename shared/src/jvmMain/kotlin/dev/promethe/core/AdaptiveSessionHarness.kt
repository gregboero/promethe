package dev.promethe.core

import dev.promethe.api.PolicyDataTrust
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class HarnessAdaptationDecision(
    val choice: String,
    val reason: String,
    val entryId: String? = null,
    val estimatedSavedInputBytes: Long = 0,
    val estimatedAddedMillis: Long = 0,
    val estimatedInputSavingMicroUsd: Long? = null,
)

/** Opt-in owner-local experiment. Reuses source/evidence, never JVM state or another session's revision. */
class AdaptiveSessionHarness(
    private val delegate: SessionHarness,
    private val store: HarnessStore,
    private val runner: HarnessRunner,
    private val compatibility: String,
    private val inputMicroUsdPerMiB: Long? = null,
) : AdaptiveHarnessControl {
    override val language get() = delegate.language
    private val library = HarnessAdaptationLibrary(store)
    private val evaluator = HarnessAdaptationEvaluator(runner)
    private val locks = Array(256) { Mutex() }
    private val sessions = ConcurrentHashMap<String, Session>()

    private class Session(
        val scope: String,
        val run: String?,
    ) {
        val observations = mutableMapOf<String, LinkedHashMap<String, Int>>()
        var contract: String? = null
        var tool: String? = null
        var remaining = 0
        var allowance = 0L
        var elapsedMillis = 0L
        var savedBytes = 0L
        var transformed = 0
        var decision = HarnessAdaptationDecision("ORIGINAL", "No task contract selected")
        val evaluated = mutableMapOf<String, HarnessAdaptationEvidence>()
        val entries = mutableMapOf<String, String>()
        var disabled = false
    }

    init {
        require(runner.language == HarnessLanguage.KOTLIN && compatibility.isNotBlank())
        require(inputMicroUsdPerMiB == null || inputMicroUsdPerMiB in 0..1_000_000_000)
    }

    private fun lock(id: String) = locks[id.hashCode() and 255]

    private fun session(request: ToolExecutionRequest): Session {
        require(request.sessionId.isNotBlank() && request.sessionId != "unknown")
        require(request.origin == ToolCallOrigin.AGENT && request.dataTrust == PolicyDataTrust.TRUSTED) { "Adaptive LAB requires a trusted local owner invocation" }
        val scope = SessionHarness.sha256(request.workspaceRelativePath.orEmpty())
        return sessions.getOrPut(request.sessionId) { Session(scope, request.runId) }.also {
            require(it.scope == scope && it.run == request.runId) { "Session task scope changed; start a new run" }
        }
    }

    override suspend fun adapt(
        request: ToolExecutionRequest,
        args: HarnessAdaptationArguments,
    ): String =
        lock(request.sessionId).withLock {
            guarded {
                val current = session(request)
                when (args.operation) {
                    "catalog" -> {
                        Json.encodeToString(library.entries(current.scope).map { it.copy(source = "") })
                    }

                    "invalidate" -> {
                        library.invalidate(current.scope, args.entryId, "owner_invalidated")
                        // Existing sessions consult the entry's status before each subsequent observation.
                        record(request, current, HarnessAdaptationDecision("ORIGINAL", "Library version invalidated", args.entryId))
                    }

                    "restore" -> {
                        val entry = library.entries(current.scope).firstOrNull { it.id == args.entryId } ?: error("Unknown scoped library entry")
                        require(entry.compatibility == compatibility && entry.hash == SessionHarness.sha256(entry.source)) { "Incompatible or corrupted version" }
                        val revision = HarnessRevision("restore", request.sessionId, null, entry.source, entry.hash, entry.toolName, 0, language = language)
                        val evidence = timed(current) { evaluator.evaluate(revision) }
                        require(evidence.useful) { "Version failed current evaluation" }
                        val restored = library.publish(current.scope, revision, compatibility, evidence)
                        record(request, current, HarnessAdaptationDecision("ORIGINAL", "Re-evaluated as new library version; decide before reuse", restored.id))
                    }

                    "decide" -> {
                        decide(request, args, current)
                    }

                    else -> {
                        error("Unknown adaptation operation")
                    }
                }
            }
        }

    private suspend fun decide(
        request: ToolExecutionRequest,
        args: HarnessAdaptationArguments,
        current: Session,
    ): String {
        require(args.remainingObservations in 0..100 && args.maxAddedLatencyMillis in 0..300_000)
        require(args.toolName in setOf("read_file", "json_query", "harness_fixture"))
        // A task contract is explicit; a large observation alone cannot authorize throwing fields away.
        if (args.contractId != HarnessAdaptationEvaluator.CONTRACT) {
            current.disabled = true
            delegate.command(request, "disable", HarnessArguments())
            return record(request, current, HarnessAdaptationDecision("ORIGINAL", "Unsupported or absent task contract"))
        }
        if (current.contract != null && (current.contract != args.contractId || current.tool != args.toolName)) {
            error("A session can select only one adaptation target; start a new session to change it")
        }
        current.contract = args.contractId
        current.tool = args.toolName
        current.remaining = args.remainingObservations
        current.allowance = args.maxAddedLatencyMillis
        val sizes = current.observations[args.toolName]?.values?.toList().orEmpty()
        if (sizes.count { it >= 1024 } < 2 || args.remainingObservations < 4 || args.maxAddedLatencyMillis == 0L) {
            current.disabled = true
            delegate.command(request, "disable", HarnessArguments())
            return record(request, current, HarnessAdaptationDecision("ORIGINAL", "Need two distinct large observations, four remaining observations and a latency allowance"))
        }
        val available = library.entries(current.scope).filter {
            it.active && it.contract == args.contractId && it.toolName == args.toolName && it.compatibility == compatibility &&
                it.evidence.suite == HarnessAdaptationEvaluator.SUITE && it.evidence.useful && it.hash == SessionHarness.sha256(it.source)
        }
        val average = sizes.average()
        val entry = available.minByOrNull { it.evidence.evaluationMillis + 2 * it.evidence.coldMillis + args.remainingObservations * it.evidence.warmMillis }
        val savingFraction = entry?.evidence?.let { 1.0 - it.presentedBytes.toDouble() / it.baselineBytes } ?: 0.5
        // One future delivery per observation. No claim about provider tokenization or prefix reuse.
        val saved = (average * savingFraction * args.remainingObservations).toLong()
        val overhead = if (entry == null) {
            15_000L + args.remainingObservations * 1_000L
        } else {
            entry.evidence.evaluationMillis + 2 * entry.evidence.coldMillis + args.remainingObservations * entry.evidence.warmMillis
        }
        if (saved <= 7_000 || overhead + current.elapsedMillis > current.allowance) {
            current.disabled = true
            delegate.command(request, "disable", HarnessArguments())
            return record(request, current, HarnessAdaptationDecision("ORIGINAL", "Estimated savings too small or execution exceeds latency allowance", estimatedSavedInputBytes = saved, estimatedAddedMillis = overhead))
        }
        current.disabled = false
        if (entry == null) {
            return record(request, current, decision("CREATE", "No compatible evaluated version; propose Kotlin, then evaluate and activate. Setup estimate excludes model generation latency and output-token cost", null, saved, overhead))
        }
        // Re-run checks on the current runner before importing into this session.
        val sourceRevision = HarnessRevision("reuse", request.sessionId, null, entry.source, entry.hash, entry.toolName, 0, language = language)
        val evidence = timed(current) { evaluator.evaluate(sourceRevision) }
        if (!evidence.useful) {
            library.invalidate(current.scope, entry.id, "revalidation_failed")
            current.disabled = true
            return record(request, current, HarnessAdaptationDecision("ORIGINAL", "Saved version failed revalidation", entry.id))
        }
        if (current.elapsedMillis >= current.allowance) {
            current.disabled = true
            return record(request, current, HarnessAdaptationDecision("ORIGINAL", "Evaluation exhausted latency allowance", entry.id))
        }
        val state = store.state(request.sessionId)
        val proposed = delegate.command(request, "propose", HarnessArguments(source = entry.source, toolName = entry.toolName, baseRevision = state.active))
        val revision = Json.decodeFromString<HarnessRevision>(proposed)
        current.evaluated[revision.id] = evidence
        current.entries[revision.id] = entry.id
        val validation = timed(current) { delegate.command(request, "evaluate", HarnessArguments(revision = revision.id)) }
        require(validation.contains("passed=true")) { "Session validation failed" }
        require(current.elapsedMillis < current.allowance) { "Session validation exhausted latency allowance" }
        val activation = delegate.command(request, "activate", HarnessArguments(revision = revision.id))
        require(activation.startsWith("Pending")) { activation }
        return record(request, current, decision("REUSE", "Version revalidated; activation at next step", entry.id, saved, overhead))
    }

    private fun decision(
        choice: String,
        reason: String,
        entry: String?,
        saved: Long,
        overhead: Long,
    ) = HarnessAdaptationDecision(
        choice,
        reason,
        entry,
        saved,
        overhead,
        inputMicroUsdPerMiB?.let { saved * it / 1_048_576 },
    )

    override suspend fun command(
        request: ToolExecutionRequest,
        operation: String,
        args: HarnessArguments,
    ): String =
        lock(request.sessionId).withLock {
            guarded {
                val current = session(request)
                when (operation) {
                    "inspect" -> {
                        delegate.command(request, operation, args) + "\nAdaptive LAB: call harness_adapt decide with the explicit task contract before proposing. " +
                            "decision=${Json.encodeToString(current.decision)}; nativeMillis=${current.elapsedMillis}; savedObservationBytes=${current.savedBytes}; transformed=${current.transformed}. Library evidence is finite regression coverage; provider savings and model generation latency are not measured."
                    }

                    "propose" -> {
                        require(current.contract != null && !current.disabled && current.decision.choice == "CREATE" && args.toolName == current.tool) { "Select CREATE for this task and target first" }
                        require(current.elapsedMillis < current.allowance) { "Latency allowance exhausted" }
                        delegate.command(request, operation, args)
                    }

                    "evaluate" -> {
                        require(current.contract != null && !current.disabled && current.elapsedMillis < current.allowance)
                        val revision = store.get(request.sessionId, args.revision)
                        require(revision.toolName == current.tool && revision.language == language && revision.hash == SessionHarness.sha256(revision.source))
                        current.evaluated.remove(revision.id)
                        val priorEntry = current.entries.remove(revision.id)
                        store.validate(request.sessionId, revision.id, false)
                        val evidence = timed(current) { evaluator.evaluate(revision) }
                        current.evaluated[revision.id] = evidence
                        if (!evidence.useful) {
                            if (priorEntry != null) library.invalidate(current.scope, priorEntry, "revalidation_failed")
                            store.validate(request.sessionId, revision.id, false)
                            store.event(request.sessionId, request.runId, revision.id, "adaptation_rejected:${Json.encodeToString(evidence)}")
                            "revision=${revision.id}; passed=false; evidence=${Json.encodeToString(evidence)}"
                        } else {
                            val result = timed(current) { delegate.command(request, operation, args) }
                            if (result.contains("passed=true")) {
                                val entry = library.publish(current.scope, revision, compatibility, evidence)
                                current.entries[revision.id] = entry.id
                                store.event(request.sessionId, request.runId, revision.id, "adaptation_published:${entry.id}:${Json.encodeToString(evidence)}")
                            }
                            "$result; evidence=${Json.encodeToString(evidence)}"
                        }
                    }

                    "activate", "rollback" -> {
                        val id = if (operation == "rollback") store.state(request.sessionId).previous else args.revision
                        require(!current.disabled && current.evaluated[id]?.useful == true && current.elapsedMillis < current.allowance) { "Current task evaluation and available latency allowance required" }
                        require(library.entries(current.scope).any { it.id == current.entries[id] && it.active }) { "Library version is no longer active" }
                        delegate.command(request, operation, args)
                    }

                    "disable" -> {
                        current.disabled = true
                        delegate.command(request, operation, args)
                    }

                    else -> {
                        error("Unknown harness operation")
                    }
                }
            }
        }

    override suspend fun beginStep(
        sessionId: String,
        runId: String?,
        stepId: String?,
    ) = lock(sessionId).withLock {
        val current = sessions[sessionId]
        if (current == null || current.run != runId) {
            // Never restore a persisted active revision without a fresh task contract/evaluation.
            if (current != null || store.state(sessionId).active != null || store.state(sessionId).pending != null) delegate.endSession(sessionId)
            sessions.remove(sessionId)
            delegate.beginStep(sessionId, runId, stepId)
        } else {
            val pending = store.state(sessionId).pending
            val pendingEntry = current.entries[pending]
            if (pendingEntry != null && (current.disabled || current.elapsedMillis >= current.allowance || library.entries(current.scope).none { it.id == pendingEntry && it.active })) {
                delegate.endSession(sessionId)
                current.disabled = true
                current.decision = HarnessAdaptationDecision("ORIGINAL", "Pending activation revoked or task allowance exhausted", pendingEntry)
                store.event(sessionId, runId, pending, "adaptation_decision:${Json.encodeToString(current.decision)}")
                delegate.beginStep(sessionId, runId, stepId)
                return@withLock
            }
            timed(current) { delegate.beginStep(sessionId, runId, stepId) }
            if (pendingEntry != null && store.state(sessionId).active != pending) {
                library.invalidate(current.scope, pendingEntry, "activation_failed_or_revoked")
                current.disabled = true
                current.decision = HarnessAdaptationDecision("ORIGINAL", "Activation failed or was revoked; original observations retained", pendingEntry)
                store.event(sessionId, runId, pending, "adaptation_decision:${Json.encodeToString(current.decision)}")
            }
        }
    }

    override suspend fun process(
        request: ToolExecutionRequest,
        raw: String,
    ): ProcessedObservation =
        lock(request.sessionId).withLock {
            // Remote/untrusted invocations cannot feed the adaptation decision or execute a saved script.
            if (request.origin != ToolCallOrigin.AGENT || request.dataTrust != PolicyDataTrust.TRUSTED) return@withLock ProcessedObservation(raw)
            val current = session(request)
            if (request.toolName in setOf("read_file", "json_query", "harness_fixture") && raw.encodeToByteArray().size <= 256 * 1024) {
                val observations = current.observations.getOrPut(request.toolName) { linkedMapOf() }
                observations[SessionHarness.sha256(raw)] = raw.encodeToByteArray().size
                if (observations.size > 32) observations.remove(observations.keys.first())
            }
            val active = store.state(request.sessionId).active
            if (current.disabled || current.contract == null || request.toolName != current.tool || active == null) return@withLock ProcessedObservation(raw)
            val entryId = current.entries[active]
            if (entryId == null || library.entries(current.scope).none { it.id == entryId && it.active } || current.evaluated[active]?.useful != true ||
                current.elapsedMillis >= current.allowance || current.remaining <= 0
            ) {
                current.disabled = true
                record(request, current, HarnessAdaptationDecision("ORIGINAL", "Version invalidated, task allowance exhausted, or no current evaluation", entryId))
                return@withLock ProcessedObservation(raw)
            }
            current.remaining--
            val result = timed(current) { delegate.process(request, raw) }
            if (store.state(request.sessionId).active != active ||
                (result.revision != null && result.text.substringBeforeLast("\n[harness revision=") != expected(raw))
            ) {
                library.invalidate(current.scope, entryId, "runtime_contract_failure")
                current.disabled = true
                delegate.command(request, "disable", HarnessArguments())
                record(request, current, HarnessAdaptationDecision("ORIGINAL", "Runtime failure; original preserved and library version invalidated", entryId))
                return@withLock ProcessedObservation(raw)
            }
            if (result.revision != null) {
                current.transformed++
                current.savedBytes += raw.encodeToByteArray().size - result.text.encodeToByteArray().size
            }
            result
        }

    override suspend fun revisionKey(sessionId: String) = delegate.revisionKey(sessionId)

    override suspend fun endSession(sessionId: String) =
        lock(sessionId).withLock {
            val current = sessions.remove(sessionId)
            try {
                if (current != null) store.event(sessionId, current.run, null, "adaptation_summary:nativeMillis=${current.elapsedMillis};savedObservationBytes=${current.savedBytes};transformed=${current.transformed};decision=${Json.encodeToString(current.decision)}")
            } finally {
                delegate.endSession(sessionId)
            }
        }

    private fun record(
        request: ToolExecutionRequest,
        current: Session,
        decision: HarnessAdaptationDecision,
    ): String {
        current.decision = decision
        val json = Json.encodeToString(decision)
        store.event(request.sessionId, request.runId, null, "adaptation_decision:$json")
        return json
    }

    private suspend fun <T> timed(
        current: Session,
        block: suspend () -> T,
    ): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            current.elapsedMillis += ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(0)
        }
    }

    private suspend fun guarded(block: suspend () -> String): String =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            "[ERROR] ${error.message}"
        }

    companion object {
        const val AGENT_ITERATIONS = 24

        /** A narrow deterministic runtime oracle bounds what an experimental processor may discard. */
        internal fun expected(raw: String): String {
            val json = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
            if (json != null) {
                val answer = (json as? JsonObject)?.get("answer") ?: return raw
                return if (answer is JsonPrimitive) answer.content else answer.toString()
            }
            val lines = raw.lines()
            if (lines.size == 2 && lines[0].split(',').contains("answer")) {
                return lines[1].split(',').getOrNull(lines[0].split(',').indexOf("answer")) ?: raw
            }
            return Regex("^DATA answer=([^\\s,]+)$").matchEntire(raw)?.groupValues?.get(1) ?: raw
        }
    }
}
