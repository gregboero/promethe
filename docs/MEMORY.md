# Memory System (4 tiers)

## Overview

Prométhé persists facts about the user beyond a single conversation via a
4-tier memory system, inspired by the **TencentDB Agent Memory** architecture:

```
L0 — Conversation   : raw messages (`messages` table in DB, already handled elsewhere)
L1 — Atomic (Facts) : extracted facts (preferences, decisions, project context)
L2 — Scenario       : scene blocks aggregated from several related memories
L3 — Persona        : high-level user profile, long-term preferences
```

This hierarchy is defined by the `MemoryTier` enum (`CONVERSATION`, `ATOMIC`, `SCENARIO`,
`PERSONA`) in
`promethe/shared/src/commonMain/kotlin/dev/promethe/core/memory/MemoryProvider.kt`. In practice,
almost the entire current system operates at the **L1 (ATOMIC)** tier: this is the tier assigned by
default to every `MemoryFact` created by the agent tools and by automatic extraction. The
`SCENARIO` tier is only produced by `HonchoMemoryProvider` (see below), and `PERSONA` is
currently never assigned by the code — the L2/L3 tiers exist in the data model
but are not yet populated by any dedicated logic.

> Not to be confused with `profiles/developer/MEMORY.md` — a Markdown file hot-reloaded by
> `ContextFileLoader` that serves as *static* memory injected as a fallback into the system prompt
> (see `ProfileManager.readFile(config, "MEMORY.md")`). This document covers the *dynamic*
> memory system, driven by database / external provider.

---

## Architecture

