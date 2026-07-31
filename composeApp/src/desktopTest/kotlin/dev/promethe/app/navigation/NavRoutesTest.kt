package dev.promethe.app.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NavRoutesTest {
    // ── 1. All 14 top-level routes round-trip: fromId(toId(route)) == route ──

    private val topLevelRoutes: List<PrometheRoute> = listOf(
        PrometheRoute.Sessions,
        PrometheRoute.Agents,
        PrometheRoute.Monitor,
        PrometheRoute.Stats,
        PrometheRoute.Memory,
        PrometheRoute.Gepa,
        PrometheRoute.Settings,
        PrometheRoute.Tools,
        PrometheRoute.Channels,
        PrometheRoute.Scheduler,
        PrometheRoute.Mcp,
        PrometheRoute.Plugins,
        PrometheRoute.Knowledge,
        PrometheRoute.Orchestrator,
    )

    @Test
    fun topLevelRoutesRoundTrip() {
        for (route in topLevelRoutes) {
            val id = PrometheRoute.toId(route)
            val restored = PrometheRoute.fromId(id)
            assertEquals(route, restored, "Round-trip failed for $route (id=$id)")
        }
    }

    // ── 2. fromId with valid strings returns the correct route ──

    @Test
    fun fromIdReturnsCorrectRoute() {
        assertEquals(PrometheRoute.Sessions, PrometheRoute.fromId("sessions"))
        assertEquals(PrometheRoute.Agents, PrometheRoute.fromId("agents"))
        assertEquals(PrometheRoute.Monitor, PrometheRoute.fromId("monitor"))
        assertEquals(PrometheRoute.Stats, PrometheRoute.fromId("stats"))
        assertEquals(PrometheRoute.Memory, PrometheRoute.fromId("memory"))
        assertEquals(PrometheRoute.Gepa, PrometheRoute.fromId("gepa"))
        assertEquals(PrometheRoute.Settings, PrometheRoute.fromId("settings"))
        assertEquals(PrometheRoute.Tools, PrometheRoute.fromId("tools"))
        assertEquals(PrometheRoute.Channels, PrometheRoute.fromId("channels"))
        assertEquals(PrometheRoute.Scheduler, PrometheRoute.fromId("scheduler"))
        assertEquals(PrometheRoute.Mcp, PrometheRoute.fromId("mcp"))
        assertEquals(PrometheRoute.Plugins, PrometheRoute.fromId("plugins"))
        assertEquals(PrometheRoute.Knowledge, PrometheRoute.fromId("knowledge"))
        assertEquals(PrometheRoute.Orchestrator, PrometheRoute.fromId("orchestrator"))
    }

    // ── 3. fromId with unknown string returns null ──

    @Test
    fun fromIdUnknownReturnsNull() {
        assertNull(PrometheRoute.fromId("nonexistent"))
        assertNull(PrometheRoute.fromId("SESSIONS"))
        assertNull(PrometheRoute.fromId("Sessions"))
        assertNull(PrometheRoute.fromId("chat"))
    }

    // ── 4. fromId with empty string returns null ──

    @Test
    fun fromIdEmptyReturnsNull() {
        assertNull(PrometheRoute.fromId(""))
    }

    // ── 5. Chat route: toId(Chat("abc")) returns "sessions" ──

    @Test
    fun chatToIdReturnsSessions() {
        val chatRoute = PrometheRoute.Chat(sessionId = "abc")
        assertEquals("sessions", PrometheRoute.toId(chatRoute))
    }

    // ── 6. fromId does not produce Chat (Chat has no string ID mapping) ──

    @Test
    fun fromIdNeverReturnsChat() {
        // "sessions" maps to Sessions, not Chat
        val route = PrometheRoute.fromId("sessions")
        assertEquals(PrometheRoute.Sessions, route)
        // "chat" is not a recognised ID
        assertNull(PrometheRoute.fromId("chat"))
    }
}
