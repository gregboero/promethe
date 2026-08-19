package dev.promethe.db

import org.jetbrains.exposed.v1.core.Table

/** agent_runs — durable lifecycle state for each entry into the agent loop. */
object AgentRuns : Table("agent_runs") {
    val runId = varchar("run_id", 160)
    val parentRunId = varchar("parent_run_id", 160).nullable()
    val sessionId = varchar("session_id", 255)
    val origin = varchar("origin", 64)
    val projectId = varchar("project_id", 64).nullable()
    val requestFingerprint = varchar("request_fingerprint", 64).nullable()
    val status = varchar("status", 32)
    val stepCount = integer("step_count").default(0)
    val lastStepId = varchar("last_step_id", 200).nullable()
    val errorCode = varchar("error_code", 128).nullable()
    val createdAt = long("created_at")
    val startedAt = long("started_at").nullable()
    val finishedAt = long("finished_at").nullable()
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(runId)

    init {
        index(isUnique = false, sessionId)
        index(isUnique = false, status)
        index(isUnique = false, parentRunId)
    }
}

/** tool_intents — durable idempotency boundary around tool side effects. */
object ToolIntents : Table("tool_intents") {
    val intentId = varchar("intent_id", 160)
    val idempotencyKeyHash = varchar("idempotency_key_hash", 64)
    val invocationHash = varchar("invocation_hash", 64)
    val runId = varchar("run_id", 160).nullable()
    val stepId = varchar("step_id", 200).nullable()
    val sessionId = varchar("session_id", 255)
    val toolName = varchar("tool_name", 255)
    val risk = varchar("risk", 32)
    val status = varchar("status", 32)
    val resultHash = varchar("result_hash", 64).nullable()
    val artifactHash = varchar("artifact_hash", 64).nullable()
    val errorCode = varchar("error_code", 128).nullable()
    val createdAt = long("created_at")
    val startedAt = long("started_at").nullable()
    val finishedAt = long("finished_at").nullable()
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(intentId)

    init {
        uniqueIndex(idempotencyKeyHash)
        index(isUnique = false, runId)
        index(isUnique = false, status)
        index(isUnique = false, artifactHash)
    }
}

/** agent_run_events - append-only execution history without prompts, arguments, or raw results. */
object AgentRunEvents : Table("agent_run_events") {
    val eventId = varchar("event_id", 200)
    val runId = varchar("run_id", 160)
    val sequence = long("sequence")
    val eventType = varchar("event_type", 40)
    val parentRunId = varchar("parent_run_id", 160).nullable()
    val sessionId = varchar("session_id", 255).nullable()
    val origin = varchar("origin", 64).nullable()
    val projectId = varchar("project_id", 64).nullable()
    val requestFingerprint = varchar("request_fingerprint", 64).nullable()
    val stepId = varchar("step_id", 200).nullable()
    val stepCount = integer("step_count").nullable()
    val intentId = varchar("intent_id", 160).nullable()
    val idempotencyKeyHash = varchar("idempotency_key_hash", 64).nullable()
    val invocationHash = varchar("invocation_hash", 64).nullable()
    val toolName = varchar("tool_name", 255).nullable()
    val risk = varchar("risk", 32).nullable()
    val runStatus = varchar("run_status", 32).nullable()
    val intentStatus = varchar("intent_status", 32).nullable()
    val resultHash = varchar("result_hash", 64).nullable()
    val artifactHash = varchar("artifact_hash", 64).nullable()
    val errorCode = varchar("error_code", 128).nullable()
    val approvalId = varchar("approval_id", 200).nullable()
    val approvalAllowed = bool("approval_allowed").nullable()
    val approvalScope = varchar("approval_scope", 32).nullable()
    val createdAt = long("created_at")
    val eventVersion = integer("event_version").default(1)

    override val primaryKey = PrimaryKey(eventId)

    init {
        uniqueIndex(runId, sequence)
        index(isUnique = false, intentId)
        index(isUnique = false, eventType)
        index(isUnique = false, artifactHash)
    }
}

/** mcp_tasks - durable state for the MCP Tasks extension. */
object McpTasks : Table("mcp_tasks") {
    val taskId = varchar("task_id", 160)
    val ownerSessionId = varchar("owner_session_id", 255)
    val method = varchar("method", 80)
    val resourceName = varchar("resource_name", 255)
    val runId = varchar("run_id", 160).nullable()
    val status = varchar("status", 32)
    val statusMessage = text("status_message").nullable()
    val resultJson = text("result_json").nullable()
    val errorJson = text("error_json").nullable()
    val inputRequestsJson = text("input_requests_json").nullable()
    val createdAt = long("created_at")
    val lastUpdatedAt = long("last_updated_at")
    val ttlMs = long("ttl_ms").nullable()
    val pollIntervalMs = long("poll_interval_ms").nullable()

