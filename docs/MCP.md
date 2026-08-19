# MCP Integration

> How Prométhé connects to external MCP servers (client role) and how it exposes its own tools
> as an MCP server (server role).

## Overview

Prométhé has two independent MCP roles:

1. **MCP client** — `McpBridge` connects to zero or more external MCP servers configured through
   `MCP_SERVERS` or encrypted SQLite configuration, discovers their tools, and registers them as regular
   Prométhé tools (`McpProxyTool`) so the agent's LLM can call them like any built-in tool.
2. **MCP server** — the gateway exposes Prométhé's own `ToolRegistry` as an MCP server, reachable
   either over HTTP (Streamable HTTP transport, mounted on the running gateway) or over stdio
   (`--mcp-stdio` process mode, for IDE integrations such as VS Code/Cursor).

These two roles are independent: the client role lets the Prométhé agent use *other* tools, the
server role lets *other* MCP clients use Prométhé's tools.

---

## Architecture

### Client role (Prométhé → external MCP servers)

| Component | File | Role |
|---|---|---|
| `McpBridge` | `shared/src/commonMain/kotlin/dev/promethe/core/McpBridge.kt` | Platform-agnostic core: holds server configs/state, connects servers, discovers tools, registers `McpProxyTool` instances into `ToolRegistry`, and routes tool-call execution back to the right transport. |
| `McpProxyTool` | `shared/src/commonMain/kotlin/dev/promethe/core/McpProxyTool.kt` | A Koog `SimpleTool` that wraps a single remote MCP tool. Parses its JSON string argument, then calls `McpBridge.executeToolCall(serverId, toolName, arguments)`. |
| `McpConfigLoader` | `shared/src/jvmMain/kotlin/dev/promethe/core/mcp/McpConfigLoader.kt` | JVM-only: loads server configs from multiple sources (see below) and can persist them back to a JSON file. |
| `JvmMcpTransportFactory` | `shared/src/jvmMain/kotlin/dev/promethe/core/JvmMcpTransportFactory.kt` | Implements `McpBridge.TransportFactory`; picks a concrete transport (`stdio`, `sse`, `streamable-http`) based on `McpServerConfig.transport` and wraps it in an adapter implementing `McpBridge.McpTransportApi`. |
| `McpStdioTransport` | `shared/src/jvmMain/kotlin/dev/promethe/core/McpStdioTransport.kt` | Fails closed with `SANDBOX_PROTOCOL_V2_REQUIRED`; persistent subprocess sessions are not supported by sandbox IPC v1. |
| `McpSseTransport` | `shared/src/jvmMain/kotlin/dev/promethe/core/McpSseTransport.kt` | Connects to an SSE endpoint to discover a message endpoint, then sends JSON-RPC 2.0 requests via HTTP POST to that endpoint. |
| `McpStreamableHttpTransport` | `shared/src/jvmMain/kotlin/dev/promethe/core/McpStreamableHttpTransport.kt` | Dual-era Streamable HTTP client. It probes `server/discover` with the stateless 2026-07-28 envelope, drives bounded MRTR retries, polls negotiated durable Tasks, and falls back to the official 2025-11-25 `initialize` lifecycle only when the response identifies a legacy endpoint. |
| `McpInputRequestHandler` | `shared/src/jvmMain/kotlin/dev/promethe/core/McpInputRequestHandler.kt` | Explicit trust boundary for MRTR. It resolves embedded server requests; without a configured handler, non-empty `inputRequests` fail closed with `McpInputRequiredException`. |

`McpBridge` itself is declared in `commonMain` and only depends on the `McpTransportApi` /
`TransportFactory` interfaces — the actual transports (`McpStdioTransport`, `McpSseTransport`,
`McpStreamableHttpTransport`) and `JvmMcpTransportFactory` live in `jvmMain`.
The HTTP transports use Ktor's CIO engine. Client-side stdio remains disabled
until the native sandbox supports managed persistent stdin/stdout sessions.

### Server role (external MCP clients → Prométhé)

