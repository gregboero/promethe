package dev.promethe.gateway.di

import dev.promethe.core.*
import dev.promethe.core.hooks.HookManager
import dev.promethe.db.PrometheDatabaseApi
import dev.promethe.gateway.OmnichannelGateway
import dev.promethe.gateway.GatewaySecurityConfig
import dev.promethe.gateway.auth.OwnerAuthService
import dev.promethe.core.security.SecretCipher
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Koin module for the Ktor gateway.
 *
 * Depends on: coreModule + agentModule + orchestratorModule
 * (registered via KoinBootstrap.initFromStack)
 *
 * Usage in Ktor:
 * ```kotlin
 * install(Koin) {
 *     modules(gatewayModule(port, apiKey, ...))
 * }
 * ```
 */
fun gatewayModule(
    port: Int = 8080,
    apiKey: String = "",
) = module {
    // Named gateway config values
    single(named("gateway.port")) { port }
    single(named("gateway.apiKey")) { apiKey }
    single { GatewaySecurityConfig.fromConfig() }
    single {
        val securityConfig = get<GatewaySecurityConfig>()
        val cipher = SecretCipher.requireForEnabledFeatures()
        if (cipher != null) {
            OwnerAuthService(get(), securityConfig.remoteSessionTtlMinutes, csrfSigner = cipher)
        } else {
            OwnerAuthService(get(), securityConfig.remoteSessionTtlMinutes)
        }
    }

    // OmnichannelGateway — the main Ktor server entry point
    single {
        val securityConfig: GatewaySecurityConfig = get()
        // Fail before the gateway starts. The master key is environment-only and
        // is mandatory whenever persisted remote sessions or OAuth are enabled.
        val masterCipher = SecretCipher.requireForEnabledFeatures()

        // OAuth is opt-in: OAUTH_ENABLED=true + at least one configured provider
        // (OAUTH_<PROVIDER>_CLIENT_ID/_SECRET). Otherwise the routes never mount.
        val configProvider = dev.promethe.core.config.ConfigProvider.get()
        val oauthManager =
            if (configProvider.getBoolean("OAUTH_ENABLED", false)) {
                require(securityConfig.remoteAccessEnabled) { "OAUTH_ENABLED requires REMOTE_ACCESS_ENABLED=true so an owner session can authorize providers" }
                val redirectUri = configProvider.get("OAUTH_REDIRECT_URI", "").trim()
                require(redirectUri.isNotBlank()) { "OAUTH_REDIRECT_URI must be configured when OAUTH_ENABLED=true" }
                require(redirectUri == "${securityConfig.publicBaseUrl}/auth/oauth/callback") {
                    "OAUTH_REDIRECT_URI must equal PUBLIC_BASE_URL/auth/oauth/callback"
                }
                val providers = dev.promethe.gateway.auth.OAuthProviderConfigs.fromConfig()
                require(providers.isNotEmpty()) { "OAUTH_ENABLED requires GitHub and/or Google OAuth client credentials" }
                dev.promethe.gateway.auth.OAuthManager(
                    database = get(),
                    httpClient = get(),
                    providers = providers,
                    redirectUri = redirectUri,
                    cipher = requireNotNull(masterCipher),
                )
            } else {
                null
            }

        OmnichannelGateway(
            agent = get(),
            database = get(),
            llmAdapter = get(),
            feedbackCollector = get(),
            mcpBridge = get(),
            mcpElicitationBroker = get(),
            memoryLayer = get(),
            taskScheduler = getOrNull(),
            hookManager = getOrNull(),
            pluginLoader = getOrNull(),
            orchestrator = getOrNull(),
            config = getOrNull(),
            approvalGate = getOrNull(),
            skillLoader = getOrNull(),
            skillWriter = getOrNull(),
            skillCurator = getOrNull(),
            skillsDir = getOrNull(named("skillsDir")),
            port = port,
            apiKey = apiKey,
            securityConfig = securityConfig,
            ownerAuthService = get(),
            actionExecutor = get(),
            sandboxManager = get(),
            sandboxRuntimePolicy = get(),
            localCodingAgentService = get(),
            oauthManager = oauthManager,
        )
    }
}
