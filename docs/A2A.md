# Agent-to-Agent (A2A / ACP)

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** See [project status / statut du projet](EXPERIMENTAL_STATUS.md).

## Overview

Promethe treats every agent in the system — the main agent, dynamically-created sub-agents,
and external agents on other machines — as an addressable A2A (Agent-to-Agent) peer. A single
registry (`AgentA2ARegistry`) is the only way any part of the system talks to an agent: the
gateway's chat pipeline, the orchestrator, `delegate_task`, webhooks, the scheduler, and the
desktop UI all route through it.

Two protocols are supported side by side:

- **Google A2A** — JSON-RPC 2.0 over HTTP, using the `ai.koog.a2a` SDK. This is the primary
  protocol: local agents are wrapped as `AgentExecutor`s and called either in-process (loopback)
  or over HTTP (remote), and the desktop client streams responses from it.
- **ACP (Agent Communication Protocol)** — a simpler, Hermes-Agent-style discovery + invoke
  protocol (`AcpAgentCard` / `AcpRequest` / `AcpResponse`) exposed by the gateway for external
  tooling that expects a lighter-weight capability-invocation surface. It's a thin façade: actual
  execution still goes through the same internal pipeline as everything else.

## Architecture

```
                    ┌───────────────────────────────┐
                    │      AgentA2ARegistry          │   shared/jvmMain
                    │  (dev.promethe.core)           │
                    │                                │
                    │  localAgents:  id → LocalAgent  │
                    │    (card, executor, transport)  │
                    │  remoteAgents: id → RemoteAgent  │
                    │    (card, url)                  │
                    └───────┬───────────────┬─────────┘
                            │               │
              in-process    │               │  HTTP (Google A2A JSON-RPC)
       A2ALoopbackTransport │               │  A2AClientTool
                            │               │
                ┌───────────▼──┐      ┌─────▼───────────────┐
                │  AIAgent /    │      │  Remote agent        │
                │  Profile      │      │  (another Promethe   │
                │  AgentExecutor│      │   instance or 3rd    │
                └───────────────┘      │   party A2A server)  │
                                       └───────────────────────┘

  gateway/jvmMain
  ┌─────────────────────────────────────────────────────────────┐
  │  PrometheA2A.installRoutes()  → POST /agents/a2a (JSON-RPC)  │
  │  AcpRoutes.acpRoutes()        → /acp/*, /.well-known/acp.json │
  │  A2AInternalClient            → shared execution bridge      │
  │    used by ACP, webhooks, goals, scheduler                   │
  └─────────────────────────────────────────────────────────────┘
                            │
                            │ HTTP JSON-RPC (message/send, tasks/sendSubscribe)
                            ▼
  composeApp/commonMain
  ┌─────────────────────────────────────────────────────────────┐
  │  A2AChatClient — Koog A2AClient, streams ChatEvent to the UI  │
  └─────────────────────────────────────────────────────────────┘
```

The registry itself never touches the network for local agents — the loopback transport calls
the `AgentExecutor` directly in the same process. Only `remoteAgents` cross an HTTP boundary via
`A2AClientTool`. The gateway's own A2A server (`PrometheA2A`) is a separate, independent piece:
it's what makes *this* Promethe instance discoverable and callable by other A2A peers (including
another Promethe registering it as a `RemoteAgent`), while `AgentA2ARegistry` is how *this*
instance dispatches to its own local/remote agents.

## Components (shared/jvmMain)

### AgentA2ARegistry

`shared/src/jvmMain/kotlin/dev/promethe/core/AgentA2ARegistry.kt`

Central catalog of all agents, local and remote, guarded by a `Mutex`. Two backing maps:

- `localAgents: MutableMap<String, LocalAgent>` — `LocalAgent(card, executor, transport)`, where
  `transport` is a dedicated `A2ALoopbackTransport` wrapping the `AgentExecutor`.
- `remoteAgents: MutableMap<String, RemoteAgent>` — `RemoteAgent(card, url)`.

A lazily-created `A2AClientTool` (with a 30s request timeout / 5s connect timeout `HttpClient`)
is shared across all remote calls.

Operations:

