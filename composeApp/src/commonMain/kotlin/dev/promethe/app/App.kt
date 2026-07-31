@file:Suppress("DEPRECATION")

package dev.promethe.app

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.*
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import dev.promethe.app.config.AppCredentials
import dev.promethe.app.config.AppLocale
import dev.promethe.app.config.CredentialManager
import dev.promethe.app.config.LocaleProvider
import dev.promethe.app.navigation.BackStackPersistence
import dev.promethe.app.navigation.PrometheRoute
import dev.promethe.app.network.PrometheClient
import dev.promethe.app.screens.*
import dev.promethe.app.theme.PrometheTheme
import dev.promethe.api.LoginClientKind
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import promethe.composeapp.generated.resources.*

/** Duration in ms for navigation transition animations. */
private const val NAV_ANIM_DURATION = 300

/** Fraction of width used for the horizontal slide offset. */
private const val SLIDE_OFFSET_FRACTION = 4

private data class NavItem(
    val id: String,
    val icon: ImageVector,
    val labelRes: StringResource,
    val route: PrometheRoute,
)

private val navItems =
    listOf(
        NavItem("sessions", Icons.Default.Forum, Res.string.nav_chat, PrometheRoute.Sessions),
        NavItem("agents", Icons.Default.SmartToy, Res.string.nav_agents, PrometheRoute.Agents),
        NavItem("channels", Icons.Filled.Chat, Res.string.nav_channels, PrometheRoute.Channels),
        NavItem("monitor", Icons.Default.Monitor, Res.string.nav_monitor, PrometheRoute.Monitor),
        NavItem("scheduler", Icons.Default.Schedule, Res.string.nav_scheduler, PrometheRoute.Scheduler),
        NavItem("mcp", Icons.Default.Extension, Res.string.nav_mcp, PrometheRoute.Mcp),
        NavItem("plugins", Icons.Default.Power, Res.string.nav_plugins, PrometheRoute.Plugins),
        NavItem("knowledge", Icons.Default.Search, Res.string.nav_rag, PrometheRoute.Knowledge),
        NavItem("orchestrator", Icons.Default.Forum, Res.string.nav_multi_agent, PrometheRoute.Orchestrator),
        NavItem("stats", Icons.Default.Analytics, Res.string.nav_stats, PrometheRoute.Stats),
        NavItem("memory", Icons.Default.Psychology, Res.string.nav_memory, PrometheRoute.Memory),
        NavItem("gepa", Icons.Default.AutoFixHigh, Res.string.nav_gepa, PrometheRoute.Gepa),
        NavItem("skills", Icons.Default.MenuBook, Res.string.nav_skills, PrometheRoute.Skills),
        NavItem("goal", Icons.Default.TrackChanges, Res.string.nav_goal, PrometheRoute.Goal),
        NavItem("settings", Icons.Default.Settings, Res.string.nav_settings, PrometheRoute.Settings),
    )

// Mobile bottom bar: only show 5 key items
private val mobileNavItems =
    listOf(
        NavItem("sessions", Icons.Default.Forum, Res.string.nav_chat, PrometheRoute.Sessions),
        NavItem("agents", Icons.Default.SmartToy, Res.string.nav_agents, PrometheRoute.Agents),
        NavItem("monitor", Icons.Default.Monitor, Res.string.nav_monitor, PrometheRoute.Monitor),
        NavItem("channels", Icons.Filled.Chat, Res.string.nav_channels, PrometheRoute.Channels),
        NavItem("settings", Icons.Default.Settings, Res.string.nav_settings, PrometheRoute.Settings),
    )

