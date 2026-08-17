package dev.promethe.api

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Serialization round-trip tests for all API data classes.
 *
 * Verifies that `Json.encodeToString(instance) -> Json.decodeFromString -> instance`
 * produces identical objects. This catches:
 *  - Missing `@Serializable` annotations
 *  - Incorrect default values in constructors
 *  - Non-symmetric custom serializers
 *  - Fields that break under `encodeDefaults = true`
 */
class SerializationRoundTripTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private inline fun <reified T> assertRoundTrip(instance: T) {
        val encoded = json.encodeToString(instance)
        val decoded = json.decodeFromString<T>(encoded)
        assertEquals(instance, decoded, "Round-trip failed for ${T::class.simpleName}: $encoded")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Chat / Session Models
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun chatRequest() = assertRoundTrip(ChatRequest(message = "Bonjour", sessionId = "s-1"))

    @Test
    fun chatEvent() = assertRoundTrip(ChatEvent(type = "response", content = "Réponse", timestamp = 1718000000000))

    @Test
    fun sessionInfo() = assertRoundTrip(SessionInfo(id = "s-1", createdAt = 1718000000000, messageCount = 5, title = "Test"))

    @Test
    fun sessionListResponse() =
        assertRoundTrip(
            SessionListResponse(listOf(SessionInfo("s-1", 0, 0))),
        )

    @Test
    fun createSessionRequest() = assertRoundTrip(CreateSessionRequest(id = "s-new"))

    @Test
    fun projectModels() {
        val project =
            ProjectInfo(
                id = "project-1",
                name = "Promethe public release",
                workspacePath = "projects/project-1",
                memoryNamespace = "project:project-1",
                active = true,
                sessionCount = 3,
                memoryCount = 5,
                createdAt = 1,
                updatedAt = 2,
            )
        assertRoundTrip(project)
        assertRoundTrip(ProjectListResponse(listOf(project), project.id))
        assertRoundTrip(CreateProjectRequest("Promethe public release", instructions = "Keep security tests green"))
        assertRoundTrip(UpdateProjectRequest(description = "Public release work"))
        assertRoundTrip(AssignSessionProjectRequest(project.id))
    }

