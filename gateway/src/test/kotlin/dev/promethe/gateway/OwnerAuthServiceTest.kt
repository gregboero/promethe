package dev.promethe.gateway

import dev.promethe.gateway.auth.OwnerAuthService
import dev.promethe.db.DatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class OwnerAuthServiceTest {
    @Test
    fun `opaque session expires and is never recoverable after revocation`() =
        runTest {
            var now = 1_700_000_000_000L
            val service = OwnerAuthService(DatabaseFactory.createInMemory(), sessionTtlMinutes = 15) { now }
            service.configureOwner("owner", "correct-horse-battery-staple", "127.0.0.1")
            val login = assertIs<OwnerAuthService.LoginResult.Success>(service.login("owner", "correct-horse-battery-staple", "127.0.0.1"))
            assertNotNull(service.authenticate(login.token))

            now += 16 * 60_000L
            assertNull(service.authenticate(login.token))

            val fresh = assertIs<OwnerAuthService.LoginResult.Success>(service.login("owner", "correct-horse-battery-staple", "127.0.0.1"))
            service.revoke(fresh.token, "127.0.0.1")
            assertNull(service.authenticate(fresh.token))
        }
}
