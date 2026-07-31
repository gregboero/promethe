package dev.promethe.gateway.auth

import dev.promethe.core.PasswordHasher
import dev.promethe.core.security.SecretCipher
import dev.promethe.db.AuthSessionRow
import dev.promethe.db.PrometheDatabaseApi
import dev.promethe.db.RemoteOwnerRow
import dev.promethe.db.SecurityAuditLogRow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Single-owner authentication for an opted-in remote gateway.
 * Raw session tokens are returned once and only their SHA-256 digest is stored.
 */
class OwnerAuthService(
    private val database: PrometheDatabaseApi,
    private val sessionTtlMinutes: Long,
    private val csrfSigner: SecretCipher = ephemeralCipher(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class AuthenticatedSession(
        val sessionId: String,
        val ownerId: String,
        val expiresAt: Long,
    )

    private data class FailedLoginWindow(
        var failures: Int,
        var windowStartedAt: Long,
        var blockedUntil: Long = 0,
    )

    private val failedLogins = ConcurrentHashMap<String, FailedLoginWindow>()
    private val failureCleanupTicker = AtomicLong(0)

    suspend fun configureOwner(
        username: String,
        password: String,
        remoteAddress: String,
    ): Boolean {
        require(username.matches(USERNAME_PATTERN)) { "Username must contain 3-64 letters, digits, . _ or -" }
        require(password.length >= MIN_PASSWORD_LENGTH) { "Password must contain at least $MIN_PASSWORD_LENGTH characters" }

        val now = clock()
        val existing = database.getRemoteOwner()
        val (hash, _) = PasswordHasher.hash(password)
        database.upsertRemoteOwner(
            RemoteOwnerRow(
                id = OWNER_ID,
                username = username,
                passwordHash = hash,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
        database.revokeAuthSessionsForOwner(OWNER_ID, now)
        audit("remote_owner_configured", OWNER_ID, remoteAddress)
        return existing != null
    }

    suspend fun login(
        username: String,
        password: String,
        remoteAddress: String,
    ): LoginResult {
        val now = clock()
        if (isBlocked(remoteAddress, now)) {
            audit("login_throttled", "", remoteAddress)
            return LoginResult.Throttled
        }

        val owner = database.getRemoteOwner()
        if (owner == null || owner.username != username || !PasswordHasher.verify(password, owner.passwordHash, "")) {
            registerFailure(remoteAddress, now)
            audit("login_failed", username.take(MAX_AUDIT_ACTOR_LENGTH), remoteAddress)
            return LoginResult.InvalidCredentials
        }

        failedLogins.remove(remoteAddress)
        val rawToken = newToken()
        val expiresAt = now + sessionTtlMinutes.coerceIn(15, 1_440) * 60_000
        val session =
            AuthSessionRow(
                id = "session-${newId()}",
                ownerId = OWNER_ID,
                tokenHash = tokenHash(rawToken),
                createdAt = now,
                expiresAt = expiresAt,
                lastSeenAt = now,
            )
        database.insertAuthSession(session)
        audit("login_succeeded", OWNER_ID, remoteAddress)
        return LoginResult.Success(rawToken, session.id, expiresAt, csrfToken(session.id))
    }

    suspend fun authenticate(rawToken: String?): AuthenticatedSession? {
        if (rawToken.isNullOrBlank() || !rawToken.startsWith(TOKEN_PREFIX)) return null
        val now = clock()
        val session = database.getAuthSessionByTokenHash(tokenHash(rawToken)) ?: return null
        if (session.revokedAt != null || session.expiresAt <= now) return null
        database.touchAuthSession(session.id, now)
        return AuthenticatedSession(session.id, session.ownerId, session.expiresAt)
    }

    suspend fun revoke(
        rawToken: String?,
        remoteAddress: String,
    ) {
        val session = authenticate(rawToken) ?: return
        database.revokeAuthSession(session.sessionId, clock())
        audit("logout", session.ownerId, remoteAddress)
    }

    suspend fun ownerExists(): Boolean = database.getRemoteOwner() != null

    fun csrfToken(sessionId: String): String = CSRF_PREFIX + csrfSigner.sign("csrf:$sessionId")

    fun verifyCsrf(
        sessionId: String,
        suppliedToken: String?,
    ): Boolean {
        if (suppliedToken.isNullOrBlank() || !suppliedToken.startsWith(CSRF_PREFIX)) return false
        return MessageDigest.isEqual(
            suppliedToken.toByteArray(Charsets.UTF_8),
            csrfToken(sessionId).toByteArray(Charsets.UTF_8),
        )
    }

    suspend fun revokeAll(
        ownerId: String,
        remoteAddress: String,
    ) {
        database.revokeAuthSessionsForOwner(ownerId, clock())
        audit("sessions_revoked", ownerId, remoteAddress)
    }

    private suspend fun audit(
        event: String,
        actor: String,
        remoteAddress: String,
    ) {
        database.insertSecurityAuditLog(
            SecurityAuditLogRow(
                eventType = event,
                actor = actor,
                remoteAddress = remoteAddress.take(MAX_AUDIT_ADDRESS_LENGTH),
                createdAt = clock(),
            ),
        )
    }

    private fun isBlocked(
        address: String,
        now: Long,
    ): Boolean = failedLogins[address]?.blockedUntil?.let { it > now } ?: false

    private fun registerFailure(
        address: String,
        now: Long,
    ) {
        if (failureCleanupTicker.incrementAndGet() % FAILURE_CLEANUP_INTERVAL == 0L || failedLogins.size > MAX_FAILURE_TRACKERS) {
            failedLogins.entries.removeIf { (_, state) ->
                now - maxOf(state.windowStartedAt, state.blockedUntil) > FAILURE_RETENTION_MS
            }
            if (failedLogins.size > MAX_FAILURE_TRACKERS) {
                failedLogins.entries.minByOrNull { it.value.windowStartedAt }?.let { failedLogins.remove(it.key) }
            }
        }
        failedLogins.compute(address) { _, previous ->
            val state = previous ?: FailedLoginWindow(failures = 0, windowStartedAt = now)
            if (now - state.windowStartedAt > FAILURE_WINDOW_MS) {
                state.failures = 0
                state.windowStartedAt = now
                state.blockedUntil = 0
            }
            state.failures += 1
            if (state.failures >= MAX_FAILURES) state.blockedUntil = now + BLOCK_DURATION_MS
            state
        }
    }

    private fun newToken(): String = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) })

    private fun newId(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(SESSION_ID_BYTES).also { SecureRandom().nextBytes(it) })

    private fun tokenHash(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    sealed interface LoginResult {
        data class Success(
            val token: String,
            val sessionId: String,
            val expiresAt: Long,
            val csrfToken: String,
        ) : LoginResult

        data object InvalidCredentials : LoginResult

        data object Throttled : LoginResult
    }

    companion object {
        const val OWNER_ID = "owner"
        private const val TOKEN_PREFIX = "pss_"
        private const val CSRF_PREFIX = "pcs_"
        private const val TOKEN_BYTES = 32
        private const val SESSION_ID_BYTES = 18
        private const val MIN_PASSWORD_LENGTH = 12
        private const val MAX_FAILURES = 5
        private const val MAX_FAILURE_TRACKERS = 10_000
        private const val FAILURE_CLEANUP_INTERVAL = 256L
        private const val FAILURE_WINDOW_MS = 15 * 60_000L
        private const val BLOCK_DURATION_MS = 15 * 60_000L
        private const val FAILURE_RETENTION_MS = FAILURE_WINDOW_MS + BLOCK_DURATION_MS
        private const val MAX_AUDIT_ACTOR_LENGTH = 255
        private const val MAX_AUDIT_ADDRESS_LENGTH = 255
        private val USERNAME_PATTERN = Regex("[A-Za-z0-9._-]{3,64}")

        private fun ephemeralCipher(): SecretCipher {
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return SecretCipher.fromBase64Key(Base64.getEncoder().encodeToString(key))
        }
    }
}