| Component | File | Role |
|---|---|---|
| `mcpServerRoutes()` | `gateway/src/jvmMain/kotlin/dev/promethe/gateway/mcp/McpServerEndpoint.kt` | Ktor routing extension mounted at the gateway root. Implements the discovery card, the JSON-RPC endpoint, and a debug REST listing. |
| `McpToolExporter` | `gateway/src/jvmMain/kotlin/dev/promethe/gateway/mcp/McpToolExporter.kt` | Shared JSON-RPC 2.0 dispatcher: converts `ToolRegistry` tools into MCP `tools/list` / `tools/call` responses. Used by both the HTTP endpoint and the stdio mode. |
| `McpStdioServerMode` | `gateway/src/jvmMain/kotlin/dev/promethe/gateway/mcp/McpStdioServerMode.kt` | Runs Prométhé as an MCP server over stdio: reads one JSON-RPC message per line from stdin, dispatches through `McpToolExporter`, writes the response to stdout. |
| `McpTaskManager` | `gateway/src/jvmMain/kotlin/dev/promethe/gateway/mcp/McpTaskManager.kt` | Durable, session-bound implementation of the 2026 `io.modelcontextprotocol/tasks` extension. It persists task state before execution, supports get/update/cancel, enforces TTL, and cancels the active coroutine when possible. |
| `mcpManagementRoutes()` | `gateway/src/jvmMain/kotlin/dev/promethe/gateway/McpManagementRoutes.kt` | REST management API (list/register/connect/disconnect servers, list tools) used by the client role — mounted under `/api`. |

### Bootstrap wiring

`shared/src/jvmMain/kotlin/dev/promethe/core/AgentBootstrap.kt` wires the client role at startup:

```kotlin
val mcpBridge = McpBridge()
mcpBridge.setTransportFactory(JvmMcpTransportFactory())

val mcpConfigs = McpConfigLoader.loadConfigs()
if (mcpConfigs.isNotEmpty()) {
    mcpConfigs.forEach { cfg -> mcpBridge.registerServer(cfg) }
    val results = mcpBridge.connectAll()
    // logs per-server connect success/failure and total tool count
}
```

The server role is wired in `gateway/src/jvmMain/kotlin/dev/promethe/gateway/OmnichannelGateway.kt`,
which mounts `mcpServerRoutes(CORS_ALLOWED_ORIGINS)` at the routing root (alongside `.well-known/` discovery routes and
the OpenAI-compatible API) and `mcpManagementRoutes(mcpBridge, database, masterCipher)` inside the `/api` route block. The
stdio server mode is selected in `gateway/src/jvmMain/kotlin/dev/promethe/gateway/Main.kt`: if the
process is launched with `--mcp-stdio`, it builds a `McpToolExporter` + `McpStdioServerMode` and
runs the stdio loop instead of starting the normal HTTP gateway.

---

## Configuration sources (client role)

`McpConfigLoader.loadConfigs()` merges server configs from two active sources, in priority order — once a
server `id` has been seen from a higher-priority source, later sources cannot override it:

1. **Environment variable `MCP_SERVERS`** — a JSON array of `McpBridge.McpServerConfig` objects.
   These entries are process-owned and cannot be changed through the management API.
2. **Encrypted SQLite configuration** — the source of truth for servers created through the UI/API.
   Public metadata is stored separately from an AES-256-GCM encrypted `env`/`headers` payload and requires
   `PROMETHE_MASTER_KEY` to load.

Legacy `./mcp.json` and `~/.promethe/mcp.json` files are imported once into encrypted SQLite when a master
key is configured, then ignored. Their canonical map format is still accepted for that one-time import:

```json
{
  "mcpServers": {
    "filesystem": {
      "transport": "stdio",
      "command": "npx @mcp/filesystem",
      "env": {},
      "enabled": true
    }
  }
}
```

### `McpServerConfig` fields

| Field | Type | Meaning |
|---|---|---|
| `id` | `String` | Unique identifier for the server (used to derive the qualified tool name and as the map key in `mcp.json`). |
| `name` | `String` | Human-readable name (shown in tool descriptions as `[MCP:{name}]`). |
| `transport` | `String` | One of `"stdio"`, `"sse"`, `"streamable-http"`. |
| `command` | `String` | Command to launch the local server process (`stdio` only). |
| `url` | `String` | Target URL (`sse` / `streamable-http` only). |
| `env` | `Map<String, String>` | Environment variables passed to the subprocess (`stdio` only); encrypted at rest for UI/API-managed servers. |
| `headers` | `Map<String, String>` | Custom HTTP headers (`sse` / `streamable-http` only); encrypted at rest for UI/API-managed servers. |
| `enabled` | `Boolean` | If `false`, `connectServer()` fails fast with `IllegalStateException`. |

When loaded from `mcp.json`, `id` and `name` are both set to the map key (e.g. the `"filesystem"`
key in the example above becomes both `id` and `name`).

---

## Transports (client role)

