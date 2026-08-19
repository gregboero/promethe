package dev.promethe.gateway

import dev.promethe.api.CapabilityAvailability
import dev.promethe.api.CapabilityAuthentication
import dev.promethe.api.CapabilityDescriptor
import dev.promethe.api.CapabilityListResponse
import dev.promethe.api.CapabilityMaturity
import dev.promethe.api.providers.CertificationStatus
import dev.promethe.api.providers.ModelLifecycle
import dev.promethe.api.providers.ProviderAvailability
import dev.promethe.core.LiveProviderKeys
import dev.promethe.core.ProviderSecretRegistry
import dev.promethe.core.ToolApprovalPolicy
import dev.promethe.core.ToolRegistry
import dev.promethe.core.config.ConfigProvider
import dev.promethe.core.coding.LocalCodingAgentService
import dev.promethe.core.providers.Capability
import dev.promethe.core.providers.ProviderRegistry
import dev.promethe.gateway.voice.VoiceProviderRegistry
import dev.promethe.gateway.providers.ProviderCatalog
import dev.promethe.gateway.providers.ProviderCatalogSource
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.capabilityRoutes(
    providerCatalogSource: ProviderCatalogSource = staticProviderCatalogSource,
    localCodingAgentService: LocalCodingAgentService? = null,
) {
    get("/api/v1/capabilities") {
        call.respond(
            CapabilityListResponse(
                capabilities = CapabilityCatalog.snapshot(providerCatalogSource, localCodingAgentService),
            ),
        )
    }
}

private val staticProviderCatalogSource =
    object : ProviderCatalogSource {
        private val catalog = ProviderCatalog.initial()

        override suspend fun catalog(forceReload: Boolean): ProviderCatalog = catalog

        override fun invalidate() = Unit
    }

private object CapabilityCatalog {
    suspend fun snapshot(
        providerCatalogSource: ProviderCatalogSource,
        localCodingAgentService: LocalCodingAgentService?,
    ): List<CapabilityDescriptor> =
        (
            coreCapabilities() +
                llmCapabilities(providerCatalogSource) +
                mediaCapabilities() +
                voiceCapabilities() +
                localCodingAgentCapabilities(localCodingAgentService) +
                toolCapabilities() +
                channelCapabilities()
        )
            .sortedWith(compareBy(CapabilityDescriptor::category, CapabilityDescriptor::id))

    private fun localCodingAgentCapabilities(service: LocalCodingAgentService?): List<CapabilityDescriptor> =
        service?.statuses().orEmpty().map { status ->
            CapabilityDescriptor(
                id = "integration.coding-agent.${status.kind.id}",
                name = status.kind.displayName,
                category = "integration.coding-agent",
                maturity = CapabilityMaturity.BETA,
                availability =
                    when {
                        !status.available -> CapabilityAvailability.DISABLED
                        status.authentication == CapabilityAuthentication.AUTHENTICATED -> CapabilityAvailability.AVAILABLE
                        else -> CapabilityAvailability.MISSING_CONFIGURATION
                    },
                platforms = listOf("desktop-windows", "desktop-macos", "desktop-linux"),
                risk = "EXECUTE",
                limitations = status.limitations,
                runtimeVersion = status.version,
                authentication = status.authentication,
            )
        }

    private suspend fun coreCapabilities(): List<CapabilityDescriptor> =
        listOf(
            stable("core.agent", "Agent and sessions", "core", listOf("gateway", "desktop", "web", "cli")),
            stable("protocol.a2a", "A2A protocol", "protocol"),
            mcpCapability(),
            beta("protocol.acp", "ACP protocol", "protocol", emptyList()),
            beta("protocol.openai", "OpenAI-compatible API", "protocol", listOf("LLM_PROVIDER", "LLM_MODEL")),
            beta("oauth.github", "GitHub OAuth", "integration", listOf("OAUTH_GITHUB_CLIENT_ID", "OAUTH_GITHUB_CLIENT_SECRET")),
            beta("oauth.google", "Google Calendar OAuth", "integration", listOf("OAUTH_GOOGLE_CLIENT_ID", "OAUTH_GOOGLE_CLIENT_SECRET")),
            lab("plugins.jvm", "Dynamic JVM plugins", "extension", "Disabled in the public profile"),
            lab("client.android", "Android client", "client", "Not officially supported in v1.0"),
            lab("client.ios", "iOS client", "client", "Not officially supported in v1.0"),
        )