| Component | File | Role |
|---|---|---|
| `MemoryLayer` | `shared/src/commonMain/kotlin/dev/promethe/core/MemoryLayer.kt` | Main facade: fact extraction via LLM, recall, storage, building the memory context for the system prompt |
| `MemoryProvider` (interface) | `shared/src/commonMain/kotlin/dev/promethe/core/memory/MemoryProvider.kt` | Common contract for all backends (`storeFact`, `recallFacts`, `getAllFacts`, `deleteFact`, `updateFact`, `isAvailable`) |
| `MemoryFact` (model) | same file as above | Structure of a fact: `id`, `userId`, `category`, `content`, `confidence`, `sourceSession`, `tier`, `createdAt`, `updatedAt` |
| `EmbeddedMemoryProvider` | `shared/src/jvmMain/kotlin/dev/promethe/core/memory/EmbeddedMemoryProvider.kt` | Default provider — local SQLite via `PrometheDatabaseApi` (`user_facts` table) |
| `HonchoMemoryProvider` | `shared/src/commonMain/kotlin/dev/promethe/core/memory/HonchoMemoryProvider.kt` | External provider based on [Honcho](https://github.com/plastic-labs/honcho) (Plastic Labs) |
| `TencentMemoryProvider` | `shared/src/commonMain/kotlin/dev/promethe/core/memory/TencentMemoryProvider.kt` | External provider based on the [TencentDB Agent Memory](https://github.com/Tencent/TencentDB-Agent-Memory) REST API |
| `FallbackMemoryProvider` | `shared/src/commonMain/kotlin/dev/promethe/core/memory/FallbackMemoryProvider.kt` | Decorator: delegates to a primary provider (Honcho/Tencent) with transparent fallback to `EmbeddedMemoryProvider` |
| `MemoryNudge` | `shared/src/commonMain/kotlin/dev/promethe/core/memory/MemoryNudge.kt` | Periodically injects a system instruction nudging the agent to call `memory_save` |
| Agent tools | `shared/src/commonMain/kotlin/dev/promethe/core/tools/builtin/MemoryTools.kt` | `MemorySaveTool`, `MemorySearchTool`, `MemoryForgetTool`, `MemoryListTool` |
| HTTP routes | `gateway/src/jvmMain/kotlin/dev/promethe/gateway/MemoryRoutes.kt` | `/memory/*` REST API |

---

## Memory tiers explained

### L0 — Conversation

Raw session messages are stored in the `messages` table of the SQLite database
(`PrometheDatabaseApi`), outside the `MemoryProvider` system. This is the raw material used
by `MemoryLayer.extractFacts()` to produce L1 facts.

### L1 — Atomic (Facts)

Main operational tier. A `MemoryFact` captures an atomic piece of information with a
`category` among `"preference"`, `"project"`, `"personal"`, `"technical"` (or `"general"` by
default on the tools side), a `confidence` score (0.0–1.0), and the source session (`sourceSession`).

Two feeding paths:

1. **Automatic post-loop extraction** — `MemoryLayer.extractFacts(sessionId)` is called
   after each complete run of the PRA loop in `AIAgent.kt` (if `!dryRun` and
   `memoryLayer != null`). It only runs if the session has at least 4 messages. The
   extraction prompt asks the LLM to return a JSON array `{category, fact}`; the response
   is parsed between the first `[` and the last `]` of the returned text, then each fact is
   persisted via `storeFact`. On parsing or LLM call failure, extraction silently returns an
   empty list (warning logged).
2. **Explicit save by the agent** — the `memory_save` tool (see below), triggered either
   by the agent's autonomous decision, or via the periodic "nudge" (`MemoryNudge`).

`EmbeddedMemoryProvider.storeFact()` does an **upsert**: if a fact with the same `category` and same
`content` (case-insensitive) already exists for the user, its `confidence` is increased
by `+0.1` (capped at `1.0`) and its `updatedAt` refreshed, instead of creating a duplicate.

### L2 — Scenario

Represents a context block aggregated from several related memories. In the current code, this
tier is only produced by `HonchoMemoryProvider.recallFacts()`: Honcho does not return
individual facts but a single block of context text (`fetchContext`), which is wrapped in a
synthetic `MemoryFact` (`id = "honcho-context"`, `tier = SCENARIO`). No L2 aggregation is
implemented for `EmbeddedMemoryProvider` or `TencentMemoryProvider`.

### L3 — Persona

High-level, long-term user profile. Defined in the model (`MemoryTier.PERSONA`) but
**no code in the repository currently assigns this tier** to a `MemoryFact` — not the providers,
not the tools, not the automatic extraction. This tier exists in the data schema for a
future evolution (see Limitations).

---

## Providers

The `MemoryProvider` contract (interface) exposes:

```kotlin
suspend fun storeFact(fact: MemoryFact)
suspend fun recallFacts(query: String, limit: Int = 10): List<MemoryFact>
suspend fun getAllFacts(userId: String = "default"): List<MemoryFact>
suspend fun deleteFact(id: String)
suspend fun updateFact(id: String, category: String? = null, content: String? = null, confidence: Float? = null): Boolean
suspend fun isAvailable(): Boolean
val name: String
```

`updateFact` has a default implementation in the interface: delete + re-store the
existing fact with the provided fields merged in — providers don't need to override it.

### `EmbeddedMemoryProvider` (default)

- Zero external dependency. Stores in SQLite via `PrometheDatabaseApi` (`user_facts` table,
  search via FTS5 index introduced in migration V2).
- `recallFacts()` delegates to `database.searchUserFacts(query)`, sorts by `confidence` descending,
  then truncates to `limit`.
- `isAvailable()` always returns `true`.
- `name = "embedded-sqlite"`.

### `HonchoMemoryProvider`

- Wraps a `HonchoClient`. `storeFact()` accumulates facts in a local list
  (`pendingFacts`) and resynchronizes the entire text profile to Honcho on every call
  (`honchoClient.syncProfile`) — there is no Honcho API to list all facts, so
  `getAllFacts()` only returns the facts accumulated locally since the process
  started (not persisted on the Prométhé side).
- `recallFacts()` calls `fetchContext()` and wraps the resulting text in a single
  `MemoryFact` of tier `SCENARIO` (category `"context"`), or an empty list if Honcho returns
  empty text.
- `isAvailable()` delegates to `honchoClient.isAvailable()`.
- `name = "honcho"`.

### `TencentMemoryProvider`

- Pure REST client to a TencentDB Agent Memory server (self-hosted or cloud), endpoints
  `POST /api/v1/memories`, `POST /api/v1/memories/query`, `GET /api/v1/memories`,
  `DELETE /api/v1/memories/{id}`, `GET /health`.
- Optional authentication: `Authorization: Bearer {apiKey}` header and/or
  `x-tdai-service-id: {serviceId}` if these values are non-empty.
- Any network exception or non-`2xx` HTTP status is absorbed: the methods return an
  empty list (`recallFacts`, `getAllFacts`) rather than propagating the error; `storeFact` /
  `deleteFact` just log a `println` message on failure.
- `isAvailable()` does a `GET {baseUrl}/health` and checks for a `200` status.
- `name = "tencent-agent-memory"`.

### `FallbackMemoryProvider`

Decorator combining a `primary` (Honcho or Tencent) and a `fallback` (always
`EmbeddedMemoryProvider` in the current wiring):

- `storeFact`: **always** writes to the fallback first (durability guaranteed), then
  attempts a best-effort write to the primary if `primary.isAvailable()`.
- `recallFacts`: queries the primary if available; if the results are empty or if the
  primary fails/is unavailable, falls back to the fallback.
- `getAllFacts` / `deleteFact`: same primary → fallback priority logic with exception
  capture.
- `isAvailable()`: `true` if primary OR fallback is available.
- `name` is composed dynamically: `"{primary.name}→{fallback.name}"` (e.g.
  `"honcho→embedded-sqlite"`).

---

## Configuration

Provider selection happens in
`shared/src/jvmMain/kotlin/dev/promethe/core/AgentBootstrap.kt` (`AgentBootstrap.create`), on the
`resolvedConfig.memoryProvider` field:

| Value | Behavior |
|---|---|
| `"honcho"` | If a `honchoClient` could be built (see below), uses `FallbackMemoryProvider(primary = HonchoMemoryProvider(honchoClient), fallback = embeddedProvider)`. Otherwise, logs a warning and falls back to `EmbeddedMemoryProvider` alone. |
| `"tencent"` | If `resolvedConfig.tencentMemoryUrl` is non-empty, uses `FallbackMemoryProvider(primary = TencentMemoryProvider(...), fallback = embeddedProvider)`. Otherwise, logs a warning and falls back to `EmbeddedMemoryProvider` alone. |
| anything else (including default `"embedded"`) | `EmbeddedMemoryProvider` directly. |

`AgentConfig` fields
(`shared/src/commonMain/kotlin/dev/promethe/core/AgentState.kt`) involved, with their default
values in the data class:

| Field | Default |
|---|---|
| `memoryProvider` | `"embedded"` |
| `honchoBaseUrl` | `""` |
| `honchoApiKey` | `""` |
| `tencentMemoryUrl` | `""` |
| `tencentMemoryServiceId` | `""` |
| `tencentMemoryApiKey` | `""` |

Associated environment variables, listed in `promethe/docs/CONFIGURATION.md`:

```bash
MEMORY_PROVIDER=embedded            # embedded (default), honcho, tencent
HONCHO_URL=                         # required if MEMORY_PROVIDER=honcho
HONCHO_API_KEY=
TENCENT_MEMORY_URL=                 # required if MEMORY_PROVIDER=tencent
TENCENT_MEMORY_SERVICE_ID=
TENCENT_MEMORY_API_KEY=
```

`honchoBaseUrl`/`honchoApiKey` are indeed read from `ConfigProvider` (`HONCHO_URL`,
`HONCHO_API_KEY`) in `AgentBootstrap.create`. The `honchoClient` is only built if
`honchoBaseUrl` is non-empty.

On the Desktop side, these same fields are also controllable via the **Settings → Memory**
screen (`composeApp/.../screens/settings/MemorySection.kt`), persisted in
`~/.promethe/credentials.json` (`CredentialsStore.Credentials.memoryProvider`, default
`"embedded"`).

The memory "nudge" cadence (`MemoryNudge`) is hardcoded to **5 user messages**
(`intervalMessages: Int = 5`) — no dedicated environment variable.

---

## Agent tools

Registered in `AgentBootstrap.create` (`shared/src/commonMain/kotlin/dev/promethe/core/tools/builtin/MemoryTools.kt`):

| Tool | Args | Behavior |
|---|---|---|
| `memory_save` | `content: String`, `category: String = "general"` | Creates a `MemoryFact` of tier `ATOMIC`, `confidence = 1.0f`, and saves it via `memoryLayer.storeFact`. |
| `memory_search` | `query: String`, `limit: Int = 10` | Calls `memoryLayer.recallFacts(query, limit)`; returns a text message listing the facts found or `"No facts found"`. |
| `memory_forget` | `factId: String` | If `factId == "all"`, retrieves all facts and deletes them one by one (returns count); otherwise deletes the fact by id. |
| `memory_list` | `category: String = ""` | Lists all facts, filtered by category if provided, grouped by category in the text output (id truncated to 8 characters). |

These tools always operate on `MemoryLayer` (never a `MemoryProvider` directly), so their
behavior depends on the active provider at startup.

---

## HTTP API

Defined in `gateway/src/jvmMain/kotlin/dev/promethe/gateway/MemoryRoutes.kt`
(`Route.memoryRoutes(memoryLayer: MemoryLayer)`), base `/memory`:

| Method | Route | Description |
|---|---|---|
| `GET` | `/memory/facts?q=&limit=` | If `q` is provided and non-empty, calls `recallFacts(q, limit)` (default `limit=50`); otherwise `getAllFacts()`. Response: `{ facts: [...], total, query }`. |
| `POST` | `/memory/facts` | Creates a fact from `{ category, content, confidence?, sourceSession?, tier? }`. `tier` is parsed into `MemoryTier` (case-insensitive) with fallback to `ATOMIC` if invalid. Responds `201 Created`. |
| `PUT` | `/memory/facts/{id}` | Updates `category`/`content`/`confidence` of an existing fact. `404` if not found. |
| `DELETE` | `/memory/facts/{id}` | Deletes a fact by id. |
| `GET` | `/memory/status` | Returns `{ provider: memoryLayer.providerName, factCount }`. |

The `tier` field in the JSON output (`MemoryFactDto.tier`) is the raw enum name (e.g. `"ATOMIC"`).

---

## Limitations

- **L2/L3 tiers barely used** — `SCENARIO` is only produced by `HonchoMemoryProvider`
  (single context block, not true multi-memory aggregation); `PERSONA` is not assigned anywhere
  in the current code. The data model anticipates a 4-tier architecture that
  the implementation does not yet fully fill in.
- **`HonchoMemoryProvider.getAllFacts()`** does not reflect the real state on the Honcho side: Honcho does not expose
  a listing API, so only the facts accumulated in process memory (`pendingFacts`) since
  the last restart are returned.
- **`EmbeddedMemoryProvider` search** relies on `searchUserFacts` (FTS5) — a text
  search, not a semantic/vector search.
- **`TencentMemoryProvider`** silently swallows network errors (`println` + empty list/value)
  rather than surfacing them to the caller; combined with `FallbackMemoryProvider`, a
  Tencent server outage is invisible to the agent (silent fallback to embedded).
- **Automatic extraction** only triggers if the session contains at least 4 messages, and
  depends on the LLM's ability to return strictly parsable JSON (an array between the first
  `[` and the last `]` of the text) — any malformed response silently fails
  extraction for that session.
- **No real multi-user scoping** — `MemoryFact.userId` exists and `getAllFacts(userId)`
  accepts it, but all agent tools and HTTP routes call it with the default value
  (`"default"`); there is no upstream user identity resolution in this code
  path.