    override val primaryKey = PrimaryKey(taskId)

    init {
        index(isUnique = false, ownerSessionId)
        index(isUnique = false, status)
        index(isUnique = false, lastUpdatedAt)
        index(isUnique = false, runId)
    }
}

/** projects — durable work contexts grouping sessions, memory and a workspace. */
object Projects : Table("projects") {
    val id = varchar("id", 64)
    val name = varchar("name", 120)
    val description = text("description").default("")
    val instructions = text("instructions").default("")
    val workspacePath = varchar("workspace_path", 500)
    val memoryNamespace = varchar("memory_namespace", 255)
    val archived = bool("archived").default(false)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(workspacePath)
        uniqueIndex(memoryNamespace)
    }
}

/** sessions — agent conversation sessions */
object Sessions : Table("sessions") {
    val id = varchar("id", 255)
    val createdAt = long("created_at")
    val metadata = text("metadata").nullable()
    val title = varchar("title", 500).nullable()
    val projectId = varchar("project_id", 64).references(Projects.id).nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index(isUnique = false, projectId)
    }
}

/** messages — individual messages within a session */
object Messages : Table("messages") {
    val id = integer("id").autoIncrement()
    val sessionId = varchar("session_id", 255).references(Sessions.id)
    val role = varchar("role", 50) // "user", "assistant", "system"
    val content = text("content")
    val timestamp = long("timestamp")
    val dataTrust = varchar("data_trust", 32).default("TRUSTED")
    val sourceRunId = varchar("source_run_id", 160).nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index(isUnique = false, sessionId)
        index(isUnique = false, sessionId, timestamp)
        index(isUnique = false, sourceRunId)
    }
}

/** feedbacks — user feedback per session */
object Feedbacks : Table("feedbacks") {
    val id = integer("id").autoIncrement()
    val sessionId = varchar("session_id", 255).references(Sessions.id)
    val score = double("score") // 0.0 – 1.0
    val comment = text("comment").nullable()
    val timestamp = long("timestamp")

    override val primaryKey = PrimaryKey(id)

    init {
        index(isUnique = false, sessionId)
    }
}

/** agent_profiles — reusable agent personas (provider, model, tools, prompt) */
object AgentProfiles : Table("agent_profiles") {
    val id = varchar("id", 255)
    val name = varchar("name", 500)
    val provider = varchar("provider", 100).default("openai")
    val model = varchar("model", 500).default("gpt-4o-mini")
    val systemPrompt = text("system_prompt").default("")
    val tools = text("tools").default("[]") // JSON array of tool names
    val skills = text("skills").default("[]") // JSON array of skill names
    val maxIterations = integer("max_iterations").default(10)
    val temperature = double("temperature").default(0.2)
    val reasoningEffort = varchar("reasoning_effort", 50).default("AUTO")
    val isSystem = bool("is_system").default(false)
    val ephemeral = bool("ephemeral").default(false)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/** checkpoints — mid-loop state persistence for crash recovery */
object Checkpoints : Table("checkpoints") {
    val id = integer("id").autoIncrement()
    val sessionId = varchar("session_id", 255).references(Sessions.id)
    val stepIndex = integer("step_index")
    val stateJson = text("state_json") // serialized agent state
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index(isUnique = false, sessionId)
    }
}

/** user_facts — long-term memory (preferences, project context, etc.) */
object UserFacts : Table("user_facts") {
    val id = integer("id").autoIncrement()
    val userId = varchar("user_id", 255).default("default")
    val category = varchar("category", 100) // "preference", "project", "personal", "technical"
    val fact = text("fact")
    val confidence = double("confidence").default(1.0)
    val sourceSession = varchar("source_session", 255).default("")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index(isUnique = false, userId)
    }
}