| Method | Behavior |
|---|---|
| `registerLocal(agentId, card, executor)` | Wraps the executor in a fresh `A2ALoopbackTransport` and stores it. Standard boot-time registration path. |
| `registerRemote(agentId, url, card)` | Registers an agent by its A2A endpoint URL (card assumed already discovered). |
| `unregister(agentId)` | Removes the id from both maps (idempotent). |
| `sendTask(agentId, userMessage, taskId)` | Looks up local first, then remote; routes to `A2ALoopbackTransport.sendTask` or `A2AClientTool.sendTask` respectively. Throws `IllegalArgumentException` (listing available ids) if the agent isn't found. |
| `sendTaskStreaming(agentId, userMessage, taskId)` | **Local agents only** — returns a `Flow<A2AStreamEvent>` from the loopback transport. Throws if the id isn't a local agent (no streaming fallback for remote). |
| `getAgentCard(agentId)` | Returns the `AgentCard` for a local or remote agent, or `null`. |
| `listAgentIds()` / `listAgentCards()` | Enumerate all registered agents (local + remote) for discovery. |
| `findBySkill(skillId)` | Scans both maps for agents whose `card.skills` contains a matching `AgentSkill.id`; returns `List<Pair<agentId, AgentCard>>`. |
| `isLocal(agentId)` | `true` if the id is in `localAgents`. |
| `mainAgent()` | Returns `localAgents["main"]`, falling back to the first registered local agent if `"main"` isn't present. |

Covered by `shared/src/jvmTest/kotlin/dev/promethe/core/AgentA2ARegistryTest.kt` (empty-registry,
register/unregister, card lookup, `findBySkill`, and the not-found exception on `sendTask`).

### A2ALoopbackTransport

`shared/src/jvmMain/kotlin/dev/promethe/core/A2ALoopbackTransport.kt`

In-memory transport for local agents — calls the `AgentExecutor` directly instead of making an
HTTP round-trip, at "<0.5ms per call" per the class doc. It manufactures the `SessionEventProcessor`
and `RequestContext` an `AgentExecutor` expects, backed by its own `InMemoryTaskStorage` /
`InMemoryMessageStorage` instances (one pair per transport instance, i.e. per local agent).

- `sendTaskStreaming(taskId, message)` — returns a `Flow<A2AStreamEvent>` that runs
  `executor.execute(context, eventProcessor)`, closes the processor, and emits a final
  `A2AStreamEvent.StatusEvent("completed")`.
- `sendTask(taskId, message)` — blocking variant: runs the executor, then reads the final
  response back out of `taskStorage` (`status.message` parts, falling back to the last history
  entry) and returns an `A2ATaskResult(taskId, response, events)`.

`A2AStreamEvent` is a sealed class with `MessageEvent` and `StatusEvent(state, description)`
variants; `A2ATaskResult(taskId, response, events)` is the blocking-call result type shared with
`AgentA2ARegistry.sendTask`.

### AgentA2ABootstrap

`shared/src/jvmMain/kotlin/dev/promethe/core/AgentA2ABootstrap.kt`

Registers agents into the `AgentA2ARegistry` at startup, and again at runtime for dynamically
created agents:

- `registerMainAgent(agent: AIAgent)` — builds an `AgentCard` for `"Prométhé Main Agent"`
  (`url = "loopback://main"`) with four fixed skills (`chat`, `code`, `research`, `delegate`),
  `AgentCapabilities(streaming = true, pushNotifications = false, stateTransitionHistory = true)`,
  wraps the `AIAgent` in an `AIAgentA2AExecutor`, and calls `registry.registerLocal("main", ...)`.
- `registerProfileAgents()` — loads all rows via `database.getAllAgentProfiles()` and calls
  `registerSingleAgent(profile)` for each.
- `registerSingleAgent(profile: AgentProfileRow)` — **hot-registration entry point**. Builds an
  `AgentCard` (`url = "loopback://${profile.id}"`) whose skills are derived from the profile's
  comma-separated `tools` field (one `AgentSkill` per tool, id `"tool-<name>"`), wraps the profile
  in a `ProfileAgentA2AExecutor`, and registers it under the profile's own id. This is what lets a
  freshly-created agent be addressable via `delegate_task` **without a restart**: the `create_agent`
  tool (`CreateAgentTool` in `AgentManagementTools.kt`) accepts an `onAgentCreated` callback, and
  `AgentBootstrap.kt` wires it to `a2aBootstrap.registerSingleAgent(profileRow)`.