    @Test
    fun discordPolicyModels() {
        val policy =
            DiscordAccessPolicy(
                userRules =
                    listOf(
                        DiscordUserAccessRule(
                            userId = "111",
                            guildId = "222",
                            effect = DiscordUserRuleEffect.ALLOW,
                            allowedTopics = listOf("weather"),
                        ),
                    ),
                channelRules =
                    listOf(
                        DiscordChannelListenRule(
                            channelId = "333",
                            captureKnowledge = true,
                            projectId = "project-1",
                        ),
                    ),
                updatedAt = 1,
            )
        assertRoundTrip(policy)
        assertRoundTrip(UpsertDiscordUserRuleRequest(allowedTopics = listOf("weather")))
        assertRoundTrip(UpsertDiscordChannelRuleRequest(projectId = "project-1"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Export Models
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun exportMessage() = assertRoundTrip(ExportMessage(role = "user", content = "hi", timestamp = 1718000000000))

    @Test
    fun conversationExport() =
        assertRoundTrip(
            ConversationExport(
                sessionId = "s-1",
                title = "Test export",
                createdAt = 1718000000000,
                messages = listOf(ExportMessage("user", "hi", 1718000000000)),
                exportedAt = 1718000000001,
            ),
        )

    // ═══════════════════════════════════════════════════════════════════════════
    // Auth Models
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun loginRequest() = assertRoundTrip(LoginRequest(user = "admin", password = "s3cret"))

    @Test
    fun loginResponse() = assertRoundTrip(LoginResponse(accessToken = "pss_opaque", expiresAt = 1_700_000_000_000))

    @Test
    fun setupRemoteOwnerRequest() = assertRoundTrip(SetupRemoteOwnerRequest(user = "admin", password = "a-very-long-password"))

    // ═══════════════════════════════════════════════════════════════════════════
    // Feedback Models
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun feedbackRequest() = assertRoundTrip(FeedbackRequest(sessionId = "s-1", score = 4.0, comment = "Nice"))

    @Test
    fun feedbackStats() = assertRoundTrip(FeedbackStats(averageScore = 4.5, totalCount = 10))

    // ═══════════════════════════════════════════════════════════════════════════
    // Agent Models
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun agentProfile() =
        assertRoundTrip(
            AgentProfile(id = "p-1", name = "Expert", systemPrompt = "You are an expert.", model = "gpt-4o"),
        )

    @Test
    fun agentProfileRequest() =
        assertRoundTrip(
            AgentProfileRequest(
                name = "Expert",
                systemPrompt = "You are an expert.",
                model = "gpt-4o",
                reasoningEffort = ReasoningEffort.HIGH,
            ),
        )

    @Test
    fun agentStatusDto() =
        assertRoundTrip(
            AgentStatusDto(id = "a-1", name = "Main", status = "active", currentStep = "Thinking"),
        )

    @Test
    fun agentExecutionEvent() =
        assertRoundTrip(
            AgentExecutionEvent(agentId = "a-1", type = "step", content = "Processing", timestamp = 1718000000000),
        )

    @Test
    fun gepaOptimizeRequest() = assertRoundTrip(GepaOptimizeRequest(maxGenerations = 5, populationSize = 8))

    @Test
    fun gepaResultDto() =
        assertRoundTrip(
            GepaResultDto(
                bestPromptPreview = "You are...",
                accuracy = 0.95,
                improvement = 0.12,
                generations = 5,
                totalCandidatesEvaluated = 40,
            ),
        )

    // ═══════════════════════════════════════════════════════════════════════════
    // Gateway Models
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun mcpToolInfo() = assertRoundTrip(McpToolInfo(name = "web_search", description = "Search the web"))

    @Test
    fun mcpConnectResponse() =
        assertRoundTrip(
            McpConnectResponse(tools = listOf(McpToolInfo("tool1", "desc"))),
        )

    @Test
    fun webhookReply() = assertRoundTrip(WebhookReply(reply = "OK"))

    @Test
    fun executeResponse() = assertRoundTrip(ExecuteResponse(result = "done"))

    @Test
    fun jvmMemoryInfo() = assertRoundTrip(JvmMemoryInfo(used = "256MB", max = "2048MB"))

    @Test
    fun enhancedHealthResponse() =
        assertRoundTrip(
            EnhancedHealthResponse(uptime = "3600", memory = JvmMemoryInfo("256MB", "2048MB")),
        )

    @Test
    fun setupRemoteResponse() = assertRoundTrip(SetupRemoteResponse(user = "admin"))

    // ═══════════════════════════════════════════════════════════════════════════
    // Stats & Status Models
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun statsResponse() =
        assertRoundTrip(
            StatsResponse(totalTokens = 50000, totalRequests = 100, estimatedCost = 1.25, avgFeedback = 4.2, feedbackCount = 15),
        )

    @Test
    fun healthResponse() = assertRoundTrip(HealthResponse(status = "ok", timestamp = 1718000000000))

    @Test
    fun errorResponse() = assertRoundTrip(ErrorResponse(error = "Not found"))

    @Test
    fun providerHealth() =
        assertRoundTrip(
            ProviderHealth(name = "openai", keyPoolSize = 3, hasExecutor = true, status = "healthy"),
        )

    @Test
    fun providersResponse() =
        assertRoundTrip(
            ProvidersResponse(providers = listOf(ProviderHealth("openai", 3, true, "healthy"))),
        )

    // ═══════════════════════════════════════════════════════════════════════════
    // Scheduler Models
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun scheduledTaskRequest() =
        assertRoundTrip(
            ScheduledTaskRequest(name = "daily-sync", cronExpression = "0 9 * * *", prompt = "Sync data"),
        )

    @Test
    fun scheduledTaskResponse() =
        assertRoundTrip(
            ScheduledTaskResponse(id = "t-1", name = "daily-sync", cronExpression = "0 9 * * *", prompt = "Sync data", enabled = true),
        )

    // ═══════════════════════════════════════════════════════════════════════════
    // RAG Models
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun ragSearchRequest() = assertRoundTrip(RagSearchRequest(query = "What is AI?", topK = 5))

    @Test
    fun ragSearchResult() =
        assertRoundTrip(
            RagSearchResult(content = "AI is...", score = 0.95, source = "doc-1"),
        )

    @Test
    fun ragSearchResponse() =
        assertRoundTrip(
            RagSearchResponse(results = listOf(RagSearchResult("content", 0.9, "src")), queryTimeMs = 42),
        )

    @Test
    fun ragDocumentInfo() =
        assertRoundTrip(
            RagDocumentInfo(id = "d-1", filename = "doc.pdf", chunkCount = 12, totalTokens = 2048, ingestedAt = 1718000000000),
        )

    // ═══════════════════════════════════════════════════════════════════════════
    // Plugin Models
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun pluginResponse() =
        assertRoundTrip(
            PluginResponse(
                name = "WebSearch",
                version = "1.0",
                description = "Search plugin",
                author = "Promethe",
                enabled = true,
                toolCount = 2,
                hookCount = 1,
                promptCount = 0,
            ),
        )

    @Test
    fun pluginListResponse() =
        assertRoundTrip(
            PluginListResponse(
                plugins = listOf(
                    PluginResponse("WebSearch", "1.0", "Search", "Promethe", true, 2, 1, 0),
                ),
                totalPlugins = 1,
                enabledPlugins = 1,
                totalTools = 2,
            ),
        )

    @Test
    fun pluginToggleRequest() = assertRoundTrip(PluginToggleRequest(enabled = true))

    // ═══════════════════════════════════════════════════════════════════════════
    // Orchestrator Models
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun delegationRequest() =
        assertRoundTrip(
            DelegationRequest(task = "research topic", profileId = "p-1", parentSessionId = "s-0"),
        )

    @Test
    fun delegationResponse() = assertRoundTrip(DelegationResponse(childSessionId = "s-new", status = "delegated"))

    @Test
    fun subAgentStatusResponse() =
        assertRoundTrip(
            SubAgentStatusResponse(
                sessionId = "s-1",
                profileId = "p-1",
                task = "research",
                status = "running",
                response = null,
                durationMs = null,
            ),
        )

    // ═══════════════════════════════════════════════════════════════════════════
    // Webhook / Channel Models
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun webhookChannelConfig() =
        assertRoundTrip(
            WebhookChannelConfig(name = "telegram", enabled = true),
        )

    @Test
    fun webhookEvent() =
        assertRoundTrip(
            WebhookEvent(channel = "telegram", content = "Hello", timestamp = 1718000000000),
        )

    @Test
    fun discordInteractionResponse() =
        assertRoundTrip(
            DiscordInteractionResponse(type = 4, data = DiscordResponseData(content = "Response", flags = 64)),
        )

    @Test
    fun discordInteractionUser() =
        assertRoundTrip(
            DiscordInteraction(
                id = "interaction-1",
                type = 2,
                channel_id = "channel-1",
                guild_id = "guild-1",
                member =
                    DiscordInteractionMember(
                        user = DiscordInteractionUser(id = "user-1", username = "alice", global_name = "Alice"),
                        nick = "Ali",
                    ),
            ),
        )

    @Test
    fun slackChallengeResponse() = assertRoundTrip(SlackChallengeResponse(challenge = "abc123"))

    // ═══════════════════════════════════════════════════════════════════════════
    // A2A Protocol Models
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun a2aAgentCard() =
        assertRoundTrip(
            A2AAgentCard(
                name = "Promethe",
                description = "AI Agent",
                url = "http://localhost:8080",
                version = "1.0.0",
                skills = listOf(A2ASkill(id = "s-1", name = "chat", description = "Chat")),
            ),
        )

    @Test
    fun a2aMessage() =
        assertRoundTrip(
            A2AMessage(
                messageId = "msg-1",
                role = "user",
                parts = listOf(A2APart.Text(text = "Hello")),
            ),
        )

    // ═══════════════════════════════════════════════════════════════════════════
    // OpenAI-Compatible Models
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun openAiChatMessage() = assertRoundTrip(OpenAIChatMessage(role = "user", content = "Hello"))

    @Test
    fun openAiChatRequest() =
        assertRoundTrip(
            OpenAIChatRequest(
                model = "gpt-4o",
                messages = listOf(OpenAIChatMessage("user", "Hello")),
                stream = false,
            ),
        )

    @Test
    fun openAiChatChoice() =
        assertRoundTrip(
            OpenAIChatChoice(index = 0, message = OpenAIChatMessage("assistant", "Hi!"), finishReason = "stop"),
        )

    @Test
    fun openAiUsage() = assertRoundTrip(OpenAIUsage(promptTokens = 10, completionTokens = 20, totalTokens = 30))

    @Test
    fun openAiChatResponse() =
        assertRoundTrip(
            OpenAIChatResponse(
                id = "chatcmpl-1",
                created = 1718000000,
                model = "gpt-4o",
                choices = listOf(OpenAIChatChoice(0, OpenAIChatMessage("assistant", "Hi!"), "stop")),
                usage = OpenAIUsage(10, 20, 30),
            ),
        )

    @Test
    fun openAiError() = assertRoundTrip(OpenAIError(message = "Invalid API key", type = "auth_error"))

    @Test
    fun openAiErrorResponse() =
        assertRoundTrip(
            OpenAIErrorResponse(error = OpenAIError("Invalid API key", "auth_error")),
        )
}
