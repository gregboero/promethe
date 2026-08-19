# API Reference

> Complete reference for the Prométhé gateway REST and WebSocket API.

**Base URL**: `http://localhost:8080`

## Authentication

`AuthMiddleware` accepts two Bearer credential formats:

```
Authorization: Bearer pk-prom-xxx   # local administrative key, loopback only
Authorization: Bearer pss_...       # opaque, expiring owner session
```

The generated `pk-prom-` key is only accepted from a loopback peer. It bootstraps a local installation
and remains useful to the embedded Desktop gateway. It is not a remote API credential.

Remote access is disabled by default. Once explicitly enabled, an owner signs in with
`POST /auth/login`. Native clients retain the returned `pss_` session token in memory; browser clients
receive an `HttpOnly`, `Secure`, `SameSite=Strict` cookie and never receive the token in JSON. Credentials
in URL query parameters are rejected.

Public routes are deliberately narrow: `/health`, `POST /auth/login`, the exact OAuth callback routes,
static browser assets, and `/.well-known/` discovery. Webhook routes (`/webhook/*`) use per-platform
signature verification instead of Bearer auth. All other routes require one of the credentials above.

When `CORS_ALLOWED_ORIGINS` is empty, browser cross-origin access is denied. When configured, it must be
an explicit comma-separated list of HTTP(S) origins; wildcard origins are not supported. The same allow-list
is applied to the MCP HTTP endpoint.

The gateway also applies a bounded in-memory rate limiter: **60 requests/minute per IP** by default,
with a separate **120 requests/minute** bucket for signed webhooks (`RateLimiter.kt`; exempt: `/health`
and `/.well-known/*`). Responses include
`X-RateLimit-Limit` / `X-RateLimit-Remaining` / `X-RateLimit-Reset` headers; exceeding the limit returns
`429 Too Many Requests`.

## Error format

```json
{
  "error": "Error description",
  "status": 400
}
```

---

## Public contracts

### GET /api/v1/capabilities

Returns the runtime capability registry used by clients and release certification. Each descriptor carries
its `STABLE`, `BETA`, `LAB`, or `UNAVAILABLE` maturity, current availability, risk class, platforms,
required configuration names, and limitations. Secret values are never returned.

Tool descriptors also include a typed `toolContract`: registration source, catalog and fallback risks,
approval mode, idempotency, egress class, owner-only restriction, operation keys and operation-specific
risks. `explicit=false` identifies the fail-closed fallback used for an uncontracted tool; such a tool is
always classified `EXTERNAL_EFFECT` and requires approval.

### GET /api/v1/providers

Returns the gateway-owned, secret-free provider catalog with live availability and certification state.
Discovery is cached for six hours and retains the last valid catalog on transient failures.

### GET /api/v1/providers/{id}/models

Returns the account-visible models for one provider. Descriptors include lifecycle, certification,
capabilities, supported parameters, modalities and provider expiration metadata when available. Unknown
discovered models remain `BETA`/`UNVERIFIED`; NIM, LiteLLM and OpenRouter have no invented global default.
`POST /api/v1/settings/reload` invalidates and refreshes this catalog after configuration changes.

### GET /api/v1/openapi.json

Returns OpenAPI 3.1 generated from the Ktor route tree mounted by the running gateway. Path parameters,
HTTP methods, Bearer authentication, and owner-session cookie authentication are derived at runtime.

---

## Chat (via A2A Protocol)

> ⚠️ **`POST /api/chat` and `WS /ws/chat` do not exist.**
> Chat goes through the A2A (Agent-to-Agent) protocol via JSON-RPC.