| Transport | Class | Protocol version sent | Notes |
|---|---|---|---|
| `stdio` | `McpStdioTransport` | N/A | Unavailable in sandbox IPC v1. Connection fails with `SANDBOX_PROTOCOL_V2_REQUIRED` and no subprocess is started. |
| `sse` | `McpSseTransport` | `2025-11-25` | Deprecated compatibility transport. `GET baseUrl` with `Accept: text/event-stream` discovers a session endpoint; subsequent JSON-RPC calls use the legacy `initialize` lifecycle. It neither advertises nor handles modern MRTR/elicitation. New configurations should use `streamable-http`. |
| `streamable-http` | `McpStreamableHttpTransport` | `2026-07-28`, fallback `2025-11-25` | Starts with `server/discover`. Modern requests carry protocol version, client identity and capabilities in `_meta`, plus `MCP-Protocol-Version`, `Mcp-Method` and, where applicable, `Mcp-Name` headers. It never sends `Mcp-Session-Id`. A legacy handshake is used only after a deterministic compatibility probe. |

All transports implement the common adapter surface. The stdio adapter returns
the fail-closed error above; SSE and Streamable HTTP implement
`initialize()`, `listTools()`, `callTool(name, arguments)`, and `close()`. For Streamable HTTP,
the internal `initialize()` adapter method performs modern discovery rather than a wire-level
`initialize` call; the latter is reserved for the legacy fallback.

`callTool` response parsing: all three transports read the JSON-RPC `result.content` array and, for
each entry, render `type: "text"` as its `text` field and `type: "image"` as `"[image: {mimeType}]"`
(`McpStreamableHttpTransport.callTool` only reads the *first* content entry's `text`, unlike the
other two transports which join all entries with `\n`).

---

## Server lifecycle (client role: connect/disconnect)

`McpBridge` tracks each server as a `ServerState(config, status, tools, error)` with
`status: ServerStatus` in `{DISCONNECTED, CONNECTING, CONNECTED, ERROR}`.

- **`registerServer(config)`** — stores the config with status `DISCONNECTED`. Does not connect.
- **`connectServer(serverId)`**:
  1. Rejects unknown server IDs and disabled servers (`enabled = false`).
  2. Sets status to `CONNECTING`.
  3. Calls `discoverTools(config)`, which asks the registered `TransportFactory` for a transport,
     calls the transport's lifecycle adapter then `listTools()`, and stores the transport in an internal map keyed by
     server ID.
  4. For each discovered tool, wraps it in an `McpProxyTool` named `mcp_{serverId}_{toolName}` with
     description `[MCP:{serverName}] {originalDescription}`, and registers it into `ToolRegistry`.
  5. On success, status becomes `CONNECTED` with the qualified tool list; on any exception, status
     becomes `ERROR` with the exception message recorded.
- **`connectAll()`** — connects every registered server ID and returns a
  `Map<String, Result<List<McpToolInfo>>>`.
- **`disconnectServer(serverId)`** — unregisters all of that server's tools from `ToolRegistry` and the
  internal tool map, calls `close()` on its transport, removes the transport, and resets status to
  `DISCONNECTED` with an empty tool list.
- **`executeToolCall(serverId, toolName, arguments)`** — used internally by `McpProxyTool`; throws
  if the server is unknown or not `CONNECTED`, otherwise forwards to the transport's `callTool`.

All mutable state (`servers`, `mcpTools`, `transports`) is guarded by a single `Mutex`.

---

## Tool discovery & registration

Discovered tools are represented as `McpBridge.McpToolInfo(serverId, toolName, description,
inputSchema)`. On connect, each raw tool name from the remote server is prefixed to form a globally
unique Prométhé tool name: `mcp_{serverId}_{toolName}`. This qualified name is what appears in
`ToolRegistry` and in the agent's tool list — the original (unqualified) name is kept internally on
the `McpProxyTool` instance as `originalToolName`, and is what actually gets sent to the remote
server in `tools/call`.

When the LLM invokes an `McpProxyTool`, it passes a single `arguments` string (JSON-encoded); the
tool parses it (falling back to an empty JSON object on parse failure) and calls
`McpBridge.executeToolCall`.

---

## HTTP management API (client role)

Mounted under `/api` by `mcpManagementRoutes(mcpBridge)` in
`gateway/src/jvmMain/kotlin/dev/promethe/gateway/McpManagementRoutes.kt`:

