package dev.promethe.core

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale

private val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}

/**
 * ToolApprovalGate — human-in-the-loop approval for dangerous tool executions.
 *
 * Three modes (configured via [AgentConfig.approvalMode]):
 * - "auto": everything runs without asking (default, backward compatible)
 * - "dangerous": only tools in [DANGEROUS_TOOLS] require approval
 * - "all": every tool call requires approval before execution
 *
 * When approval is required, the gate stores a pending request and waits
 * for it to be approved/rejected via the REST API endpoint `POST /approval/{id}`.
 */
class ToolApprovalGate(
    private val config: AgentConfig,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ApprovalGate {
    /** Tools which can modify files, processes, configuration, or external state. */
    companion object {
        val DANGEROUS_TOOLS = ToolApprovalPolicy.dangerousTools

        /** These actions remain approval-gated even when approvalMode is auto. */
        val MANDATORY_APPROVAL_TOOLS = ToolApprovalPolicy.dangerousTools

        private const val MIN_GRANT_TTL_MS = 1_000L
        private const val DEFAULT_SESSION_TTL_MS = 8 * 60 * 60 * 1_000L
        private const val DEFAULT_PERSISTENT_TTL_MS = 30 * 24 * 60 * 60 * 1_000L
        private const val MAX_SESSION_TTL_MS = 24 * 60 * 60 * 1_000L
        private const val MAX_PERSISTENT_TTL_MS = 365 * 24 * 60 * 60 * 1_000L
        private const val MIN_KNOWN_SECRET_LENGTH = 8

        private val COMMAND_ARGUMENT_TOOLS =
            setOf(
                "execute_command",
                "shell",
                "docker",
                "process_manager",
                "plugin_hook",
            )
        private val SENSITIVE_VALUE_FLAGS =
            setOf(
                "--api-key",
                "--apikey",
                "--access-token",
                "--token",
                "--secret",
                "--password",
                "--authorization",
                "--cookie",
            )
        private val CREDENTIAL_ASSIGNMENT =
            Regex(
                "(?i)(?:^|[?&;,\\s])(?:--?)?(?:api[-_]?key|access[-_]?token|token|secret|password|authorization|cookie)\\s*[:=]\\s*\\S+",
            )
        private val BEARER_CREDENTIAL = Regex("(?i)(?:authorization\\s*:\\s*)?bearer\\s+\\S+")
        private val SECRET_TOKEN_SHAPE =
            Regex("(?i)(?:sk|gh[pousr]|xox[baprs]|AIza|eyJ)[-_A-Za-z0-9.]{8,}")
        private val URL_USER_INFO = Regex("(?i)https?://[^/@\\s]+:[^/@\\s]+@")
    }

    /** Pending approval requests. Thread-safe via synchronized map. */
    private val pendingApprovals = mutableMapOf<String, ApprovalRequest>()

    data class ApprovalRequest(
        val id: String,
        val toolName: String,
        val args: String,
        val argsDigest: String,
        val fingerprint: String,
        val sessionId: String,
        var status: ApprovalStatus = ApprovalStatus.PENDING,
        var resolvedScope: ApprovalGate.ApprovalScope? = null,
        var resolutionId: String? = null,
        val createdAt: Long = System.currentTimeMillis(),
    )

    enum class ApprovalStatus { PENDING, APPROVED, REJECTED, TIMEOUT }

    data class ApprovalGrant(
        val id: String,
        val fingerprint: String,
        val sessionId: String?,
        val scope: ApprovalGate.ApprovalScope,
        val allowed: Boolean,
        val createdAt: Long,
        val expiresAt: Long,
    )

    enum class ResponseResult {
        ACCEPTED,
        NOT_FOUND,
        PERSISTENT_REQUIRES_LOCAL_OWNER,
        INVALID_EXPIRATION,
    }

    enum class RevocationResult {
        REVOKED,
        NOT_FOUND,
        PERSISTENT_REQUIRES_LOCAL_OWNER,
    }

    private val approvalGrants = mutableMapOf<String, ApprovalGrant>()

    /**
     * Checks whether the given tool call needs approval and waits for it.
     * Returns immediately if approval mode is "auto" or tool is not dangerous.
     */
    override suspend fun check(
        toolName: String,
        args: String,
        sessionId: String,
    ): ApprovalGate.ApprovalResult {
        if (!requiresApproval(toolName)) {
            return ApprovalGate.ApprovalResult(allowed = true, reason = "auto-approved")
        }

        return awaitApproval(toolName, args, sessionId)
    }

    override suspend fun checkMandatory(
        toolName: String,
        args: String,
        sessionId: String,
    ): ApprovalGate.ApprovalResult = awaitApproval(toolName, args, sessionId)

    private suspend fun awaitApproval(
        toolName: String,
        args: String,
        sessionId: String,
    ): ApprovalGate.ApprovalResult {
        if (containsCommandSecret(toolName, args)) {
            return ApprovalGate.ApprovalResult(
                allowed = false,
                reason = "Command arguments contain credential material and were denied",
            )
        }

        val fingerprint = approvalFingerprint(toolName, args)
        findApplicableGrant(fingerprint, sessionId)?.let { grant ->
            return ApprovalGate.ApprovalResult(
                allowed = grant.allowed,
                reason = if (grant.allowed) "approved by ${grant.scope.name.lowercase()} grant" else "denied by ${grant.scope.name.lowercase()} grant",
                scope = grant.scope,
                approvalId = grant.id,
            )
        }

        val requestId = secureRequestId("approval")
        val request =
            ApprovalRequest(
                id = requestId,
                toolName = toolName,
                args = redactSensitiveArguments(args),
                argsDigest = sha256(args),
                fingerprint = fingerprint,
                sessionId = sessionId,
                createdAt = clock(),
            )

        synchronized(pendingApprovals) {
            pendingApprovals[requestId] = request
        }

        logger.info { "⏸ Waiting for approval: $toolName (id=$requestId)" }

        // Poll for approval/rejection
        val deadline = clock() + config.approvalTimeoutMs
        while (clock() < deadline) {
            val current = synchronized(pendingApprovals) { pendingApprovals[requestId] }
            when (current?.status) {
                ApprovalStatus.APPROVED -> {
                    cleanup(requestId)
                    logger.info { "✅ Approved: $toolName" }
                    return ApprovalGate.ApprovalResult(
                        allowed = true,
                        reason = "approved by user",
                        scope = current.resolvedScope,
                        approvalId = current.resolutionId,
                    )
                }

                ApprovalStatus.REJECTED -> {
                    cleanup(requestId)
                    logger.warn { "❌ Rejected: $toolName" }
                    return ApprovalGate.ApprovalResult(
                        allowed = false,
                        reason = "rejected by user",
                        scope = current.resolvedScope,
                        approvalId = current.resolutionId,
                    )
                }

                else -> {
                    delay(500)
                } // Poll every 500ms
            }
        }

        // Timeout
        cleanup(requestId)
        logger.warn { "⏰ Timeout: $toolName" }
        return ApprovalGate.ApprovalResult(allowed = false, reason = "approval timed out after ${config.approvalTimeoutMs / 1000}s")
    }

    /**
     * Called by the REST API to approve or reject a pending request.
     */
    fun respond(
        requestId: String,
        approved: Boolean,
    ): Boolean = respond(requestId, approved, ApprovalGate.ApprovalScope.ONCE) == ResponseResult.ACCEPTED

    fun respond(
        requestId: String,
        approved: Boolean,
        scope: ApprovalGate.ApprovalScope,
        expiresInMs: Long? = null,
        localOwner: Boolean = false,
    ): ResponseResult {
        if (scope == ApprovalGate.ApprovalScope.PERSISTENT && !localOwner) {
            return ResponseResult.PERSISTENT_REQUIRES_LOCAL_OWNER
        }

        val ttl = expiresInMs ?: defaultTtl(scope)
        if (scope != ApprovalGate.ApprovalScope.ONCE && ttl !in MIN_GRANT_TTL_MS..maxTtl(scope)) {
            return ResponseResult.INVALID_EXPIRATION
        }

        return synchronized(pendingApprovals) {
            val request = pendingApprovals[requestId]
                ?.takeIf { it.status == ApprovalStatus.PENDING }
                ?: return@synchronized ResponseResult.NOT_FOUND
            val resolutionId =
                if (scope == ApprovalGate.ApprovalScope.ONCE) {
                    request.id
                } else {
                    val grant =
                        ApprovalGrant(
                            id = secureRequestId("grant"),
                            fingerprint = request.fingerprint,
                            sessionId = request.sessionId.takeIf { scope == ApprovalGate.ApprovalScope.SESSION },
                            scope = scope,
                            allowed = approved,
                            createdAt = clock(),
                            expiresAt = clock() + ttl,
                        )
                    synchronized(approvalGrants) {
                        removeConflictingGrant(grant)
                        approvalGrants[grant.id] = grant
                    }
                    grant.id
                }
            request.resolvedScope = scope
            request.resolutionId = resolutionId
            request.status = if (approved) ApprovalStatus.APPROVED else ApprovalStatus.REJECTED
            ResponseResult.ACCEPTED
        }
    }

    /**
     * List all pending approval requests (for UI display).
     */
    fun listPending(): List<ApprovalRequest> =
        synchronized(pendingApprovals) {
            newestFirst(
                pendingApprovals.values.filter { it.status == ApprovalStatus.PENDING },
            ) { it.createdAt }
        }

    fun listGrants(): List<ApprovalGrant> =
        synchronized(approvalGrants) {
            pruneExpiredGrants()
            newestFirst(approvalGrants.values) { it.createdAt }
        }

    fun revoke(
        grantId: String,
        localOwner: Boolean = false,
    ): RevocationResult =
        synchronized(approvalGrants) {
            pruneExpiredGrants()
            val grant = approvalGrants[grantId] ?: return@synchronized RevocationResult.NOT_FOUND
            if (grant.scope == ApprovalGate.ApprovalScope.PERSISTENT && !localOwner) {
                return@synchronized RevocationResult.PERSISTENT_REQUIRES_LOCAL_OWNER
            }
            approvalGrants.remove(grantId)
            RevocationResult.REVOKED
        }

    private fun requiresApproval(toolName: String): Boolean =
        when (config.approvalMode) {
            "auto" -> false
            "dangerous" -> toolName in DANGEROUS_TOOLS
            "all" -> true
            else -> false
        }

    private fun cleanup(requestId: String) {
        synchronized(pendingApprovals) {
            pendingApprovals.remove(requestId)
        }
    }

    private fun findApplicableGrant(
        fingerprint: String,
        sessionId: String,
    ): ApprovalGrant? =
        synchronized(approvalGrants) {
            pruneExpiredGrants()
            val matching =
                approvalGrants.values.filter { grant ->
                    grant.fingerprint == fingerprint &&
                        (grant.scope == ApprovalGate.ApprovalScope.PERSISTENT || grant.sessionId == sessionId)
                }
            matching
                .filterNot { it.allowed }
                .maxByOrNull { it.createdAt }
                ?: matching.filter { it.allowed }.maxByOrNull { it.createdAt }
        }

    private fun removeConflictingGrant(grant: ApprovalGrant) {
        approvalGrants.entries.removeIf { (_, existing) ->
            existing.fingerprint == grant.fingerprint &&
                existing.scope == grant.scope &&
                existing.sessionId == grant.sessionId &&
                existing.allowed == grant.allowed
        }
    }

    private fun pruneExpiredGrants() {
        val now = clock()
        approvalGrants.entries.removeIf { (_, grant) -> grant.expiresAt <= now }
    }

    private fun defaultTtl(scope: ApprovalGate.ApprovalScope): Long =
        when (scope) {
            ApprovalGate.ApprovalScope.ONCE -> 0
            ApprovalGate.ApprovalScope.SESSION -> DEFAULT_SESSION_TTL_MS
            ApprovalGate.ApprovalScope.PERSISTENT -> DEFAULT_PERSISTENT_TTL_MS
        }

    private fun maxTtl(scope: ApprovalGate.ApprovalScope): Long =
        when (scope) {
            ApprovalGate.ApprovalScope.ONCE -> 0
            ApprovalGate.ApprovalScope.SESSION -> MAX_SESSION_TTL_MS
            ApprovalGate.ApprovalScope.PERSISTENT -> MAX_PERSISTENT_TTL_MS
        }

    // ══════════════════════════════════════════════════════════════
    //  Provider Choice — human-in-the-loop provider selection
    //  for multi-provider AI capabilities (image gen, TTS, etc.)
    // ══════════════════════════════════════════════════════════════

    /** Pending provider choice requests. */
    private val pendingProviderChoices = mutableMapOf<String, ProviderChoiceRequest>()

    /**
     * A request for the user to choose which AI provider to use
     * for a given capability (e.g., "Which image generator?").
     */
    data class ProviderChoiceRequest(
        val id: String,
        val capability: String,
        val capabilityLabel: String,
        val suggestedProviderId: String,
        val suggestedProviderName: String,
        val alternatives: List<ProviderOption>,
        val sessionId: String,
        var status: ApprovalStatus = ApprovalStatus.PENDING,
        var selectedProviderId: String? = null,
        val createdAt: Long = System.currentTimeMillis(),
    )

    data class ProviderOption(
        val id: String,
        val name: String,
    )

    data class ProviderChoiceResult(
        val approved: Boolean,
        val selectedProviderId: String?,
        val reason: String,
    )

    /**
     * Request provider choice from the user.
     * Creates a pending choice request and waits for the UI to respond.
     *
     * @param capability Human-readable capability name (e.g., "image-generation")
     * @param capabilityLabel Display label (e.g., "Génération d'images")
     * @param suggestedProviderId Default provider to suggest (e.g., "openai-dalle3")
     * @param suggestedProviderName Display name (e.g., "DALL-E 3 (OpenAI)")
     * @param alternatives Other available providers
     * @param sessionId Current chat session
     */
    suspend fun checkProvider(
        capability: String,
        capabilityLabel: String,
        suggestedProviderId: String,
        suggestedProviderName: String,
        alternatives: List<ProviderOption>,
        sessionId: String,
    ): ProviderChoiceResult {
        val requestId = secureRequestId("provider")
        val request = ProviderChoiceRequest(
            id = requestId,
            capability = capability,
            capabilityLabel = capabilityLabel,
            suggestedProviderId = suggestedProviderId,
            suggestedProviderName = suggestedProviderName,
            alternatives = alternatives,
            sessionId = sessionId,
            createdAt = clock(),
        )

        synchronized(pendingProviderChoices) {
            pendingProviderChoices[requestId] = request
        }

        logger.info { "⏸ Waiting for provider choice: $capability (id=$requestId, suggested=$suggestedProviderName)" }

        val deadline = clock() + config.approvalTimeoutMs
        while (clock() < deadline) {
            val current = synchronized(pendingProviderChoices) { pendingProviderChoices[requestId] }
            when (current?.status) {
                ApprovalStatus.APPROVED -> {
                    val selected = current.selectedProviderId ?: suggestedProviderId
                    cleanupProvider(requestId)
                    logger.info { "✅ Provider selected: $selected for $capability" }
                    return ProviderChoiceResult(
                        approved = true,
                        selectedProviderId = selected,
                        reason = "user selected $selected",
                    )
                }

                ApprovalStatus.REJECTED -> {
                    cleanupProvider(requestId)
                    logger.warn { "❌ Provider choice rejected for $capability" }
                    return ProviderChoiceResult(
                        approved = false,
                        selectedProviderId = null,
                        reason = "user rejected",
                    )
                }

                else -> {
                    delay(500)
                }
            }
        }

        cleanupProvider(requestId)
        logger.warn { "⏰ Provider choice timeout for $capability" }
        return ProviderChoiceResult(
            approved = false,
            selectedProviderId = null,
            reason = "provider choice timed out after ${config.approvalTimeoutMs / 1000}s",
        )
    }

    /**
     * Called by the REST API when the user selects a provider.
     */
    fun respondProvider(
        requestId: String,
        approved: Boolean,
        selectedProviderId: String? = null,
    ): Boolean {
        return synchronized(pendingProviderChoices) {
            val request = pendingProviderChoices[requestId] ?: return false
            request.status = if (approved) ApprovalStatus.APPROVED else ApprovalStatus.REJECTED
            request.selectedProviderId = selectedProviderId
            true
        }
    }

    /**
     * List all pending provider choice requests (for UI display).
     */
    fun listPendingProviders(): List<ProviderChoiceRequest> =
        synchronized(pendingProviderChoices) {
            newestFirst(
                pendingProviderChoices.values.filter { it.status == ApprovalStatus.PENDING },
            ) { it.createdAt }
        }

    private fun cleanupProvider(requestId: String) {
        synchronized(pendingProviderChoices) {
            pendingProviderChoices.remove(requestId)
        }
    }

    private fun secureRequestId(prefix: String): String {
        val random = ByteArray(18).also { SecureRandom().nextBytes(it) }
        return "$prefix-${Base64.getUrlEncoder().withoutPadding().encodeToString(random)}"
    }

    /** Avoids a lazily loaded comparator class while the Desktop app is running from Gradle class directories. */
    private inline fun <T> newestFirst(
        values: Collection<T>,
        createdAt: (T) -> Long,
    ): List<T> {
        val result = ArrayList<T>(values.size)
        for (value in values) {
            val timestamp = createdAt(value)
            var insertionIndex = 0
            while (insertionIndex < result.size && createdAt(result[insertionIndex]) >= timestamp) {
                insertionIndex++
            }
            result.add(insertionIndex, value)
        }
        return result
    }

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun approvalFingerprint(
        toolName: String,
        args: String,
    ): String {
        val canonicalArgs =
            runCatching { canonicalJson(Json.parseToJsonElement(args)) }
                .getOrElse { args }
        return sha256("promethe-approval-v1\u0000$toolName\u0000$canonicalArgs")
    }

    private fun canonicalJson(element: JsonElement): String =
        when (element) {
            is JsonObject -> {
                element.entries
                    .sortedBy { it.key }
                    .joinToString(prefix = "{", postfix = "}") { (key, value) ->
                        "${JsonPrimitive(key)}:${canonicalJson(value)}"
                    }
            }

            is JsonArray -> {
                element.joinToString(prefix = "[", postfix = "]") { canonicalJson(it) }
            }

            else -> {
                element.toString()
            }
        }

    private fun redactSensitiveArguments(value: String): String =
        runCatching {
            redactElement(parseArgumentObject(value)).toString()
        }.getOrElse { "[arguments unavailable; sha256=${sha256(value)}]" }

    private fun redactElement(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> {
                buildJsonObject {
                    element.forEach { (key, child) ->
                        put(key, if (isSensitiveKey(key)) JsonPrimitive("***") else redactElement(child))
                    }
                }
            }

            is JsonArray -> {
                buildJsonArray {
                    element.forEach { child ->
                        add(
                            if (child is JsonPrimitive && child.isString && looksSensitiveArgument(child.content)) {
                                JsonPrimitive("***")
                            } else {
                                redactElement(child)
                            },
                        )
                    }
                }
            }

            else -> {
                element
            }
        }

    private fun containsCommandSecret(
        toolName: String,
        value: String,
    ): Boolean {
        if (toolName !in COMMAND_ARGUMENT_TOOLS) return false
        val arguments =
            runCatching {
                parseArgumentObject(value)["arguments"]
                    ?.let { it as? JsonArray }
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    .orEmpty()
            }.getOrDefault(emptyList())
        if (arguments.isEmpty()) return false

        val knownSecrets =
            LiveProviderKeys.values
                .asSequence()
                .filter { it.length >= MIN_KNOWN_SECRET_LENGTH }
                .toList()
        return arguments.indices.any { index ->
            val argument = arguments[index]
            val previous = arguments.getOrNull(index - 1).orEmpty().lowercase(Locale.ROOT)
            knownSecrets.any(argument::contains) ||
                looksSensitiveArgument(argument) ||
                (previous in SENSITIVE_VALUE_FLAGS && argument.isNotBlank())
        }
    }

    private fun parseArgumentObject(value: String): JsonObject {
        val jsonValue = value.substringBefore("\n[sandbox-policy=")
        return Json.parseToJsonElement(jsonValue) as JsonObject
    }

    private fun looksSensitiveArgument(value: String): Boolean =
        CREDENTIAL_ASSIGNMENT.containsMatchIn(value) ||
            BEARER_CREDENTIAL.containsMatchIn(value) ||
            SECRET_TOKEN_SHAPE.matches(value) ||
            URL_USER_INFO.containsMatchIn(value)

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.ROOT).replace("-", "_")
        return normalized.contains("password") ||
            normalized.contains("secret") ||
            normalized.contains("token") ||
            normalized.contains("api_key") ||
            normalized == "authorization" ||
            normalized == "cookie"
    }
}