Prométhé mounts the A2A JSON-RPC transport under the `agents` route with sub-path `a2a`
(`PrometheA2A.installRoutes` → Koog SDK's `HttpJSONRPCServerTransport.transportRoutes(route, "/a2a")`),
so the real endpoint is **`POST /agents/a2a`** — this is also what the Compose UI client
(`A2AChatClient.kt`, `PrometheClient.kt`) calls. There is no handler at bare `POST /agents/`.

### POST /agents/a2a (JSON-RPC `message/send`)

Send a message to the agent. The body is a standard JSON-RPC call.

```bash
curl -X POST http://localhost:8080/agents/a2a \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "message/send",
    "id": "1",
    "params": {
      "message": {
        "role": "user",
        "parts": [{"type": "text", "text": "Explique-moi comment fonctionne Git"}],
        "contextId": "sess-001",
        "metadata": {
          "provider": "openrouter",
          "model": "google/gemini-3.5-flash",
          "profileId": "default"
        }
      }
    }
  }'
```

### POST /agents/a2a (JSON-RPC `message/stream`)

Streaming version (SSE). Same format, but events arrive as Server-Sent Events:
- `thought` — Agent's internal reasoning
- `action` — Tool call (name + arguments)
- `observation` — Tool result
- `response` — Final response
- `done` — End of stream

### WS /ws/agents

WebSocket for real-time **Monitor Screen** events (not chat).

```javascript
const ws = new WebSocket("ws://localhost:8080/ws/agents");
ws.onmessage = (event) => {
  const agentEvent = JSON.parse(event.data);
  // { type: "TOOL_CALL", tool: "web_search", sessionId: "sess-001", ... }
};
```

---

## Sessions

### GET /api/v1/sessions

Lists sessions, excluding internal sub-agent/profile/A2A/context sessions (ids prefixed `sub-`, `profile-`, `a2a-`, `ctx-`, or with a `"parent"` metadata key).

```bash
curl http://localhost:8080/api/v1/sessions
```

**Response**:
```json
[
  {
    "id": "sess-001",
    "createdAt": 1718000000000,
    "metadata": "{}",
    "messageCount": 12,
    "projectId": "project-2c2b62ba"
  }
]
```

### POST /api/v1/sessions

Create a new session. `projectId` is optional; when omitted, a new session inherits the server's active project.

```bash
curl -X POST http://localhost:8080/api/v1/sessions \
  -H "Content-Type: application/json" \
  -d '{"id":"sess-001","projectId":"project-2c2b62ba"}'
```

### DELETE /api/v1/sessions/{id}

Delete a session.

```bash
curl -X DELETE http://localhost:8080/api/v1/sessions/sess-001
```

### GET /api/v1/sessions/{id}/messages

Fetch the message history of a session as chat events.

```bash
curl http://localhost:8080/api/v1/sessions/sess-001/messages
```

### PATCH /api/v1/sessions/{id}/metadata

Overwrite a session's metadata blob (raw JSON body, stored as-is).

```bash
curl -X PATCH http://localhost:8080/api/v1/sessions/sess-001/metadata \
  -H "Content-Type: application/json" \
  -d '{"name": "Projet X"}'
```

### PATCH /api/v1/sessions/{id}/project

Assign an existing conversation to a project, or send `null` to detach it.

```bash
curl -X PATCH http://localhost:8080/api/v1/sessions/sess-001/project \
  -H "Content-Type: application/json" \
  -d '{"projectId":"project-2c2b62ba"}'
```

### GET /api/v1/sessions/{id}/export/json

Export a complete session as JSON (sets `Content-Disposition: attachment`).

```bash
curl http://localhost:8080/api/v1/sessions/sess-001/export/json
```

### GET /api/v1/sessions/{id}/export/markdown

Export a complete session as Markdown (sets `Content-Disposition: attachment`).

```bash
curl http://localhost:8080/api/v1/sessions/sess-001/export/markdown
```

> Note: there is no combined `GET /api/v1/sessions/{id}/export` — export is split into the two typed routes above. See also [Session Checkpoints](#session-checkpoints) for `GET/POST/DELETE /api/v1/sessions/{id}/checkpoint(s)`.

---

## Projects

Projects group conversations, trusted instructions, a workspace and isolated long-term memory. All routes
require owner authentication. Archiving is non-destructive and never deletes project files or memories.

| Method | Route | Purpose |
|---|---|---|
| `GET` | `/api/v1/projects` | List projects and the active project id |
| `POST` | `/api/v1/projects` | Create a project and its workspace |
| `GET` | `/api/v1/projects/{id}` | Read one project |
| `PUT` | `/api/v1/projects/{id}` | Update or restore a project |
| `DELETE` | `/api/v1/projects/{id}` | Archive a project |
| `PUT` | `/api/v1/projects/{id}/active` | Make a project active |
| `DELETE` | `/api/v1/projects/active` | Clear the active project |

```bash
curl -X POST http://localhost:8080/api/v1/projects \
  -H "Content-Type: application/json" \
  -d '{
    "name":"Promethe public release",
    "description":"Release preparation",
    "instructions":"Prioritize security regressions and keep the release checklist current."
  }'
```

See [PROJECTS.md](PROJECTS.md) for workspace and memory semantics.

---

## Agents

### GET /api/v1/agents

```bash
curl http://localhost:8080/api/v1/agents
```

### POST /api/v1/agents

```bash
curl -X POST http://localhost:8080/api/v1/agents \
  -H "Content-Type: application/json" \
  -d '{
    "name": "researcher",
    "systemPrompt": "Tu es un agent de recherche...",
    "tools": ["web_search", "http_fetch"]
  }'
```

### PUT /api/v1/agents/{id}

Update an existing agent.

### DELETE /api/v1/agents/{id}

Delete an agent.

---

## Memory

### GET /api/v1/memory/facts

Optional query params: `q` (search text — falls back to listing all facts when omitted) and `limit` (default 50).

```bash
curl http://localhost:8080/api/v1/memory/facts
curl "http://localhost:8080/api/v1/memory/facts?q=python&limit=10"
```

**Response**:
```json
{
  "facts": [
    {
      "id": "fact-001",
      "userId": "default",
      "category": "preference",
      "tier": "ATOMIC",
      "content": "L'utilisateur préfère Python",
      "confidence": 0.85,
      "createdAt": 1718000000000
    }
  ],
  "total": 1,
  "query": null
}
```

### POST /api/v1/memory/facts

Manually create a memory fact. `tier` is one of the `MemoryTier` values (default `ATOMIC`).

```bash
curl -X POST http://localhost:8080/api/v1/memory/facts \
  -H "Content-Type: application/json" \
  -d '{"category": "preference", "content": "Prefers dark mode", "confidence": 1.0, "tier": "ATOMIC"}'
```

### PUT /api/v1/memory/facts/{id}

Update an existing fact's category/content/confidence.

```bash
curl -X PUT http://localhost:8080/api/v1/memory/facts/fact-001 \
  -H "Content-Type: application/json" \
  -d '{"content": "Updated fact text"}'
```

### DELETE /api/v1/memory/facts/{id}

```bash
curl -X DELETE http://localhost:8080/api/v1/memory/facts/fact-001
```

### GET /api/v1/memory/status

```bash
curl http://localhost:8080/api/v1/memory/status
# → {"provider": "embedded", "factCount": 42, "tierCounts": {...}}
```

---

## GEPA

GEPA optimization runs as a background job (`GepaJobManager`) — `POST /api/v1/gepa/optimize` returns immediately with a `jobId`; poll `GET /api/v1/gepa/jobs/{id}` for progress.

### POST /api/v1/gepa/optimize

Start a new GEPA optimization job. Returns `409 Conflict` if a job is already running.

```bash
curl -X POST http://localhost:8080/api/v1/gepa/optimize
```

**Response** (`202 Accepted`):
```json
{
  "jobId": "gepa-job-abc123"
}
```

### GET /api/v1/gepa/jobs/{id}

Get the status of a specific GEPA job.

```bash
curl http://localhost:8080/api/v1/gepa/jobs/gepa-job-abc123
```

### GET /api/v1/gepa/jobs

List all GEPA jobs, most recent first.

```bash
curl http://localhost:8080/api/v1/gepa/jobs
```

### GET /api/v1/gepa/current

Convenience endpoint for the currently running job, if any. Returns `204 No Content` when idle.

```bash
curl http://localhost:8080/api/v1/gepa/current
```

---

## Sandbox and file permissions

### GET /api/v1/security/sandbox/status

Returns the native process-sandbox state plus `workspaceRoot` and
`localConfigurationAllowed`. The latter is true only for a loopback request
authenticated with the local API key.

### GET /api/v1/security/permission-profile

Returns the active file/process permission profile, including canonical
readable and writable roots.

### PUT /api/v1/security/permission-profile

Updates and persists the profile. This route requires loopback authentication
with the local API key. `FULL_ACCESS` applies only to approved file tools;
process tools remain confined to the main workspace. It is rejected whenever
remote access is enabled.

### POST /api/v1/security/sandbox/self-test

Runs the native backend self-test.

### POST /api/v1/security/sandbox/setup

Runs the one-time native setup where supported. This route also requires the
local loopback API key.

---

## MCP Servers (client — connect Prométhé to external MCP servers)

Manage Prométhé's connections to *external* MCP servers (e.g. filesystem, GitHub MCP servers). This is the client side; see [MCP Protocol Server](#mcp-protocol-server) below for Prométhé acting as an MCP *server*.

UI-managed configurations are stored in SQLite. Their `env` and `headers` values are encrypted with
`PROMETHE_MASTER_KEY` and are always returned as `***`. A server defined through `MCP_SERVERS` is process-owned:
it takes priority and cannot be changed or deleted through this API.

### GET /api/v1/mcp/servers

```bash
curl http://localhost:8080/api/v1/mcp/servers
```

### POST /api/v1/mcp/servers

Persist and register a new MCP server config (does not auto-connect). The request may contain secret
headers or environment variables; they are encrypted before persistence.

```bash
curl -X POST http://localhost:8080/api/v1/mcp/servers \
  -H "Content-Type: application/json" \
  -d '{
    "id": "filesystem",
    "name": "filesystem",
    "transport": "streamable-http",
    "url": "https://mcp.example.test",
    "headers": {"Authorization": "Bearer secret"}
  }'
```

### PUT /api/v1/mcp/servers/{id}

Replace a persisted MCP server configuration. The route id and body `id` must match. Updating a server
disconnects its old transport and unregisters its old tools before the new configuration is registered.

### DELETE /api/v1/mcp/servers/{id}

Delete a persisted MCP server configuration. Its transport is closed and every tool previously registered
for that server is removed from `ToolRegistry`.

### POST /api/v1/mcp/servers/{id}/connect

Connect a registered MCP server and discover its tools.

```bash
curl -X POST http://localhost:8080/api/v1/mcp/servers/filesystem/connect
```

### POST /api/v1/mcp/servers/{id}/disconnect

Disconnect an MCP server.

```bash
curl -X POST http://localhost:8080/api/v1/mcp/servers/filesystem/disconnect
```

### GET /api/v1/mcp/tools

Lists all tools exposed by connected MCP servers, plus all built-in tools (merged, with a `source` field of `"MCP"` or `"Built-in"`).

```bash
curl http://localhost:8080/api/v1/mcp/tools
```

---

## MCP Protocol Server

Prométhé also exposes its own built-in tools **as an MCP server** (2025-11-05 Streamable HTTP transport), so external MCP clients (IDEs, other agents) can call them. Implemented in `gateway/.../mcp/McpServerEndpoint.kt`, mounted at root (not under `/api`).

### GET /.well-known/mcp

Server Card for discovery (name, protocol version, capabilities, `endpoint: "/mcp"`).

```bash
curl http://localhost:8080/.well-known/mcp
```

### POST /mcp

Full JSON-RPC 2.0 endpoint. Handles `initialize`, `tools/list`, `tools/call`, `tasks/get`, `tasks/cancel`, `notifications/initialized`, `shutdown`. Validates the `MCP-Protocol-Version` header (400 if unsupported), rejects batch JSON-RPC arrays (400), and rejects browser `Origin` values outside `CORS_ALLOWED_ORIGINS` (403). Native MCP clients may omit `Origin`.

```bash
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc": "2.0", "id": 1, "method": "tools/list"}'
```

### GET /mcp/tools

Simple REST listing of all registered built-in tools (debugging/UI only — distinct from `GET /api/v1/mcp/tools` above, which merges in connected external MCP tools).

```bash
curl http://localhost:8080/mcp/tools
```

Prométhé can also run as a **stdio** MCP server instead of the HTTP gateway: `java -jar gateway-all.jar --mcp-stdio`.

---

## Scheduler

### GET /api/v1/scheduler/tasks

```bash
curl http://localhost:8080/api/v1/scheduler/tasks
```

### POST /api/v1/scheduler/tasks

Request field is `cronExpression`, not `cron`. Set `runAt` (epoch ms) instead of `cronExpression` for a one-shot task.

```bash
curl -X POST http://localhost:8080/api/v1/scheduler/tasks \
  -H "Content-Type: application/json" \
  -d '{
    "name": "daily-report",
    "cronExpression": "0 9 * * *",
    "prompt": "Generate a daily activity report",
    "enabled": true
  }'
```

### GET /api/v1/scheduler/tasks/{id}

Get a single scheduled task.

```bash
curl http://localhost:8080/api/v1/scheduler/tasks/task-1719000000000
```

### PUT /api/v1/scheduler/tasks/{id}

### DELETE /api/v1/scheduler/tasks/{id}

### POST /api/v1/scheduler/tasks/{id}/toggle

Toggle a task's `enabled` flag without needing to resend the full body.

```bash
curl -X POST http://localhost:8080/api/v1/scheduler/tasks/task-1719000000000/toggle
```

---

## Channels

### GET /api/v1/channels

```bash
curl http://localhost:8080/api/v1/channels
```

**Response**:
```json
[
  {
    "name": "telegram",
    "displayName": "Telegram",
    "enabled": true,
    "configured": true,
    "requiredEnvVars": ["TELEGRAM_BOT_TOKEN", "TELEGRAM_SECRET_TOKEN"]
  }
]
```

### PUT /api/v1/channels/{name}/config

Set the env vars required/optional for a channel (only keys declared for that channel are accepted).

```bash
curl -X PUT http://localhost:8080/api/v1/channels/telegram/config \
  -H "Content-Type: application/json" \
  -d '{"TELEGRAM_BOT_TOKEN": "123:abc", "TELEGRAM_SECRET_TOKEN": "s3cr3t"}'
# → {"status": "configured", "channel": "telegram"}
```

> Note: there is no `PUT /api/v1/channels/{name}` (bare) — configuration is set via `.../config`.

### POST /api/v1/channels/{name}/test

Best-effort check that a channel's required keys are present (does not perform a live network test).

```bash
curl -X POST http://localhost:8080/api/v1/channels/telegram/test
# → {"status": "ok", "channel": "telegram", "message": "Configuration valid"}
```

Supported channel names: `telegram`, `discord`, `slack`, `whatsapp`, `signal`, `matrix`, `sms`. See [Channel Webhooks](#channel-webhooks-inbound) for the actual inbound receiver endpoints.

### Discord live policy

All endpoints below require owner authentication. Discord IDs may be numeric IDs or Discord mention
syntax. Policies are persisted in SQLite and used immediately by the persistent Gateway and signed
Discord interactions.

| Method | Route | Purpose |
|---|---|---|
| `GET` | `/api/v1/channels/discord/policy` | List dynamic user and channel rules |
| `PUT` | `/api/v1/channels/discord/policy/users/{userId}` | Allow/deny a user and optionally restrict subjects/server/channel |
| `DELETE` | `/api/v1/channels/discord/policy/users/{userId}` | Remove the exact dynamic rule; optional `guildId` and `channelId` query parameters select its scope |
| `PUT` | `/api/v1/channels/discord/policy/channels/{channelId}` | Start/stop Open Knowledge capture and optionally associate a project |
| `DELETE` | `/api/v1/channels/discord/policy/channels/{channelId}` | Remove the dynamic channel override |

Contract signatures: `GET /api/v1/channels/discord/policy`,
`PUT /api/v1/channels/discord/policy/users/{userId}`,
`DELETE /api/v1/channels/discord/policy/users/{userId}`,
`PUT /api/v1/channels/discord/policy/channels/{channelId}` and
`DELETE /api/v1/channels/discord/policy/channels/{channelId}`.

```bash
curl -X PUT http://localhost:8080/api/v1/channels/discord/policy/users/123456789012345678 \
  -H "Authorization: Bearer $PROMETHE_SESSION" \
  -H "Content-Type: application/json" \
  -d '{"effect":"ALLOW","allowedTopics":["weather","public release"]}'

curl -X PUT http://localhost:8080/api/v1/channels/discord/policy/channels/345678901234567890 \
  -H "Authorization: Bearer $PROMETHE_SESSION" \
  -H "Content-Type: application/json" \
  -d '{"captureKnowledge":true,"projectId":"project-release"}'
```

---

## Env Config

### GET /api/v1/config/env

Lists all configured environment variables (masked values).

```bash
curl http://localhost:8080/api/v1/config/env
# → {"GITHUB_TOKEN": "ghp_***", "NOTION_API_KEY": "ntn_***"}
```

### PUT /api/v1/config/env/{key}

```bash
curl -X PUT http://localhost:8080/api/v1/config/env/GITHUB_TOKEN \
  -H "Content-Type: application/json" \
  -d '{"value": "ghp_abc123..."}'
# → {"status": "set", "key": "GITHUB_TOKEN"}
```

### DELETE /api/v1/config/env/{key}

```bash
curl -X DELETE http://localhost:8080/api/v1/config/env/GITHUB_TOKEN
# → {"status": "removed", "key": "GITHUB_TOKEN"}
```

---

## Tool Approval

Tool calls in `DANGEROUS_TOOLS` (`execute_code`, `write_file`, `browser_eval`, `send_email`, `twilio`) require human
approval by default (`APPROVAL_MODE=dangerous`, the secure-by-default setting — do not loosen to `auto` without an
explicit ask). All approval routes below are mounted at **root** (`Route.approvalRoutes` in `SystemAndAuthRoutes.kt`,
called outside `route("api")`) — there is no `/api/approval/*` variant.

### GET /approval/pending

```bash
curl http://localhost:8080/approval/pending
```

**Response**:
```json
[
  {
    "id": "appr-001",
    "toolName": "execute_command",
    "args": {"command": "rm -rf /tmp/old"},
    "createdAt": 1718000000000
  }
]
```

### POST /approval/{id}

```bash
# Approve
curl -X POST http://localhost:8080/approval/appr-001 \
  -H "Content-Type: application/json" \
  -d '{"approved": true}'

# Reject
curl -X POST http://localhost:8080/approval/appr-001 \
  -H "Content-Type: application/json" \
  -d '{"approved": false}'
```

### GET /approval/providers/pending

List pending *provider choice* requests (e.g. when a capability like image generation could route to more than one configured provider).

```bash
curl http://localhost:8080/approval/providers/pending
```

### POST /approval/providers/{id}

Respond to a provider choice request.

```bash
curl -X POST http://localhost:8080/approval/providers/req-002 \
  -H "Content-Type: application/json" \
  -d '{"approved": true, "selectedProviderId": "openai"}'
```

---

## A2A (Agent-to-Agent)

### POST /agents/a2a

JSON-RPC entry point for the A2A protocol (Koog SDK). Supports `message/send`, `message/stream`, `tasks/get`.
(See the [Chat](#chat-via-a2a-protocol) section above for why this is `/agents/a2a` and not bare `/agents/`.)

```bash
curl -X POST http://localhost:8080/agents/a2a \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "message/send",
    "id": "1",
    "params": {
      "message": {
        "role": "user",
        "parts": [{"type": "text", "text": "Analyse ce code"}]
      }
    }
  }'
```

### GET /.well-known/agent.json

Dynamic agent identity card (A2A Agent Card). Reflects tools, skills, and active providers in real time.

```bash
curl http://localhost:8080/.well-known/agent.json
```

---

## Health

### GET /health

```bash
curl http://localhost:8080/health
# → {"status": "ok", "uptime": "2h 15m 30s"}
```

---

## Feedback

### POST /api/v1/feedback

```bash
curl -X POST http://localhost:8080/api/v1/feedback \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "sess-001",
    "rating": 5,
    "comment": "Excellente réponse"
  }'
```

### GET /api/v1/feedback

```bash
curl http://localhost:8080/api/v1/feedback
```

---

## Stats

### GET /api/v1/stats

```bash
curl http://localhost:8080/api/v1/stats
# → {"totalSessions": 42, "totalMessages": 1337, "avgResponseTime": 2.5}
```

---

## RAG / Knowledge Base

### GET /api/v1/rag/config

Retrieve the active RAG configuration.

```bash
curl http://localhost:8080/api/v1/rag/config
# → {"enabled": true, "embeddingProvider": "OLLAMA", "embeddingModel": "nomic-embed-text", "vectorStoreType": "SQLITE_VEC", ...}
```

### PUT /api/v1/rag/config

Update the RAG configuration (rebuilds the KnowledgeBase if enabled).

```bash
curl -X PUT http://localhost:8080/api/v1/rag/config \
  -H "Content-Type: application/json" \
  -d '{
    "enabled": true,
    "embeddingProvider": "OLLAMA",
    "embeddingModel": "nomic-embed-text",
    "vectorStoreType": "SQLITE_VEC",
    "chunkSize": 512,
    "chunkOverlap": 50
  }'
```

### POST /api/v1/rag/test-embedding

Test the connection to the embedding service.

```bash
curl -X POST http://localhost:8080/api/v1/rag/test-embedding
# → {"success": true, "provider": "OLLAMA", "model": "nomic-embed-text", "dimensions": 768, "latencyMs": 42}
```

### GET /api/v1/rag/models

List of known embedding models.

```bash
curl http://localhost:8080/api/v1/rag/models
```

### POST /api/v1/rag/ingest

Ingest a document into the knowledge base.

```bash
curl -X POST http://localhost:8080/api/v1/rag/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Contenu du document à indexer...",
    "filename": "guide.md",
    "metadata": {"source": "manual"}
  }'
# → {"id": "doc-abc123", "filename": "guide.md", "chunkCount": 12, "totalTokens": 4500, "ingestedAt": "2026-01-01T00:00:00Z"}
```

### GET /api/v1/rag/documents

List all ingested documents.

```bash
curl http://localhost:8080/api/v1/rag/documents
```

### DELETE /api/v1/rag/documents/{id}

Delete a document from the base.

```bash
curl -X DELETE http://localhost:8080/api/v1/rag/documents/doc-abc123
```

### POST /api/v1/rag/search

Semantic search in the knowledge base.

```bash
curl -X POST http://localhost:8080/api/v1/rag/search \
  -H "Content-Type: application/json" \
  -d '{"query": "how to configure RAG", "topK": 5}'
# → {"results": [{"content": "...", "score": 0.92, "source": "guide.md"}], "queryTimeMs": 35}
```

### GET /api/v1/rag/status

Check the status of the RAG system (embedding + vector store connectivity).

```bash
curl http://localhost:8080/api/v1/rag/status
# → {"enabled": true, "embeddingOk": true, "vectorStoreOk": true, "totalChunks": 240, "documents": 8}
```

---

## Goals (Autonomous Execution)

### POST /api/v1/goal

Launch an autonomous goal. The agent breaks the goal down into subtasks and executes them sequentially.

```bash
curl -X POST http://localhost:8080/api/v1/goal \
  -H "Content-Type: application/json" \
  -d '{
    "goal": "Analyze the project code and produce a quality report",
    "maxTasks": 10,
    "budgetPreset": "STANDARD"
  }'
```

Budget presets: `MINIMAL`, `STANDARD`, `EXTENDED`, `UNLIMITED`.

### GET /api/v1/goal/status

Track the progress of the current goal.

```bash
curl http://localhost:8080/api/v1/goal/status
# → {"state": "RUNNING", "goal": "...", "tasksTotal": 5, "tasksCompleted": 2, "currentTask": "Analyse statique", "totalTokens": 12000, "totalCost": 0.03}
```

Possible states: `IDLE`, `DECOMPOSING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`.

### POST /api/v1/goal/stop

Stop the current goal (graceful stop).

```bash
curl -X POST http://localhost:8080/api/v1/goal/stop
```

---

## Orchestrator (Multi-Agent)

### POST /api/v1/orchestrator/delegate

Delegate a task to an ephemeral sub-agent.

```bash
curl -X POST http://localhost:8080/api/v1/orchestrator/delegate \
  -H "Content-Type: application/json" \
  -d '{
    "task": "Research Docker security best practices",
    "profileId": "researcher",
    "parentSessionId": "sess-001"
  }'
# → {"childSessionId": "sub-abc123", "status": "delegated"}
```

### GET /api/v1/orchestrator/status/{sessionId}

Check the status of a sub-agent.

```bash
curl http://localhost:8080/api/v1/orchestrator/status/sub-abc123
# → {"sessionId": "sub-abc123", "task": "...", "status": "completed", "response": "...", "durationMs": 15000}
```

### POST /api/v1/orchestrator/{sessionId}/cancel

Cancel a running sub-agent.

```bash
curl -X POST http://localhost:8080/api/v1/orchestrator/sub-abc123/cancel
```

### GET /api/v1/orchestrator/children/{parentSessionId}

List all sub-agents of a parent session.

```bash
curl http://localhost:8080/api/v1/orchestrator/children/sess-001
```

### GET /api/v1/orchestrator/dashboard

Overview of the orchestrator (active, completed agents).

```bash
curl http://localhost:8080/api/v1/orchestrator/dashboard
# → {"activeCount": 2, "completedCount": 15, "subAgents": [...]}
```

---

## Plugins

### GET /api/v1/plugins

List all discovered plugins.

```bash
curl http://localhost:8080/api/v1/plugins
# → {"plugins": [{"name": "weather", "version": "1.0", "enabled": true, "toolCount": 3}], "totalPlugins": 5, "enabledPlugins": 3, "totalTools": 12}
```

### GET /api/v1/plugins/{name}

Detail of a specific plugin.

```bash
curl http://localhost:8080/api/v1/plugins/weather
# → {"name": "weather", "version": "1.0", "description": "...", "author": "...", "enabled": true, "toolCount": 3, "hookCount": 1, "promptCount": 0}
```

### POST /api/v1/plugins/{name}/toggle

Enable/disable a plugin (⚠️ not implemented at runtime, returns 501).

```bash
curl -X POST http://localhost:8080/api/v1/plugins/weather/toggle \
  -H "Content-Type: application/json" \
  -d '{"enabled": false}'
```

---

## Skills

### GET /api/v1/skills

List all managed skills, including non-active lifecycle states. Only `ACTIVE` skills are available to agents.

```bash
curl http://localhost:8080/api/v1/skills
# → {"skills": [{"name": "code_review", "description": "...", "preview": "...", "isSystem": true, "contract": {"lifecycle": "ACTIVE", "contentHash": "..."}}]}
```

### GET /api/v1/skills/{name}

Retrieve the full content of a skill.

```bash
curl http://localhost:8080/api/v1/skills/code_review
```

### POST /api/v1/skills

Create a new `DRAFT` skill.

```bash
curl -X POST http://localhost:8080/api/v1/skills \
  -H "Content-Type: application/json" \
  -d '{
    "name": "data_analysis",
    "description": "Analyse de données structurées",
    "content": "# Data Analysis\n\nVous êtes un expert en analyse de données..."
  }'
```

### PUT /api/v1/skills/{name}

Update an existing skill. Non-system skills are moved to `QUARANTINED` and stop being available to agents.

```bash
curl -X PUT http://localhost:8080/api/v1/skills/data_analysis \
  -H "Content-Type: application/json" \
  -d '{"content": "# Data Analysis v2\n\nContenu mis à jour..."}'
```

### PUT /api/v1/skills/{name}/lifecycle

Apply an owner-reviewed lifecycle transition. Direct `DRAFT → ACTIVE` activation is refused; the promotion
path is `DRAFT → QUARANTINED → CANDIDATE → ACTIVE`. System skill lifecycle cannot be changed.

```bash
curl -X PUT http://localhost:8080/api/v1/skills/data_analysis/lifecycle \
  -H "Content-Type: application/json" \
  -d '{"lifecycle": "QUARANTINED"}'
```

### DELETE /api/v1/skills/{name}

Delete a skill (system skills cannot be deleted).

```bash
curl -X DELETE http://localhost:8080/api/v1/skills/data_analysis
```

### POST /api/v1/skills/curate

Run a non-destructive quality pass. The response contains quality issues and typed `QUARANTINED` proposals for low-quality or near-duplicate skills. No skill is deleted, merged, or rewritten; legacy `merged` and `deleted` counters remain zero.

```bash
curl -X POST http://localhost:8080/api/v1/skills/curate
```

---

## Context Files

### GET /api/v1/context/files

List all context files (`.promethe.md`, `SOUL.md`, `AGENTS.md`, `CONTEXT.md`).

```bash
curl http://localhost:8080/api/v1/context/files
# → {"files": [{"name": ".promethe.md", "content": "...", "exists": true, "sizeBytes": 1234}], "directory": "/path/to/promethe"}
```

### GET /api/v1/context/files/{name}

Retrieve a specific context file.

```bash
curl http://localhost:8080/api/v1/context/files/SOUL.md
```

### PUT /api/v1/context/files/{name}

Create or update a context file.

```bash
curl -X PUT http://localhost:8080/api/v1/context/files/SOUL.md \
  -H "Content-Type: application/json" \
  -d '{"content": "# Personnalité\n\nVous êtes un assistant professionnel..."}'
```

### DELETE /api/v1/context/files/{name}

Delete a context file.

```bash
curl -X DELETE http://localhost:8080/api/v1/context/files/.promethe.md
```

---

## OpenAI Compatible

Prométhé exposes an OpenAI-compatible endpoint, allowing it to be used as a drop-in replacement in any SDK/IDE that speaks the OpenAI protocol.

### POST /v1/chat/completions

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "default",
    "messages": [
      {"role": "system", "content": "Tu es un assistant technique."},
      {"role": "user", "content": "Explain Go goroutines"}
    ],
    "temperature": 0.7,
    "max_tokens": 2048,
    "stream": false
  }'
```

With SSE streaming (`stream: true`), chunks arrive in `data: {...}\n\n` format.

### GET /v1/models

List of available models.

```bash
curl http://localhost:8080/v1/models
```

---

## ACP (Agent Communication Protocol)

### GET /.well-known/acp.json

Discovery of the Agent Card (exposed capabilities).

```bash
curl http://localhost:8080/.well-known/acp.json
```

### POST /acp/invoke

Invoke an agent capability via ACP.

```bash
curl -X POST http://localhost:8080/acp/invoke \
  -H "Content-Type: application/json" \
  -d '{
    "capabilityId": "chat",
    "input": "Résume ce document",
    "parameters": {},
    "contextId": "ctx-001"
  }'
# → {"output": "Le document traite de...", "status": "COMPLETED", "taskId": "acp-ctx-001"}
```

### GET /acp/health

Health check for the ACP protocol.

```bash
curl http://localhost:8080/acp/health
# → {"status": "ok", "protocol": "ACP/1.0", "capabilities": "5"}
```

---

## System & Authentication

### GET /health

Minimal public liveness probe. Mounted at root, **not** under `/api`.

```bash
curl http://localhost:8080/health
# → {"status":"ok"}
```

### GET /health

Authenticated application health route. It is distinct from the public liveness probe above.

```bash
curl http://localhost:8080/health
# → {"status":"healthy","timestamp":1718000000000}
```

### GET /api/v1/system/diagnostics

Authenticated diagnostics with JVM uptime and memory data. Metrics and diagnostics are never exposed by
the public liveness endpoint.

```bash
curl -H "Authorization: Bearer $SESSION" http://localhost:8080/api/v1/system/diagnostics
```

### POST /auth/login

Starts an opted-in remote owner session. Native clients send `clientKind: "NATIVE"` and receive an opaque
short-lived token plus `expiresAt`. Browser clients send `clientKind: "BROWSER"`; the response has no token
because the session is delivered only through a strict secure cookie.

```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"user":"owner","password":"correct-horse-battery-staple","clientKind":"NATIVE"}'
# → {"accessToken":"pss_...","expiresAt":1718003600000}
```

### POST /auth/logout

Revokes the current owner session and clears the browser cookie when present.

### GET /api/v1/auth/session

Returns the current owner session, its expiration, and the CSRF token required for browser-cookie mutations.
Native Bearer clients receive the same session metadata but are not subject to cookie CSRF checks.

### POST /api/v1/auth/logout-all

Revokes every owner session. Browser callers must send the current `csrfToken` as `X-CSRF-Token`; native
clients authenticate with their in-memory Bearer session. The current browser cookie is also cleared.

### PUT /api/v1/security/remote-owner

Creates or replaces the single remote owner. This bootstrap route requires `REMOTE_ACCESS_ENABLED=true`,
the local `pk-prom-` key in `Authorization`, and a loopback connection. Replacing the owner revokes all
existing sessions.

```bash
curl -X PUT http://localhost:8080/api/v1/security/remote-owner \
  -H "Authorization: Bearer $LOCAL_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"user":"owner","password":"correct-horse-battery-staple"}'
# → {"user":"owner","sessionsRevoked":false}
```

The former raw HTTP command endpoint and the legacy remote setup route were removed and return `404`.

### Approval Gate

See the [Tool Approval](#tool-approval) section above — `GET/POST /approval/...` (root, no `/api` prefix).

---


## Voice

Voice configuration, TTS synthesis, and real-time voice sessions via WebSocket.

### GET /api/v1/voice/providers

List implemented and configured voice providers. Known providers without a runtime backend are omitted from
this selectable list and reported as `UNAVAILABLE` by `/api/v1/capabilities`. Filter by capability with the
`cap` parameter (`S2S`, `TTS`, `STT`, `TRANSLATE`).

```bash
# All providers
curl http://localhost:8080/api/v1/voice/providers

# Speech-to-Speech providers only
curl "http://localhost:8080/api/v1/voice/providers?cap=S2S"
```

### GET /api/v1/voice/voices

Retrieve the available voices for a given provider.

```bash
curl "http://localhost:8080/api/v1/voice/voices?provider=openai_tts"
```

### GET /api/v1/voice/models

List available models for a provider and capability.

```bash
curl "http://localhost:8080/api/v1/voice/models?provider=gemini_live&cap=S2S"
```

### POST /api/v1/voice/preview

Synthesize a short voice sample for preview.

```bash
curl -X POST http://localhost:8080/api/v1/voice/preview \
  -H "Content-Type: application/json" \
  -d '{"provider": "openai_tts", "voice": "alloy", "text": "Bonjour, ceci est un test."}'
```

### POST /api/v1/tts/synthesize

Full TTS synthesis (used by the playback button in chat).

```bash
curl -X POST http://localhost:8080/api/v1/tts/synthesize \
  -H "Content-Type: application/json" \
  -d '{
    "text": "Hello, this is text-to-speech.",
    "provider": "openai_tts",
    "voice": "alloy"
  }'
```

### WS /ws/chat/voice

WebSocket for real-time voice sessions. Protocol:

1. The client sends a `VoiceSessionConfig` (JSON text frame) as the first frame
2. The client sends `AudioIn` frames (mic audio, base64)
3. The server responds with `AudioOut` (synthesized audio), `Transcript` (transcription), `ToolCall` (tool calls)
4. The client sends `SessionEnd` to disconnect

```javascript
// A browser uses the secure owner-session cookie established at login.
const ws = new WebSocket("wss://gateway.example/ws/chat/voice");

// 1. Send the config
ws.onopen = () => {
  ws.send(JSON.stringify({
    provider: "gemini_live",
    model: "gemini-3.1-flash-live-preview",
    voice: "Puck",
    systemInstructions: "Tu es un assistant vocal."
  }));
};

// 2. Receive events
ws.onmessage = (event) => {
  const evt = JSON.parse(event.data);
  // evt.type: "AudioOut" | "Transcript" | "ToolCall" | "Error" | "SessionEnd"
};
```

---

## Webhooks

Reception of external events (inbound), manual dispatch, and webhook channel management.

### POST /api/v1/webhooks/in/{channel}

Entry point for inbound webhooks (third-party platforms like Telegram, Slack, etc.).

```bash
curl -X POST http://localhost:8080/api/v1/webhooks/in/custom-channel \
  -H "Content-Type: application/json" \
  -d '{"message": "Hello from external system"}'
# → {"response": "Réponse de l'agent..."}
```

### POST /api/v1/webhooks/dispatch

Manually send a message to an outbound webhook channel.

```bash
curl -X POST http://localhost:8080/api/v1/webhooks/dispatch \
  -H "Content-Type: application/json" \
  -d '{"channel": "slack-alerts", "content": "Déploiement terminé ✅"}'
# → {"success": true, "channel": "slack-alerts", "statusCode": 200}
```

### GET /api/v1/webhooks/channels

List all configured webhook channels.

```bash
curl http://localhost:8080/api/v1/webhooks/channels
# → {"channels": [{"id": "wh-001", "name": "slack-alerts", "type": "slack", "enabled": true, ...}]}
```

### POST /api/v1/webhooks/channels

Create a new webhook channel.

```bash
curl -X POST http://localhost:8080/api/v1/webhooks/channels \
  -H "Content-Type: application/json" \
  -d '{
    "name": "slack-alerts",
    "type": "slack",
    "secret": "xoxb-...",
    "outboundUrl": "https://hooks.slack.com/services/...",
    "enabled": true
  }'