| Method | Route | Description |
|---|---|---|
| `GET` | `/api/v1/mcp/servers` | Lists registered servers. Every `env` and `headers` value is redacted as `***`; environment-managed servers are marked immutable. |
| `POST` | `/api/v1/mcp/servers` | Persists and registers a new UI-managed `McpServerConfig`. Secret maps are encrypted. Returns `200 OK`. |
| `PUT` | `/api/v1/mcp/servers/{id}` | Replaces a persisted UI-managed config after disconnecting and unregistering its previous tools. |
| `DELETE` | `/api/v1/mcp/servers/{id}` | Removes the persisted config, transport, and all registered tools owned by that server. |
| `POST` | `/api/v1/mcp/servers/{id}/connect` | Connects the given server. Returns `McpConnectResponse(status, tools)` on success, or `500` with an `ErrorResponse` on failure. |
| `POST` | `/api/v1/mcp/servers/{id}/disconnect` | Disconnects the given server. |
| `GET` | `/api/v1/mcp/tools` | Lists every tool available to the agent — both MCP-sourced tools (`source: "MCP"`) and built-in tools from `ToolRegistry.toolsSnapshot()` (`source: "Built-in"`) — with a combined `count`. |

## HTTP / stdio MCP server (server role)

Mounted at the routing root by `mcpServerRoutes()` in
`gateway/src/jvmMain/kotlin/dev/promethe/gateway/mcp/McpServerEndpoint.kt`, implementing the
modern **2026-07-28** stateless requests and legacy **2025-11-25** requests on the same Streamable
HTTP endpoint:

| Method | Route | Description |
|---|---|---|
| `GET` | `/.well-known/mcp` | Server Card: preferred version `2026-07-28`, `supportedVersions` (`2026-07-28`, `2025-11-25`), tool capability, durable Tasks extension when configured, and endpoint `/mcp`. |
| `POST` | `/mcp` | Full JSON-RPC 2.0 endpoint. Browser `Origin` values must be allow-listed. Modern requests must carry matching `_meta` and `MCP-Protocol-Version`, a body-matching `Mcp-Method`, and a body-matching `Mcp-Name` for named calls. Header mismatches return JSON-RPC `-32020`; unsupported versions return `-32022` with the supported list. Batch arrays are rejected with `400`. |
| `GET` | `/mcp/tools` | Simple REST listing of all `ToolRegistry` tools (`name`, `description`) for debugging/UI — separate from the JSON-RPC `tools/list` method. |

`McpToolExporter.dispatch()` handles these JSON-RPC methods over both the HTTP endpoint and stdio
mode:

| Method | Behavior |
|---|---|
| `server/discover` | Modern only. Returns supported versions, tool capabilities, the Tasks extension when durable storage is active, server identity in result `_meta`, private cache hints, and `resultType: complete`. |
| `initialize` | Legacy only. Returns `2025-11-25`, tool capabilities and server info. Modern requests receive `-32601`. |
| `tools/list` | Enumerates policy-visible tools in deterministic name order. Modern results include `resultType`, `ttlMs`, `cacheScope`, server identity and explicit draft 2020-12 input schemas. |
| `tools/call` | Sends a typed `ToolInvocation` to `SecureToolExecutor`, preserving MCP origin, owner session, exact arguments, risk classification, and mandatory approval. It returns `resultType: task` for an eligible long-running tool only when the client advertises `io.modelcontextprotocol/tasks`; otherwise it remains synchronous. |
| `tasks/get` | Modern only. Returns the session-owned durable state and final result/error. `Mcp-Name` must equal `taskId`. |
| `tasks/update` | Modern only. Supplies responses requested by an `input_required` task. The capability must be repeated in request metadata. |
| `tasks/cancel` | Modern only. Persists cancellation and cancels the active coroutine when it still belongs to this process. Repeated cancellation is idempotent. |
| `shutdown` / `notifications/initialized` | Legacy lifecycle only. Modern requests do not use the initialize/shutdown exchange. |
| anything else | JSON-RPC error `-32601` (method not found). |

### Multi Round-Trip Requests (MRTR)

The modern Streamable HTTP client recognizes `resultType: input_required` for `tools/call`,
`prompts/get`, and `resources/read`. It resolves every entry through the handler registered for
its exact method (`elicitation/create`, `sampling/createMessage`, or `roots/list`), then retries
the original request with a fresh JSON-RPC ID,
per-round `inputResponses`, and the byte-for-byte `requestState` value supplied by the server.
Advertised client capabilities are derived from this registry. Retries are limited to ten rounds
and sixteen embedded requests per round by default.