    private suspend fun mcpCapability(): CapabilityDescriptor {
        val connectedTools = ToolRegistry.listTools().count { it.name.startsWith("mcp_") }
        return CapabilityDescriptor(
            id = "protocol.mcp",
            name = "MCP client and server",
            category = "protocol",
            maturity = CapabilityMaturity.BETA,
            availability =
                if (connectedTools > 0) {
                    CapabilityAvailability.AVAILABLE
                } else {
                    CapabilityAvailability.MISSING_CONFIGURATION
                },
            requiredConfiguration = listOf("MCP_SERVERS"),
            limitations =
                listOf(
                    "Availability requires at least one connected MCP tool",
                    "Sandboxed stdio transport is unavailable until IPC protocol v2",
                ),
        )
    }

    private suspend fun llmCapabilities(source: ProviderCatalogSource): List<CapabilityDescriptor> =
        source.catalog().entries().flatMap { entry ->
            val provider = entry.provider
            val providerCapability =
                CapabilityDescriptor(
                    id = "provider.llm.${provider.id}",
                    name = provider.displayName,
                    category = "provider.llm",
                    maturity = provider.certificationStatus.toMaturity(),
                    availability = provider.availability.toCapabilityAvailability(),
                    platforms = listOf("gateway", "desktop", "web"),
                    requiredConfiguration = llmConfiguration(provider.id),
                    limitations =
                        buildList {
                            add("Provider behavior depends on the selected model and endpoint")
                            if (provider.availability == ProviderAvailability.STALE) add("Using the last valid discovered catalog")
                        },
                )
            val modelCapabilities =
                entry.models.map { model ->
                    CapabilityDescriptor(
                        id = "provider.llm.${provider.id}.model.${model.id}",
                        name = model.displayName,
                        category = "provider.llm.model",
                        maturity = model.certificationStatus.toMaturity(),
                        availability = model.availability.toCapabilityAvailability(),
                        platforms = listOf("gateway", "desktop", "web"),
                        limitations =
                            buildList {
                                if (model.lifecycle == ModelLifecycle.PREVIEW) add("Provider marks this model as preview")
                                if (model.lifecycle == ModelLifecycle.DEPRECATED) add("Provider marks this model as deprecated")
                                if (model.certificationStatus != CertificationStatus.CERTIFIED) {
                                    add("Real-provider certification is required before STABLE")
                                }
                            },
                    )
                }
            listOf(providerCapability) + modelCapabilities
        }

    private fun CertificationStatus.toMaturity(): CapabilityMaturity = if (this == CertificationStatus.CERTIFIED) CapabilityMaturity.STABLE else CapabilityMaturity.BETA

    private fun ProviderAvailability.toCapabilityAvailability(): CapabilityAvailability =
        when (this) {
            ProviderAvailability.AVAILABLE,
            ProviderAvailability.STALE,
            -> CapabilityAvailability.AVAILABLE

            ProviderAvailability.CONFIGURATION_REQUIRED -> CapabilityAvailability.MISSING_CONFIGURATION

            ProviderAvailability.UNAVAILABLE -> CapabilityAvailability.DISABLED
        }

    private fun llmConfiguration(provider: String): List<String> =
        when (provider) {
            "ollama" -> listOf("OLLAMA_URL")
            "litellm" -> listOf("LLM_BASE_URL")
            "openai" -> listOf("OPENAI_API_KEY")
            "anthropic" -> listOf("ANTHROPIC_API_KEY")
            "google" -> listOf("GOOGLE_API_KEY")
            "openrouter" -> listOf("OPENROUTER_API_KEY")
            "deepseek" -> listOf("DEEPSEEK_API_KEY")
            "nvidia" -> listOf("NVIDIA_NIM_API_KEY")
            else -> emptyList()
        }