# → {"id": "wh-1719000000000"}
```

### PUT /api/v1/webhooks/channels/{id}

Update an existing webhook channel.

```bash
curl -X PUT http://localhost:8080/api/v1/webhooks/channels/wh-001 \
  -H "Content-Type: application/json" \
  -d '{"name": "slack-alerts", "type": "slack", "enabled": false}'
# → {"updated": "wh-001"}
```

### DELETE /api/v1/webhooks/channels/{id}

Delete a webhook channel.

```bash
curl -X DELETE http://localhost:8080/api/v1/webhooks/channels/wh-001
# → {"deleted": "wh-001"}
```

---

## Channel Webhooks (inbound)

Distinct from the generic `/api/v1/webhooks/*` system above: these are the dedicated, platform-specific
receiver endpoints for the built-in messaging channels (`ChannelWebhookRoutes.kt`), mounted at **root**
(no `/api` prefix). Each verifies the platform's own signature scheme (`WebhookAuth.kt`) before routing
the message through the shared A2A pipeline.

| Method | Path | Platform |
|---|---|---|
| POST | `/webhook/telegram` | Telegram (secret-token header) |
| GET | `/webhook/whatsapp` | WhatsApp Cloud API — verification handshake (`hub.mode`/`hub.verify_token`/`hub.challenge`) |
| POST | `/webhook/whatsapp` | WhatsApp Cloud API — inbound message (HMAC-SHA256 via `X-Hub-Signature-256`) |
| POST | `/webhook/discord` | Discord Interactions (Ed25519 signature; replies to PING type=1 for URL verification) |
| POST | `/webhook/slack` | Slack Events API (HMAC signing secret; handles `url_verification` challenge) |
| POST | `/webhook/signal` | Signal (via signal-cli REST API) |
| POST | `/webhook/matrix` | Matrix Appservice |
| POST | `/webhook/sms` | Twilio SMS (`X-Twilio-Signature`; empty TwiML acknowledgement, asynchronous A2A reply) |

```bash
curl -X POST http://localhost:8080/webhook/telegram \
  -H "Content-Type: application/json" \
  -H "X-Telegram-Bot-Api-Secret-Token: <secret>" \
  -d '{"message": {"chat": {"id": 123}, "text": "Hello"}}'
```

Each handler creates/reuses a session (`telegram-{chatId}`, `whatsapp-{from}`, `discord-{channelId}`,
`slack-{channel}`, `signal-{source}`, `matrix-{roomId}`) and returns the agent's reply as `{"reply": "..."}`
(Discord replies in its own interaction-response envelope instead). These routes are exempt from Bearer
auth (`AuthMiddleware` treats `/webhook/` as public) and from the rate limiter.

---

## Status Dashboard

Aggregated system dashboard: runtime, LLM, memory, hooks, scheduler, plugins, and provider health.

### GET /api/v1/status

Complete system status in a single call.

```bash
curl http://localhost:8080/api/v1/status
```

**Response**:
```json
{
  "runtime": {
    "uptimeMs": 86400000,
    "uptimeHuman": "1d 0h 0m",
    "model": "google/gemini-3.5-flash",
    "provider": "openrouter",
    "executionBackend": "koog"
  },
  "llm": {
    "total_requests": 142,
    "total_prompt_tokens": 50000,
    "total_completion_tokens": 30000,
    "total_cost_usd": 0.15,
    "cache": {
      "hits": 40,
      "misses": 102,
      "size": 40,
      "hit_rate": 0.28,
      "read_tokens": 32000,
      "write_tokens": 12000,
      "observable_responses": 142,
      "prefix_reuse_hits": 120,
      "prefix_reuse_misses": 22
    }
  },
  "memory": {
    "provider": "embedded",
    "factCount": 42
  },
  "hooks": {
    "registeredCount": 3,
    "hooks": ["before_response", "after_tool", "on_error"]
  },
  "scheduler": {
    "running": true,
    "taskCount": 5
  },
  "plugins": {
    "total": 5,
    "enabled": 3,
    "tools": 12
  }
}
```

### GET /api/v1/status/providers

Detailed health of each configured LLM provider (key pool, status, usage).

```bash
curl http://localhost:8080/api/v1/status/providers
```

**Response**:
```json
{
  "providers": [
    {
      "name": "openrouter",
      "keyPoolSize": 2,
      "hasExecutor": true,
      "status": "active",
      "isActive": true,
      "model": "google/gemini-3.5-flash",
      "totalRequests": 100,
      "totalTokens": 75000,
      "totalCost": 0.12
    }
  ]
}
```

### POST /api/v1/settings/test-llm

Test connectivity to an LLM provider before configuring it.

```bash
curl -X POST http://localhost:8080/api/v1/settings/test-llm \
  -H "Content-Type: application/json" \
  -d '{"provider": "openai", "apiKey": "sk-...", "model": "gpt-4o"}'
# → {"ok": true, "latencyMs": 320}
```

### POST /api/v1/settings/reload

Reload API keys and the active provider/model from `credentials.json` without restarting. Long-lived
media tools and the capability router read the refreshed provider-key registry on their next call.

```bash
curl -X POST http://localhost:8080/api/v1/settings/reload
# → {"status":"ok","providersReloaded":true}
```

---

## OAuth (GitHub and Google Calendar)

OAuth is mounted only when `OAUTH_ENABLED=true`, `REMOTE_ACCESS_ENABLED=true`, an explicit
`OAUTH_REDIRECT_URI`, a Base64 32-byte `PROMETHE_MASTER_KEY`, and GitHub and/or Google client credentials
are configured. OAuth connections belong to the single owner and their token payloads are AES-256-GCM
encrypted in SQLite.

### GET /auth/oauth/{provider}/authorize

Starts OAuth for `github` or `google`. This route requires an owner session. The server chooses the fixed
configured redirect URI, generates an expiring one-use signed state value, and uses PKCE where supported.
Clients must not provide a redirect URI or state value.

```bash
curl -H "Authorization: Bearer $SESSION" \
  http://localhost:8080/auth/oauth/github/authorize
# → {"authorization_url":"https://github.com/login/oauth/authorize?..."}
```

### GET /auth/oauth/callback

The provider calls this public endpoint with only `code` and the one-use `state`. The callback rejects a
client-provided redirect URI, consumes the state once, and stores the encrypted connection.

### GET /api/v1/oauth/connections

Lists the connected OAuth providers for the current owner session.

```bash
curl -H "Authorization: Bearer $SESSION" http://localhost:8080/api/v1/oauth/connections
# → {"connections":["github","google"]}
```

### DELETE /api/v1/oauth/{provider}

Disconnects `github` or `google` for the current owner and removes its locally stored encrypted tokens.

---

## Session Checkpoints

Save and restore session state (checkpoint / rollback).

### GET /api/v1/sessions/{id}/checkpoint

Retrieve the latest checkpoint of a session.

```bash
curl http://localhost:8080/api/v1/sessions/sess-001/checkpoint
```

Returns the full checkpoint JSON, or `404` if no checkpoint exists.

### POST /api/v1/sessions/{id}/rollback

Restore a session to its latest checkpoint.

```bash
curl -X POST http://localhost:8080/api/v1/sessions/sess-001/rollback
# → {"status": "rollback_ready", "checkpoint": {...}}
```

### DELETE /api/v1/sessions/{id}/checkpoints

Delete all checkpoints of a session.

```bash
curl -X DELETE http://localhost:8080/api/v1/sessions/sess-001/checkpoints
# → {"status": "cleared"}
```

---

## Agent Tools

These are not HTTP routes — they are the tools registered into `ToolRegistry` that the agent itself can call
during its Perception–Raisonnement–Action loop (see `AgentBootstrap.kt` and `IntegrationRegistrar.kt`). They're
listed here because `GET /api/v1/mcp/tools` and `GET /mcp/tools` both expose this registry over HTTP, and the tool
names below are exactly what appears in those responses and in `toolName` fields elsewhere in this document
(e.g. the Tool Approval section).

> **Security**: tool calls named in `DANGEROUS_TOOLS` — `execute_code`, `write_file`, `browser_eval`,
> `send_email`, `twilio` — require human approval by default (`APPROVAL_MODE=dangerous`, secure-by-default).
> `execute_code` is opt-in and always launches through the native sandbox. Shell-script languages are
> unavailable; Python, JavaScript and Kotlin are the supported structured code paths.

### Always registered (21 tools — `AgentBootstrap.kt`)

Core tools needed regardless of configuration: filesystem primitives, delegation/agent management, the
memory + skill learning loop, and self-introspection.

| Tool name | Purpose |
|---|---|
| `read_file` | Read a file from the agent's working directory |
| `write_file` | Write a file (⚠️ approval-gated) |
| `http_fetch` | Fetch an arbitrary URL |
| `delegate_task` | Delegate a task to an ephemeral sub-agent |
| `get_subtask_result` | Retrieve a delegated sub-agent's result |
| `list_agents` | List agent profiles |
| `promote_agent` | Promote an ephemeral agent to a persistent profile |
| `cleanup_ephemeral_agents` | Garbage-collect ephemeral agent profiles |
| `create_agent` | Create a new agent profile (hot-registers into the A2A registry) |
| `memory_save` | Store a memory fact |
| `memory_search` | Recall memory facts |
| `memory_forget` | Delete a memory fact |
| `memory_list` | List all memory facts |
| `skill_search` | Search available skills |
| `skill_load` | Load a skill's content |
| `skill_create` | Create a new skill |
| `skill_improve` | Revise an existing skill |
| `skill_list` | List all skills |
| `agent_status` | Report the agent's own runtime/LLM status |
| `session_history` | Read a session's message history |
| `token_budget` | Report token usage/budget |

### Media tools — always registered (5 tools — `IntegrationRegistrar.registerIntegrationTools`)

Registered unconditionally; each routes through `CapabilityRouter` to whichever configured provider
supports the capability (missing provider = tool call fails at invocation, not at registration).

| Tool name | Purpose |
|---|---|
| `generate_image` | Image generation |
| `analyze_image` | Vision / image analysis |
| `text_to_speech` | TTS synthesis |
| `generate_video` | Video generation |
| `analyze_video` | Video analysis |

> Code quirk: `generate_video`/`analyze_video` are constructed twice in `IntegrationRegistrar.kt` — once in
> `registerIntegrationTools` (line ~34) and again in `registerExtendedTools` (line ~302), both called from
> `AgentBootstrap.create`. `ToolRegistry.register` de-dupes by tool name, so only one registration wins in
> practice — the tools show up once, not twice, in `ToolRegistry`/`/mcp/tools` listings.

### Integration tools — conditional on env vars (`IntegrationRegistrar.registerIntegrationTools`)

| Tool name | Registered when |
|---|---|
| `github` | `GITHUB_TOKEN` is set |
| `send_email` | `EMAIL_API_KEY` **and** `EMAIL_FROM` are set (provider: `EMAIL_PROVIDER`, default `resend`) — ⚠️ approval-gated |
| `calendar` | `GOOGLE_CALENDAR_TOKEN` is set |
| `notion` | `NOTION_API_KEY` is set |
| `jira` | `JIRA_URL` **and** `JIRA_API_TOKEN` are set |
| `twilio` | `TWILIO_ACCOUNT_SID` **and** `TWILIO_AUTH_TOKEN` are set — ⚠️ approval-gated |
| `web_scrape` | always (no key required) |
| `execute_code` | `CODE_EXECUTION_ENABLED=true` — ⚠️ approval-gated, sandboxed via `EXEC_BACKEND` (default `local`); Python, JavaScript and Kotlin only |
| `browser_navigate`, `browser_click`, `browser_type`, `browser_extract`, `browser_screenshot`, `browser_eval`, `browser_scroll`, `browser_back`, `browser_press`, `browser_get_images`, `browser_dialog` (11 tools, `BrowserTools.create`) | `BROWSER_CDP_PORT` is set (local CDP backend), **or** `BROWSER_BACKEND=browserbase` with `BROWSERBASE_API_KEY` + `BROWSERBASE_PROJECT_ID` set (cloud backend) |

`browser_eval` is in `DANGEROUS_TOOLS` and requires approval.
`browser_vision` is intentionally unavailable and is not registered until a VLM integration performs real screenshot analysis.

### Extended tools — always registered (`IntegrationRegistrar.registerExtendedTools`)

**Filesystem (6)**: `file_delete`, `file_move`, `directory_tree`, `file_search`, `code_grep`, `patch`

**Git (5)**: `git_status`, `git_diff`, `git_commit`, `git_log`, `git_branch`

**System (5)**: `shell`, `process_manager`, `system_info`, `docker`, `environment`

**Web (6)**: `web_crawl`, `rss_feed`, `web_screenshot`, `web_search`, `web_extract`, `x_search`

**Data & productivity (6)**: `json_query`, `csv`, `pdf_reader`, `api_call`, `todo`, `notes`

**Security (3)**: `hash`, `encrypt`, `cert_check`

**Communication (2 unconditional + 1 conditional)**: `signal`, `discord` (always); `slack` only when `SLACK_TOKEN` is set

**AI (3)**: `embedding`, `speech_to_text`, `vector_search`

**Agent-level (11)**: `clarify`, `cronjob`, `send_message`, `session_search`, `mixture_of_agents`, `config_get`, `config_set`, `plugin_list`, `checkpoint_save`, `checkpoint_list`, `render_ui`

**Gateway administration (1)**: `discord_policy` — owner A2A conversations only; mutations require approval.

**Autonomous (1)**: `autonomous_goal`

### RAG tools — conditional (`IntegrationRegistrar.registerExtendedTools`)

| Tool name | Registered when |
|---|---|
| `knowledge_search` | `RAG_ENABLED=true` (and `KnowledgeBase` init succeeds) |
| `knowledge_ingest` | same as above |
| `knowledge_delete` | same as above |

> `ragRoutes()`'s `PUT /api/v1/rag/config` can also register `knowledge_search` at runtime (hot-registration via the Settings UI), independent of the startup path above.

### Home Assistant tools — conditional (`IntegrationRegistrar.registerExtendedTools`)

| Tool name | Registered when |
|---|---|
| `ha_list_entities` | `HA_URL` is set |
| `ha_get_state` | same as above |
| `ha_list_services` | same as above |
| `ha_call_service` | same as above |