Two `AgentExecutor` adapters live in this file:

- `AIAgentA2AExecutor(executionService)` — bridges the shared `AgentExecutionService` to A2A. Streams
  `trajectory.outputs["thought"]` / `["action"]` as `TaskStatusUpdateEvent`s tagged with
  `metadata.type = "thought" | "action"`, then emits the final response as a `Completed` status
  event.
- `ProfileAgentA2AExecutor(profile, executionService)` — supplies the profile id to the same
  service. It never calls an LLM directly.

### A2AClientTool

`shared/src/jvmMain/kotlin/dev/promethe/core/A2AClientTool.kt`

Client for talking to *external* A2A agents over HTTP/HTTPS — used both directly and as the
shared `remoteClient` inside `AgentA2ARegistry`.

- **Discovery**: `discover(baseUrl)` — `GET {baseUrl}/.well-known/agent.json`, decoded into a
  local `A2AClientTool.AgentCard` (name, description, url, version, capabilities). Wrapped in
  `withRetry` (up to 3 attempts, exponential backoff from 1s up to a 10s cap). Returns `null` on
  failure rather than throwing.
- **Communication**: `sendTask(agentUrl, taskDescription, sessionId)` — `POST {agentUrl}/a2a`
  with a hand-built JSON-RPC 2.0 envelope (`method = "tasks/send"`, a `params.message` with a
  single text part). Parses `result.status.message.parts[0].text` out of the response, or surfaces
  `error.message`. Returns an `A2ATaskResult(success, agentName, response, error)` — note this is
  a *different* `A2ATaskResult` shape than the one in `A2ALoopbackTransport.kt` (local vs. remote
  result types share a name but not a definition).

Both `discover` and `sendTask` catch exceptions internally and report failure through their return
value instead of throwing, so callers (like `AgentA2ARegistry.sendTask`) can treat "remote agent
unreachable" as an ordinary failed-task result.

## Gateway (gateway/jvmMain)

### PrometheA2A / PrometheA2AExecutor

`gateway/src/jvmMain/kotlin/dev/promethe/gateway/PrometheA2AExecutor.kt`

This is the gateway's own Google A2A **server** — it's what makes the running Promethe instance
discoverable and callable by other agents, separate from the in-process `AgentA2ARegistry` used
for `delegate_task`.