    private fun mediaCapabilities(): List<CapabilityDescriptor> {
        val registry = ProviderRegistry(LiveProviderKeys)
        return Capability.entries.flatMap { capability ->
            registry.getAll(capability).map { provider ->
                val configured = registry.isConfigured(provider)
                CapabilityDescriptor(
                    id = "provider.media.${provider.id}",
                    name = provider.name,
                    category = "provider.media.${provider.capability.name.lowercase()}",
                    maturity = if (provider.implemented) CapabilityMaturity.BETA else CapabilityMaturity.UNAVAILABLE,
                    availability = when {
                        !provider.implemented -> CapabilityAvailability.NOT_IMPLEMENTED
                        configured -> CapabilityAvailability.AVAILABLE
                        else -> CapabilityAvailability.MISSING_CONFIGURATION
                    },
                    platforms = listOf("gateway", "desktop"),
                    requiredConfiguration = provider.credentialKeys.map(::mediaSecretEnv),
                    limitations = if (provider.implemented) {
                        listOf("Provider certification is required before STABLE")
                    } else {
                        listOf("Provider is registered but not implemented")
                    },
                )
            }
        }
    }

    private fun mediaSecretEnv(key: String): String =
        when (key) {
            "ollama_url" -> "OLLAMA_URL"
            else -> ProviderSecretRegistry.definitions.firstOrNull { it.id == key }?.envKey ?: key.uppercase()
        }

    private fun voiceCapabilities(): List<CapabilityDescriptor> =
        VoiceProviderRegistry.allProviders().map { provider ->
            val configured = ConfigProvider.get().get(provider.requiredSettingKey, "").isNotBlank()
            CapabilityDescriptor(
                id = "provider.voice.${provider.id}",
                name = provider.displayName,
                category = "provider.voice",
                maturity = if (provider.implemented) CapabilityMaturity.BETA else CapabilityMaturity.UNAVAILABLE,
                availability =
                    when {
                        !provider.implemented -> CapabilityAvailability.NOT_IMPLEMENTED
                        configured -> CapabilityAvailability.AVAILABLE
                        else -> CapabilityAvailability.MISSING_CONFIGURATION
                    },
                platforms = listOf("gateway", "web", "desktop"),
                requiredConfiguration = listOf(provider.requiredSettingKey.uppercase()),
                limitations =
                    if (provider.implemented) {
                        listOf("Real-provider certification is required before STABLE")
                    } else {
                        listOf("Provider is known but its runtime backend is not implemented")
                    },
            )
        }

    private suspend fun toolCapabilities(): List<CapabilityDescriptor> =
        ToolRegistry.listTools().map { tool ->
            val contract = ToolApprovalPolicy.contractFor(tool.name)
            val risk = contract.catalogRisk
            CapabilityDescriptor(
                id = "tool.${tool.name}",
                name = tool.name,
                category = "tool",
                maturity = CapabilityMaturity.BETA,
                availability = CapabilityAvailability.AVAILABLE,
                risk = risk.name,
                toolContract = contract.descriptor(),
                limitations = if (risk.name == "READ") {
                    listOf("Tool certification is required before STABLE")
                } else {
                    listOf("Human approval is mandatory")
                },
            )
        }