@Composable
fun App(
    apiKey: String = "",
    isFirstRun: Boolean = false,
    initialRoute: PrometheRoute = PrometheRoute.Sessions,
    browserSessionHint: Boolean = false,
    isBrowserClient: Boolean = false,
    onNavigate: ((String) -> Unit)? = null,
    externalNavigation: kotlinx.coroutines.flow.Flow<PrometheRoute>? = null,
    onSetupComplete: ((String, String, String, String, Map<String, String>, String, String) -> Unit)? = null,
) {
    val currentTheme = remember { mutableStateOf(CredentialManager.load()?.theme ?: "system") }
    val currentLanguage = remember { mutableStateOf(CredentialManager.load()?.language ?: "system") }
    PrometheTheme(theme = currentTheme.value) {
        LocaleProvider(
            initialLanguage = currentLanguage.value,
            onLocaleChanged = { locale ->
                currentLanguage.value = locale.code
                // Persist the language choice
                CredentialManager.load()?.copy(language = locale.code)?.let { CredentialManager.save(it) }
            },
        ) {
            var showSetup by remember { mutableStateOf(isFirstRun) }
            var showLogin by remember { mutableStateOf(false) }
            // Navigation 3: user-owned back stack — restore from persistence or use initial
            val backStack = remember {
                val restored = BackStackPersistence.restore()
                mutableStateListOf<PrometheRoute>(*(restored ?: listOf(initialRoute)).toTypedArray())
            }
            // The API key is local-only. Remote session tokens live solely in this
            // composition and are dropped on restart or expiry.
            var effectiveApiKey by remember { mutableStateOf(apiKey.ifBlank { CredentialManager.load()?.apiKey ?: "" }) }
            var remoteSessionToken by remember { mutableStateOf<String?>(null) }
            var browserSessionActive by remember { mutableStateOf(browserSessionHint) }
            var browserCsrfToken by remember { mutableStateOf<String?>(null) }
            var effectiveGatewayUrl by remember {
                mutableStateOf(CredentialManager.load()?.gatewayUrl ?: "http://localhost:8080")
            }
            val activeCredential = remoteSessionToken ?: effectiveApiKey
            val client = remember(effectiveGatewayUrl, activeCredential, browserCsrfToken, isBrowserClient) {
                PrometheClient(
                    baseUrl = effectiveGatewayUrl,
                    apiKey = activeCredential,
                    clientKind = if (isBrowserClient) LoginClientKind.BROWSER else LoginClientKind.NATIVE,
                    csrfToken = browserCsrfToken,
                    onUnauthorized = {
                        remoteSessionToken = null
                        browserSessionActive = false
                        browserCsrfToken = null
                        CredentialManager.clearRemoteSessionHint()
                        showLogin = true
                    },
                )
            }

            LaunchedEffect(browserSessionActive, effectiveGatewayUrl) {
                if (isBrowserClient && browserSessionActive && browserCsrfToken == null) {
                    runCatching { client.getAuthSession() }
                        .onSuccess { session -> browserCsrfToken = session.csrfToken }
                        .onFailure {
                            browserSessionActive = false
                            CredentialManager.clearRemoteSessionHint()
                            showLogin = true
                        }
                }
            }

            // Derive current screen ID from top of back stack for nav selection
            val currentScreenId by remember {
                derivedStateOf {
                    backStack.lastOrNull()?.let { PrometheRoute.toId(it) } ?: "sessions"
                }
            }

            // Sync hash routing for WasmJs
            LaunchedEffect(currentScreenId) {
                onNavigate?.invoke(currentScreenId)
            }

            // Persist back stack on every change
            LaunchedEffect(Unit) {
                snapshotFlow { backStack.toList() }.collect { routes ->
                    BackStackPersistence.save(routes)
                }
            }

            // Receive external navigation (e.g. browser back/forward or manual hash edit)
            LaunchedEffect(externalNavigation) {
                externalNavigation?.collect { route ->
                    if (backStack.lastOrNull() != route) {
                        backStack.clear()
                        backStack.add(route)
                    }
                }
            }

            // If no API key and not first run setup → show login for remote devices
            LaunchedEffect(effectiveApiKey, remoteSessionToken, browserSessionActive) {
                if (effectiveApiKey.isBlank() && remoteSessionToken == null && !browserSessionActive && !isFirstRun) {
                    showLogin = true
                }
            }

            if (showSetup) {
                SetupScreen(onSetupComplete = { provider, key, model, localUrl, contextFiles, remUser, remPass ->
                    onSetupComplete?.invoke(provider, key, model, localUrl, contextFiles, remUser, remPass)
                    // Reload API key from saved credentials
                    effectiveApiKey = CredentialManager.load()?.apiKey ?: ""
                    effectiveGatewayUrl = CredentialManager.load()?.gatewayUrl ?: "http://localhost:8080"
                    showSetup = false
                })
                return@LocaleProvider
            }

            if (showLogin) {
                LoginScreen(
                    initialGatewayUrl = CredentialManager.load()?.lastRemoteGatewayUrl?.ifBlank { effectiveGatewayUrl } ?: effectiveGatewayUrl,
                    clientKind = if (isBrowserClient) LoginClientKind.BROWSER else LoginClientKind.NATIVE,
                    onLoginSuccess = { sessionToken, gatewayUrl, csrfToken ->
                        CredentialManager.load()?.copy(lastRemoteGatewayUrl = gatewayUrl)?.let(CredentialManager::save)
                        remoteSessionToken = sessionToken
                        browserSessionActive = isBrowserClient
                        browserCsrfToken = csrfToken
                        effectiveGatewayUrl = gatewayUrl
                        showLogin = false
                    },
                )
                return@LocaleProvider
            }

            val colors = MaterialTheme.colorScheme

            // Helper: navigate to a top-level tab (clears back stack to just this route)
            val navigateToTab: (PrometheRoute) -> Unit = { route ->
                backStack.clear()
                backStack.add(route)
            }

            BoxWithConstraints(
                Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.isCtrlPressed) {
                        // Ctrl+1..9 → navigate to tab
                        val tabIndex = when (event.key) {
                            Key.One -> 0
                            Key.Two -> 1
                            Key.Three -> 2
                            Key.Four -> 3
                            Key.Five -> 4
                            Key.Six -> 5
                            Key.Seven -> 6
                            Key.Eight -> 7
                            Key.Nine -> 8
                            else -> -1
                        }
                        if (tabIndex in navItems.indices) {
                            navigateToTab(navItems[tabIndex].route)
                            true
                        } else {
                            when (event.key) {
                                Key.N -> {
                                    // Ctrl+N → new session (navigate to sessions)
                                    navigateToTab(PrometheRoute.Sessions)
                                    true
                                }

                                Key.R -> {
                                    // Ctrl+R → refresh (re-navigate to current tab)
                                    val currentRoute = backStack.lastOrNull()
                                    if (currentRoute != null) {
                                        val tabRoute = navItems.find {
                                            PrometheRoute.toId(it.route) == PrometheRoute.toId(currentRoute)
                                        }?.route ?: currentRoute
                                        backStack.clear()
                                        backStack.add(tabRoute)
                                    }
                                    true
                                }

                                else -> {
                                    false
                                }
                            }
                        }
                    } else if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        // Escape → go back (pop back stack)
                        if (backStack.size > 1) {
                            backStack.removeAt(backStack.lastIndex)
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                },
            ) {
                if (maxWidth > 600.dp) {
                    // ── Wide layout: side NavigationRail ──────────────────────
                    Row(Modifier.fillMaxSize()) {
                        NavigationRail(
                            modifier = Modifier.testTag("sidebar").width(72.dp),
                            containerColor = colors.surface,
                            contentColor = colors.onSurface,
                            header = null,
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                navItems.forEachIndexed { index, item ->
                                    // Add visual dividers between sections
                                    if (index == 3 || index == 7 || index == 11) {
                                        Spacer(Modifier.height(4.dp))
                                        HorizontalDivider(
                                            modifier = Modifier.width(48.dp).padding(horizontal = 12.dp),
                                            color = colors.outlineVariant.copy(alpha = 0.3f),
                                        )
                                        Spacer(Modifier.height(4.dp))
                                    }
                                    NavigationRailItem(
                                        selected = currentScreenId == item.id,
                                        onClick = { navigateToTab(item.route) },
                                        icon = { Icon(item.icon, contentDescription = stringResource(item.labelRes)) },
                                        label = { Text(stringResource(item.labelRes), style = MaterialTheme.typography.labelSmall) },
                                        modifier = Modifier
                                            .testTag("nav_${item.id}")
                                            .semantics { role = Role.Tab },
                                        colors =
                                            NavigationRailItemDefaults.colors(
                                                selectedIconColor = colors.primary,
                                                selectedTextColor = colors.primary,
                                                indicatorColor = colors.primaryContainer.copy(alpha = 0.3f),
                                                unselectedIconColor = colors.onSurfaceVariant,
                                                unselectedTextColor = colors.onSurfaceVariant,
                                            ),
                                    )
                                }
                            }
                        }

                        AppContent(
                            backStack = backStack,
                            client = client,
                            navigateToTab = navigateToTab,
                            onThemeChanged = { currentTheme.value = it },
                        )
                    }
                } else {
                    // ── Narrow layout: bottom NavigationBar ───────────────────
                    Scaffold(
                        bottomBar = {
                            NavigationBar(
                                containerColor = colors.surface,
                                contentColor = colors.onSurface,
                            ) {
                                mobileNavItems.forEach { item ->
                                    NavigationBarItem(
                                        selected = currentScreenId == item.id,
                                        onClick = { navigateToTab(item.route) },
                                        icon = { Icon(item.icon, contentDescription = stringResource(item.labelRes)) },
                                        label = { Text(stringResource(item.labelRes)) },
                                        modifier = Modifier
                                            .testTag("nav_mobile_${item.id}")
                                            .semantics { role = Role.Tab },
                                        colors =
                                            NavigationBarItemDefaults.colors(
                                                selectedIconColor = colors.primary,
                                                selectedTextColor = colors.primary,
                                                indicatorColor = colors.primaryContainer.copy(alpha = 0.3f),
                                                unselectedIconColor = colors.onSurfaceVariant,
                                                unselectedTextColor = colors.onSurfaceVariant,
                                            ),
                                    )
                                }
                            }
                        },
                    ) { innerPadding ->
                        AppContent(
                            backStack = backStack,
                            client = client,
                            navigateToTab = navigateToTab,
                            onThemeChanged = { currentTheme.value = it },
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppContent(
    backStack: MutableList<PrometheRoute>,
    client: PrometheClient,
    navigateToTab: (PrometheRoute) -> Unit,
    onThemeChanged: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Box(modifier = modifier.fillMaxSize().testTag("content_area")) {
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            transitionSpec = {
                // Forward navigation: slide in from right + fade in
                ContentTransform(
                    targetContentEnter = fadeIn(tween(NAV_ANIM_DURATION)) +
                        slideInHorizontally(tween(NAV_ANIM_DURATION)) { it / SLIDE_OFFSET_FRACTION },
                    initialContentExit = fadeOut(tween(NAV_ANIM_DURATION)) +
                        slideOutHorizontally(tween(NAV_ANIM_DURATION)) { -it / SLIDE_OFFSET_FRACTION },
                )
            },
            popTransitionSpec = {
                // Pop / back navigation: slide in from left + fade in
                ContentTransform(
                    targetContentEnter = fadeIn(tween(NAV_ANIM_DURATION)) +
                        slideInHorizontally(tween(NAV_ANIM_DURATION)) { -it / SLIDE_OFFSET_FRACTION },
                    initialContentExit = fadeOut(tween(NAV_ANIM_DURATION)) +
                        slideOutHorizontally(tween(NAV_ANIM_DURATION)) { it / SLIDE_OFFSET_FRACTION },
                )
            },
            entryProvider = { route ->
                when (route) {
                    is PrometheRoute.Sessions -> NavEntry(route) {
                        SessionsScreen(
                            client = client,
                            onSessionSelected = { sessionId ->
                                backStack.add(PrometheRoute.Chat(sessionId))
                            },
                        )
                    }

                    is PrometheRoute.Chat -> NavEntry(route) {
                        ChatScreen(
                            sessionId = route.sessionId,
                            client = client,
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }

                    is PrometheRoute.Agents -> NavEntry(route) {
                        AgentProfilesScreen(client)
                    }

                    is PrometheRoute.Monitor -> NavEntry(route) {
                        AgentMonitorScreen(client)
                    }

                    is PrometheRoute.Stats -> NavEntry(route) {
                        StatsScreen(client)
                    }

                    is PrometheRoute.Memory -> NavEntry(route) {
                        MemoryScreen(client)
                    }

                    is PrometheRoute.Gepa -> NavEntry(route) {
                        GepaDashboard(client)
                    }

                    is PrometheRoute.Skills -> NavEntry(route) {
                        SkillsScreen(client)
                    }

                    is PrometheRoute.Settings -> NavEntry(route) {
                        SettingsScreen(
                            client = client,
                            onNavigateToAgents = { navigateToTab(PrometheRoute.Agents) },
                            onCredentialsChanged = { creds -> onThemeChanged(creds.theme) },
                        )
                    }

                    is PrometheRoute.Tools -> NavEntry(route) {
                        ToolsScreen(client)
                    }

                    is PrometheRoute.Channels -> NavEntry(route) {
                        ChannelsScreen(client)
                    }

                    is PrometheRoute.Scheduler -> NavEntry(route) {
                        SchedulerScreen(client)
                    }

                    is PrometheRoute.Mcp -> NavEntry(route) {
                        McpScreen(client)
                    }

                    is PrometheRoute.Plugins -> NavEntry(route) {
                        PluginsScreen(client)
                    }

                    is PrometheRoute.Knowledge -> NavEntry(route) {
                        KnowledgeScreen(client)
                    }

                    is PrometheRoute.Orchestrator -> NavEntry(route) {
                        OrchestratorScreen(client)
                    }

                    is PrometheRoute.Goal -> NavEntry(route) {
                        GoalScreen(client)
                    }
                }
            },
        )
    }
}