- `buildAgentCard(baseUrl, skillLoader, database)` builds a **dynamic** `AgentCard` on every call
  (not cached), so a peer discovering Promethe always sees current capabilities:
  - core tool skills from `ToolRegistry.toolsSnapshot()` (id `"tool-<name>"`)
  - agent skills from `SkillLoader.listSkills()` (id `"skill-<name>"`, description = first
    non-blank/non-heading line of the skill's Markdown, truncated to 200 chars)
  - sub-agent skills synthesized from active sessions whose id starts with `"a2a-"` (up to 10, id
    `"agent-<sessionId>"`)
  - `AgentCard.url = "$baseUrl/agents/a2a"`, `provider = AgentProvider(organization = "Promethe", url = baseUrl)`,
    capabilities `streaming = true, pushNotifications = false, stateTransitionHistory = true`.
- `createExecutor(executionService, database)` returns the `AgentExecutor` used by the server.
  Per request it: extracts text from the incoming message parts; derives `a2aContextId` from
  `context.contextId` (required by the Koog SDK for all emitted events) and a DB `sessionId` from
  `message.contextId` (falling back to `"a2a-${taskId}"`); reads optional `provider`/`model`/
  `profileId` overrides out of `message.metadata` (set by the desktop client when the user picks a
  non-default agent profile). All requests become an `AgentExecutionRequest`; profile resolution
  and the only call to `AIAgent.executeLoop()` live in `AgentExecutionService`.
  - Streams each trajectory step as a `TaskStatusUpdateEvent` tagged via `message.metadata.type`:
    `"thought"`, `"action"` (deduped against the previous action to avoid repeats), `"response"`,
    `"system"`, `"observation"`, and a special `"audio_message"` type when the `text_to_speech`
    tool's observation contains base64 audio data (so the client can auto-play it).
  - Also emits an A2UI bridge: a `response` containing `__a2ui_tree__=...` / `__a2ui_data__=...`
    markers has those markers stripped from the visible text and re-attached as
    `metadata.a2ui_tree` / `metadata.a2ui_data` JSON.
  - A loop that ends without prose returns the structured `missing_final_response` error. Tool
    observations are never promoted to final user responses.
  - Every event is also broadcast to `AgentEventBus` (feeds the `/ws/agents` Monitor WebSocket).
  - On exception, emits a `Failed` status event with `"Error: ${e.message}"`.
- `installRoutes(route, agent, database, baseUrl, skillLoader, llmAdapter)` builds the agent card
  (synchronously, via `runBlocking`), constructs the executor, an `AutoCreateTaskStorage` (a
  `TaskStorage` that lazily creates a `Task` on the first `TaskStatusUpdateEvent` instead of
  throwing — needed because the SDK's `message/sendSubscribe` flow doesn't pre-create tasks), and
  mounts a Koog `HttpJSONRPCServerTransport` at **`/a2a`** relative to the given `route`. In
  `OmnichannelGateway`, `installRoutes` is called inside `route("agents") { ... }`, so the actual
  JSON-RPC endpoint is **`POST /agents/a2a`**.
  - The class-level doc explains why routes are mounted manually rather than via Koog's
    transport helper directly: `HttpJSONRPCServerTransport` has an SSE `DuplicatePluginException`
    bug under Ktor 3.x.

Two standard discovery endpoints are mounted separately, directly in
`OmnichannelGateway.kt` (`route("/") { route(".well-known") { ... } }`):

- `GET /.well-known/agent.json` — Google A2A discovery; returns `PrometheA2A.buildAgentCard(...)`.
- `GET /.well-known/acp.json` — ACP discovery; returns `AcpServer(baseUrl).buildAgentCard()`.

### AcpRoutes

`gateway/src/jvmMain/kotlin/dev/promethe/gateway/AcpRoutes.kt`

Defines `Route.acpRoutes(a2aClient, baseUrl)`, mounted from `OmnichannelGateway` inside
`route("api") { ... }`, so its effective paths are:

| Method | Path (as mounted) | Behavior |
|---|---|---|
| `GET` | `/.well-known/acp.json` | Returns `AcpServer(baseUrl).buildAgentCard()` as JSON. |
| `POST` | `/acp/invoke` | Accepts an `AcpRequest` (`capabilityId`, `input`, `parameters`, `contextId`, `stream`, `metadata`), builds a session id `"acp-${contextId ?: currentTimeMillis()}"`, composes a prompt (prefixing non-`"chat"` capabilities with `[ACP capability: ...]` and any parameters), and executes it through the secured A2A-aligned agent path with `origin = ACP`. Responds with an `AcpResponse` — `status = FAILED` (HTTP 500) if the response text starts with `"Error:"`, otherwise `COMPLETED` (HTTP 200). |
| `GET` | `/acp/health` | Returns `{"status": "ok", "protocol": "ACP/1.0", "capabilities": "<count>"}`. |

The ACP models (`AcpAgentCard`, `AcpCapability`, `AcpParameter`, `AcpAuth`, `AcpRequest`,
`AcpResponse`, `AcpStatus`, `AcpRegistryEntry`) live in
`shared/src/commonMain/kotlin/dev/promethe/core/acp/AcpModels.kt`, and `AcpServer`
(`shared/src/commonMain/kotlin/dev/promethe/core/acp/AcpServer.kt`) builds the card: two fixed
capabilities (`chat`, `execute`) plus one `AcpCapability` per tool in `ToolRegistry.toolsSnapshot()`
(id `"tool-<name>"`).

### A2AInternalClient

`gateway/src/jvmMain/kotlin/dev/promethe/gateway/A2AInternalClient.kt`

Not a network client — an **internal execution bridge** used by every entry point that isn't the
A2A JSON-RPC server itself (ACP invoke, webhooks, goals/autonomous-mode, the task scheduler). Its
class doc explicitly states it mirrors `PrometheA2A.createExecutor`'s logic "without the transport
overhead" and is "NOT the full A2A JSON-RPC protocol."

`execute(sessionId, text, channelHint = "internal")`:
1. Returns `"No response"` immediately if `text` is blank.
2. Builds an `AgentExecutionRequest` with the protocol origin.
3. Delegates in-process to `AgentExecutionService`; there is no loopback HTTP call.
4. Returns the typed final response; on exception, returns
   `"Error: ${e.message}"` (this is the string `AcpRoutes` checks with `startsWith("Error:")` to
   decide `AcpStatus.FAILED` vs `COMPLETED`).

## Desktop client

### A2AChatClient

`composeApp/src/commonMain/kotlin/dev/promethe/app/network/A2AChatClient.kt`

The Compose UI's chat transport, built on the official Koog `A2AClient` rather than hand-rolled
SSE/JSON-RPC parsing, so it works uniformly across JVM, WasmJs, iOS, and Android targets.

- Constructed with `baseUrl` and an optional `apiKey` (added as a `Bearer` `Authorization` header
  via `defaultRequest` when non-blank). The underlying `HttpClient` sets a 1-hour request/socket
  timeout to accommodate long-running agent turns.
- `transport = HttpJSONRPCClientTransport("$baseUrl/agents/a2a", httpClient)` — confirms the
  client talks to the gateway's `/agents/a2a` mount point described above.
- Supplies a minimal hardcoded `AgentCard` via `ExplicitAgentCardResolver` so the client doesn't
  need a round-trip to `/.well-known/agent.json` before it can connect.
- `ensureConnected()` calls `client.connect()` exactly once (guarded by a `Mutex` + `connected`
  flag).
- `sendMessageStreaming(sessionId, text, profileId, profileProvider, profileModel)` — builds a
  `Message` with `contextId = sessionId` and, if any profile fields are set, a `metadata` JSON
  object (`profileId`, `provider`, `model`) that `PrometheA2AExecutor` reads back out server-side
  to select the right persona/provider/model. Returns a `Flow<ChatEvent>` by collecting
  `client.sendMessageStreaming(request)` and mapping each Koog `Event` to a Promethe `ChatEvent`:
  - `Message` → type comes from `metadata.type` (defaults to `"response"` if absent).
  - `TaskStatusUpdateEvent` → same `metadata.type` extraction; terminal events (`Completed`/
    `Failed`) with no message text are swallowed (return `null`, not emitted); events with
    `type == "system"` are filtered out client-side too (defense in depth alongside the
    server-side filtering in `PrometheA2AExecutor`).
  - `TaskArtifactUpdateEvent` → emitted as a `"response"` `ChatEvent` if it has non-blank text.
  - `Task` → lifecycle-only, never emitted to the UI.
  - On any streaming exception, emits a `ChatEvent(type = "error", ...)` followed by a final
    `ChatEvent(type = "done")` (always emitted, success or failure).
- `close()` closes the underlying `HttpClient`.

## Configuration

There is no dedicated `A2A_*` environment variable surface documented in the files reviewed here.
The A2A/ACP endpoints are mounted unconditionally as part of gateway startup
(`OmnichannelGateway.start()`); the only inputs that vary are the gateway's own `port` /
`apiKey` (used for the `Authorization: Bearer` check applied globally by `AuthMiddleware`, not
specific to A2A/ACP) and, for the desktop client, the `baseUrl` / `apiKey` passed into
`A2AChatClient`'s constructor.

## Limitations

- `AgentA2ARegistry.sendTaskStreaming` only supports **local** agents — there is no streaming path
  for remote agents; `sendTask` (blocking) is the only way to call a remote peer today.
- `A2AClientTool.sendTask` extracts only the **first** text part of the JSON-RPC result's status
  message; multi-part or artifact-based responses from a remote agent are not specially handled.
- Local registry agents, profile agents and the gateway's `/agents/a2a` server share
  `AgentExecutionService`; only their protocol event adapters differ.
- The gateway serves the ACP agent card from `/.well-known/acp.json`; `acpRoutes` is the single
  owner of that discovery contract.
- `A2AInternalClient` is explicitly documented as not implementing full A2A JSON-RPC semantics; it
  exists purely so non-A2A entry points (ACP, webhooks, goals, scheduler) get the same session/
  event-bus behavior as the real A2A executor without paying for the transport layer.
- Remote agent discovery (`A2AClientTool.discover`) and the registry's `registerRemote` are not
  wired to any automatic peer-discovery mechanism in the files reviewed — registering a remote
  agent appears to require an explicit call with a known URL and card.