    private fun channelCapabilities(): List<CapabilityDescriptor> {
        val config = ConfigProvider.get()
        val definitions = listOf(
            ChannelCatalog("telegram", "Telegram", listOf("TELEGRAM_BOT_TOKEN"), CapabilityMaturity.BETA),
            ChannelCatalog("discord", "Discord", listOf("DISCORD_BOT_TOKEN"), CapabilityMaturity.BETA),
            ChannelCatalog("slack", "Slack", listOf("SLACK_BOT_TOKEN", "SLACK_SIGNING_SECRET"), CapabilityMaturity.BETA),
            ChannelCatalog("whatsapp", "WhatsApp Cloud", listOf("WHATSAPP_PHONE_NUMBER_ID", "WHATSAPP_ACCESS_TOKEN"), CapabilityMaturity.BETA),
            ChannelCatalog("signal", "Signal", listOf("SIGNAL_CLI_REST_URL", "SIGNAL_PHONE_NUMBER"), CapabilityMaturity.BETA),
            ChannelCatalog("matrix", "Matrix", listOf("MATRIX_HOMESERVER_URL", "MATRIX_ACCESS_TOKEN"), CapabilityMaturity.BETA),
            ChannelCatalog("email", "Email", listOf("EMAIL_API_KEY", "EMAIL_FROM"), CapabilityMaturity.LAB),
            ChannelCatalog("sms", "SMS (Twilio)", listOf("TWILIO_ACCOUNT_SID", "TWILIO_AUTH_TOKEN", "TWILIO_PHONE_NUMBER"), CapabilityMaturity.BETA),
            ChannelCatalog("teams", "Microsoft Teams", listOf("TEAMS_APP_ID", "TEAMS_APP_PASSWORD"), CapabilityMaturity.LAB),
            ChannelCatalog("mattermost", "Mattermost", listOf("MATTERMOST_URL", "MATTERMOST_TOKEN"), CapabilityMaturity.LAB),
            ChannelCatalog("dingtalk", "DingTalk", listOf("DINGTALK_CLIENT_ID", "DINGTALK_CLIENT_SECRET"), CapabilityMaturity.LAB),
            ChannelCatalog("feishu", "Feishu / Lark", listOf("FEISHU_APP_ID", "FEISHU_APP_SECRET"), CapabilityMaturity.LAB),
            ChannelCatalog("wecom", "WeCom", listOf("WECOM_CORP_ID", "WECOM_SECRET"), CapabilityMaturity.LAB),
            ChannelCatalog("line", "LINE", listOf("LINE_CHANNEL_ACCESS_TOKEN"), CapabilityMaturity.LAB),
            ChannelCatalog("qq", "QQ Bot", listOf("QQ_APP_ID", "QQ_APP_SECRET", "QQ_BOT_TOKEN"), CapabilityMaturity.LAB),
            ChannelCatalog("weixin", "WeChat Official Account", listOf("WECHAT_APP_ID", "WECHAT_APP_SECRET"), CapabilityMaturity.LAB),
            ChannelCatalog("bluebubbles", "BlueBubbles (iMessage)", listOf("BLUEBUBBLES_URL", "BLUEBUBBLES_PASSWORD"), CapabilityMaturity.LAB),
            ChannelCatalog("ntfy", "ntfy", listOf("NTFY_TOPIC"), CapabilityMaturity.LAB),
            ChannelCatalog("homeassistant", "Home Assistant", listOf("HOME_ASSISTANT_URL", "HOME_ASSISTANT_TOKEN"), CapabilityMaturity.LAB),
        )
        return definitions.map { definition ->
            val configured = definition.required.all { config.get(it, "").isNotBlank() }
            CapabilityDescriptor(
                id = "channel.${definition.id}",
                name = definition.name,
                category = "channel",
                maturity = definition.maturity,
                availability = if (definition.maturity == CapabilityMaturity.BETA && configured) {
                    CapabilityAvailability.AVAILABLE
                } else if (definition.maturity == CapabilityMaturity.BETA) {
                    CapabilityAvailability.MISSING_CONFIGURATION
                } else {
                    CapabilityAvailability.DISABLED
                },
                requiredConfiguration = definition.required,
                limitations = if (definition.maturity == CapabilityMaturity.BETA) {
                    listOf("Manual inbound and outbound certification is required before STABLE")
                } else {
                    listOf("Channel adapter exists, but gateway integration is not generally available")
                },
            )
        }
    }

    private data class ChannelCatalog(
        val id: String,
        val name: String,
        val required: List<String>,
        val maturity: CapabilityMaturity,
    )

    private fun stable(
        id: String,
        name: String,
        category: String,
        platforms: List<String> = listOf("gateway"),
    ) = CapabilityDescriptor(id, name, category, CapabilityMaturity.STABLE, CapabilityAvailability.AVAILABLE, platforms)

    private fun beta(
        id: String,
        name: String,
        category: String,
        required: List<String>,
    ): CapabilityDescriptor {
        val configured = required.all { ConfigProvider.get().get(it, "").isNotBlank() }
        return CapabilityDescriptor(
            id,
            name,
            category,
            CapabilityMaturity.BETA,
            if (configured) CapabilityAvailability.AVAILABLE else CapabilityAvailability.MISSING_CONFIGURATION,
            requiredConfiguration = required,
        )
    }

    private fun lab(
        id: String,
        name: String,
        category: String,
        limitation: String,
    ) = CapabilityDescriptor(
        id,
        name,
        category,
        CapabilityMaturity.LAB,
        CapabilityAvailability.DISABLED,
        limitations = listOf(limitation),
    )
}