/** scheduled_tasks — cron-like scheduled agent tasks */
object ScheduledTasks : Table("scheduled_tasks") {
    val id = varchar("id", 255)
    val name = varchar("name", 500)
    val cronExpression = varchar("cron_expression", 100) // "*/5 * * * *"
    val prompt = text("prompt") // message to send to agent
    val profileId = varchar("profile_id", 255).nullable() // optional agent profile
    val enabled = bool("enabled").default(true)
    val lastRunAt = long("last_run_at").nullable()
    val nextRunAt = long("next_run_at").nullable()
    val lastRunStatus = varchar("last_run_status", 50).nullable() // "success", "error"
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)
}

/** webhook_channels — inbound/outbound webhook channel configurations */
object WebhookChannels : Table("webhook_channels") {
    val id = varchar("id", 255)
    val name = varchar("name", 255) // "telegram", "discord", "slack", "custom"
    val type = varchar("type", 50).default("inbound") // "inbound", "outbound", "bidirectional"
    val secret = text("secret").default("") // shared secret for signature verification
    val outboundUrl = text("outbound_url").default("") // URL to POST outbound events to
    val enabled = bool("enabled").default(true)
    val headerName = varchar("header_name", 255).default("") // custom auth header name
    val payloadTemplate = text("payload_template").default("") // template for outbound formatting
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(name)
    }
}

/** settings — key-value config persistence (rag_config, etc.) */
object Settings : Table("settings") {
    val key = varchar("key", 255)
    val value = text("value") // JSON or plain text
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(key)
}

/** llm_usage_logs — per-request LLM usage for persistent stats */
object LlmUsageLogs : Table("llm_usage_logs") {
    val id = integer("id").autoIncrement()
    val provider = varchar("provider", 50)
    val model = varchar("model", 100)
    val promptTokens = integer("prompt_tokens")
    val completionTokens = integer("completion_tokens")
    val cost = double("cost")
    val timestamp = long("timestamp")

    override val primaryKey = PrimaryKey(id)

    init {
        index(isUnique = false, timestamp)
        index(isUnique = false, provider)
    }
}

/** remote_owner — the one account allowed to administer an opted-in remote gateway. */
object RemoteOwners : Table("remote_owners") {
    val id = varchar("id", 64)
    val username = varchar("username", 255)
    val passwordHash = text("password_hash")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(username)
    }
}

/** auth_sessions — only hashes of random bearer tokens are persisted. */
object AuthSessions : Table("auth_sessions") {
    val id = varchar("id", 128)
    val ownerId = varchar("owner_id", 64).references(RemoteOwners.id)
    val tokenHash = varchar("token_hash", 128)
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    val lastSeenAt = long("last_seen_at")
    val revokedAt = long("revoked_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(tokenHash)
        index(isUnique = false, ownerId)
        index(isUnique = false, expiresAt)
    }
}

/** oauth_authorizations — one-time state records for OAuth authorization-code flows. */
object OAuthAuthorizations : Table("oauth_authorizations") {
    val stateHash = varchar("state_hash", 128)
    val ownerId = varchar("owner_id", 64).references(RemoteOwners.id)
    val provider = varchar("provider", 64)
    val redirectUri = text("redirect_uri")
    val encryptedVerifier = text("encrypted_verifier")
    val expiresAt = long("expires_at")
    val consumedAt = long("consumed_at").nullable()

    override val primaryKey = PrimaryKey(stateHash)
}

/** oauth_connections — encrypted provider tokens scoped to the gateway owner. */
object OAuthConnections : Table("oauth_connections") {
    val ownerId = varchar("owner_id", 64).references(RemoteOwners.id)
    val provider = varchar("provider", 64)
    val encryptedTokens = text("encrypted_tokens")
    val expiresAt = long("expires_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(ownerId, provider)
}

/** mcp_server_configs — public config plus an encrypted headers/environment payload. */
object McpServerConfigs : Table("mcp_server_configs") {
    val id = varchar("id", 128)
    val configJson = text("config_json")
    val encryptedSecrets = text("encrypted_secrets")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/** security_audit_logs — intentionally excludes passwords, tokens and command payloads. */
object SecurityAuditLogs : Table("security_audit_logs") {
    val id = integer("id").autoIncrement()
    val eventType = varchar("event_type", 128)
    val actor = varchar("actor", 255).default("")
    val remoteAddress = varchar("remote_address", 255).default("")
    val detail = text("detail").default("")
    val createdAt = long("created_at")
    val previousHash = varchar("previous_hash", 64).default("")
    val entryHash = varchar("entry_hash", 64).default("")

    override val primaryKey = PrimaryKey(id)

    init {
        index(isUnique = false, createdAt)
        index(isUnique = false, eventType)
    }
}