No implicit user answer is generated. A non-empty `inputRequests` map without an input handler
raises `McpInputRequiredException`; unknown request methods, malformed envelopes, mismatched
JSON-RPC IDs, and unsupported result types are rejected. The default `JvmMcpTransportFactory`
registers only `elicitation/create` and advertises only that capability. Form requests are bound to
the secured tool invocation, displayed to the owner in the Monitor, validated against their schema,
and resumed with `accept`, `decline`, or `cancel`. Unsupported modes, sensitive fields, oversized
schemas, unsupported schema keywords, and requests outside a secured invocation fail closed. Forms
are limited to 64 KiB, sixteen flat primitive fields, and bounded labels/descriptions. The Monitor
also warns the owner never to enter credentials or other secrets. A request times out as `cancel`.
Pending forms are intentionally held in memory because their originating tool coroutine cannot
survive a process restart. Promethe does not advertise `sampling/createMessage` or `roots/list`.

Errors are mapped to standard JSON-RPC codes: `-32601` (method not found), `-32602` (invalid
params, e.g. missing `name`/`arguments`), `-32603` (uncaught internal error), `-32700` (parse
error), `-32003` (missing required Tasks capability), `-32020` (header/body mismatch), and
`-32022` (unsupported protocol version).

### Durable Tasks

Prométhé advertises `io.modelcontextprotocol/tasks` per modern request and accepts task results only
for `tools/call`. Server-side tasks are stored in SQLite before their coroutine starts and are bound
to the authenticated owner session. The persisted statuses are `working`, `input_required`,
`completed`, `failed`, and `cancelled`; timestamps are ISO-8601 and TTL/polling hints are included.
An active task left by a previous process is marked failed on startup rather than replayed blindly.

The Streamable HTTP client polls `tasks/get` with bounded intervals, fulfills supported input requests
through the same explicit handlers used by MRTR, sends `tasks/update`, and returns the original tool
result after completion. Local cancellation and exhausted polling trigger a best-effort
`tasks/cancel`. HTTP and stdio server modes share this implementation.

For stdio mode (`McpStdioServerMode`, activated via `promethe/gateway`'s `--mcp-stdio` CLI flag —
see `gateway/src/jvmMain/kotlin/dev/promethe/gateway/Main.kt`), the same `McpToolExporter` is reused:
the loop reads one JSON-RPC message per line from stdin, dispatches it, writes the response (if
any) to stdout, and exits cleanly on EOF or on receiving a `shutdown` method (detected via a raw
string check on the line before/independent of full dispatch).

---

## Limitations

- **No automatic reconnect/retry** — `connectServer`/`connectAll` are one-shot; there is no
  health check or reconnection loop if a subprocess dies or an HTTP server becomes unreachable
  after the initial connect.
- **SSE endpoint discovery is best-effort** — `McpSseTransport.initialize()` falls back to treating
  `baseUrl` as the message endpoint if it cannot parse a `data:` line from the initial SSE response.
- **MCP HTTP+SSE is legacy-only and deprecated** — it remains available during the compatibility
  window but receives no new protocol features.
- **MRTR is client-side and opt-in at the trust boundary** — the Streamable HTTP client can drive
  bounded modern retries and owner-facing form elicitation. URL-mode elicitation and richer schema
  renderers are not supported, and Prométhé's server role does not emit `input_required` results yet.
- **Tasks use polling, not subscriptions** — durable create/get/update/cancel is implemented, but
  notification subscriptions and resumable push delivery remain future work. The server currently
  opts in a conservative set of long-running tools rather than allowing every tool to become a task.
- **Client task IDs are not persisted across process restarts yet** — the server state is durable,
  but a Prométhé client process currently resumes polling only while its transport instance remains alive.
- **Tool input schemas are generated canonically from Koog descriptors** — MCP and voice share the
  same deterministic 2020-12 source for nested objects, arrays, enums, `null`, `anyOf`, required
  properties and typed `additionalProperties`. Constructs absent from Koog descriptors, including
  `$defs`, `$ref`, `oneOf` and typed output schemas, still require a future contract migration.
- **`McpStreamableHttpTransport.callTool` only reads the first `content` entry**, unlike
  `McpStdioTransport`/`McpSseTransport`, which join all entries with `\n` — a server returning
  multiple content blocks (e.g. text + image) will only surface the first one over this transport.
- **Batch JSON-RPC requests are rejected** (`400 Bad Request`) — only single
  JSON-RPC objects are accepted on `POST /mcp` and over stdio.
