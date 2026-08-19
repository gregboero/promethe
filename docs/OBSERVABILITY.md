# Observability

> Tracing, LLM usage stats, lifecycle hooks, and the Stats dashboard in Prométhé.

## Overview

Prométhé exposes three complementary layers of observability:

1. **Tracing** — OpenTelemetry spans around agent, LLM, tool, and GEPA execution, initialized by `TracySetup`.
2. **LLM usage stats** — per-request token counts and cost, persisted to SQLite and aggregated by `KoogLlmAdapter`.
3. **Lifecycle hooks** — `LoggingHook` and `MetricsHook`, which observe tool calls, errors, and sessions as they happen.

These are surfaced through gateway HTTP endpoints (`/status`, `/status/providers`, `/stats`) and consumed by the Compose **Stats screen** (`StatsScreen.kt`).

---

## OpenTelemetry tracing

`TracySetup.initialize(config: AgentConfig)` (`shared/src/commonMain/kotlin/dev/promethe/core/TracySetup.kt`) is the single entry point called once during gateway/agent startup, from `AgentBootstrap.kt`:

```kotlin
val llmAdapter = KoogLlmAdapter(resolvedConfig, database)
TracySetup.initialize(resolvedConfig)
llmAdapter.initializeWithRouter(apiKeys, database)
```

It forwards the selected backend, endpoint, and Langfuse credentials to the `Tracing` expect/actual object (`shared/src/commonMain/kotlin/dev/promethe/core/Tracing.kt`), which defines:

- `initialize(...)` — configures the OpenTelemetry Java SDK once at startup.
- `span(name, attributes) { ... }` — runs a suspendable block inside a named span and propagates the OTel context across coroutine thread changes.
- `flush()` — flush pending traces before shutdown.

### Config

| Variable | Default | Values |
|---|---|---|
| `TRACING_BACKEND` (`AgentConfig.tracingBackend`) | `console` | `none`, `console`, `langfuse`, `otlp` |
| `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` / `LANGFUSE_HOST` | — | Used when backend is `langfuse` |
| `OTLP_ENDPOINT` | — | Used when backend is `otlp` (e.g. `http://localhost:4317`) |

These are configurable via the Setup Screen / Settings → **Observability** section (`ObservabilitySection.kt`), which lets the user pick the backend and fill in Langfuse/OTLP fields, persisted through `CredentialManager` / `AgentConfig`.

### Exporters and data policy

- `none` disables tracing.
- `console` uses the local console exporter and performs no network request.
- `otlp` uses the configured `OTLP_ENDPOINT`; when it is blank, standard `OTEL_*` environment variables can configure the exporter.
- `langfuse` sends OTLP/HTTP spans to `<LANGFUSE_HOST>/api/public/otel` with Basic authentication and the Langfuse v4 ingestion header.

Backend changes require an application restart because the SDK is initialized once. Endpoint URLs must use HTTP or HTTPS and cannot contain embedded credentials.

Trace attributes are allowlisted metadata, not request payloads. Keys containing prompt, content, message, token, secret, password, credentials, cookies, or tool arguments are dropped unless they are an exact non-sensitive structural counter such as `gen_ai.usage.prompt_tokens`, `gen_ai.usage.completion_tokens`, or `gen_ai.request.message_count`. String values are length- and character-bounded, and exception messages are not attached to spans. `promethe.run.id` and `promethe.step.id` correlate agent, LLM, and tool activity without exporting conversation text.

---

## LLM usage stats

### What is recorded

`KoogLlmAdapter` (`shared/src/commonMain/kotlin/dev/promethe/core/KoogLlmAdapter.kt`) tracks, per completion:

- `promptTokens` / `completionTokens`
- `provider` and `model` actually used (post-fallback)
- `cost` (see cost computation below)
- a timestamp

It maintains in-memory cumulative counters (`totalRequests`, `totalPromptTokens`, `totalCompletionTokens`, `totalCost`). Prefix-cache telemetry deliberately separates provider-observed cache usage from local prefix reuse candidates:

- `cacheHits`, `cacheMisses`, `cacheReadTokens`, and `cacheWriteTokens` come only from provider response metadata;
- `prefixReuseHits` and `prefixReuseMisses` report whether the same provider/model/system/tools fingerprint was seen before;
- `cacheSize` is the bounded number of known prefix fingerprints, not a local response cache.

Anthropic system and tool prefixes receive explicit cache breakpoints. Other providers keep their native automatic cache behavior. If a client does not expose provider cache metadata, Promethe leaves the provider counters unchanged instead of reporting a false miss.

```kotlin
data class LlmStats(
    val totalRequests: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalCost: Double,
    val cacheHits: Long,
    val cacheMisses: Long,
    val cacheSize: Int,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val cacheObservableResponses: Long,
    val prefixReuseHits: Long,
    val prefixReuseMisses: Long,
)

suspend fun getStats(): LlmStats
```

### Persistence

Each request is also persisted to SQLite via `PrometheDatabaseApi.insertLlmUsageLog(LlmUsageLogRow)`, fire-and-forget (failures are caught and logged, never block the response):

```kotlin
database?.insertLlmUsageLog(
    LlmUsageLogRow(
        provider = actualProvider,
        model = actualModel,
        promptTokens = pTokens,
        completionTokens = cTokens,
        cost = cost,
        timestamp = System.currentTimeMillis(),
    ),
)
```

The backing table, `llm_usage_logs` (`shared/src/jvmMain/kotlin/dev/promethe/db/Tables.kt`), has columns `id`, `provider`, `model`, `prompt_tokens`, `completion_tokens`, `cost`, `timestamp`, with indexes on `timestamp` and `provider`.

Two aggregate reads exist on `PrometheDatabaseApi`:
- `getAggregatedLlmStats()` — total requests/tokens/cost across all providers. Used by `KoogLlmAdapter.restoreStats()` to rehydrate the in-memory counters from the DB once at gateway startup (so stats survive a restart).
- `getLlmUsageByProvider(): Map<String, LlmProviderStats>` — per-provider breakdown (request count, total tokens, total cost), used by `GET /status/providers`.

### Cost computation (ModelPricingService)

Cost is computed inline in `KoogLlmAdapter` for every completion:

```kotlin
val (pRate, cRate) = pricingService?.getPricing(actualProvider, actualModel, providerKey) ?: ...
val cost = (pTokens / 1_000_000.0) * pRate + (cTokens / 1_000_000.0) * cRate
```

`ModelPricingService` (`shared/src/commonMain/kotlin/dev/promethe/core/ModelPricingService.kt`) resolves `(promptPricePerMillion, completionPricePerMillion)` per model with an in-memory cache (1-hour TTL):

- **Ollama** → always free (`0.0, 0.0`).
- **LiteLLM** → queries the proxy's `/model/info` endpoint (preferred when `litellmBaseUrl` is configured — it reflects whatever pricing the proxy is configured with).
- **OpenRouter** → queries `/api/v1/models`, converting the per-token `prompt`/`completion` price strings to price-per-million.
- **OpenAI / Anthropic / Google / DeepSeek** → these providers don't expose pricing via API, so pricing falls back to a hardcoded `KNOWN_PRICING` table (e.g. `claude-sonnet-4-20250514` → `$3.00 / $15.00` per million prompt/completion tokens, `gpt-4o` → `$2.50 / $10.00`, etc.). Unknown models fall back further to `DEFAULT_PRICING` (`$1.00 / $1.00`).
- Any provider not explicitly listed tries LiteLLM first (if configured), else OpenRouter.

Because `KNOWN_PRICING` is a static table checked into source, costs for providers without a pricing API will drift as vendors change prices — see Limitations.

---

## Hooks (LoggingHook, MetricsHook)

Built-in hooks (`shared/src/commonMain/kotlin/dev/promethe/core/hooks/BuiltinHooks.kt`) are registered on `HookManager` at startup (`AgentBootstrap.kt`) and fire on lifecycle events (`HookEvent`: `BEFORE_TOOL_CALL`, `AFTER_TOOL_CALL`, `SESSION_START`, `SESSION_END`, `ON_ERROR`, `MESSAGE_RECEIVED`, `BEFORE_RESPONSE`).

### LoggingHook (`builtin.logging`, priority 10 — runs first)

Logs to the standard logger (not persisted):
- `BEFORE_TOOL_CALL` — debug-logs the tool name and argument keys.
- `AFTER_TOOL_CALL` — debug-logs the tool name and first 200 chars of its result.
- `ON_ERROR` — error-logs the session id and exception message.

### MetricsHook (`builtin.metrics`, priority 20)

Accumulates in-memory counters (no persistence, no HTTP exposure — see Limitations):
- `toolCallCount: Map<String, Int>` — invocation count per tool name, incremented on `BEFORE_TOOL_CALL`.
- `errorCount` — incremented on `ON_ERROR`.
- `sessionCount` — incremented on `SESSION_START`.

Exposed programmatically via `getMetrics(): Map<String, Any>` (keys `tool_calls`, `error_count`, `session_count`), but nothing in the gateway or UI currently calls it.

Other built-in hooks exist alongside these (`GuardrailHook`, `WebhookDispatchHook`, `GepaFeedbackHook`) but are not stats/observability hooks — they gate or forward events rather than record metrics.

`HookManager.listHooks()` (returning just the registered hook ids) is what backs the `hooks` section of `GET /status` and the Stats screen's "Hooks" chip list.

---

## HTTP endpoints exposing stats

### `StatusRoutes.kt` (`gateway/src/jvmMain/kotlin/dev/promethe/gateway/StatusRoutes.kt`)

| Route | Description |
|---|---|
| `GET /health` | Simple liveness probe (`{status, timestamp}`). |
| `GET /status` | Aggregated dashboard: runtime (uptime, model, provider, execution backend), `llm` (requests, prompt/completion tokens, total cost, prompt-cache hit/miss/size/hit-rate), `memory` (provider + fact count), `hooks` (count + ids), `scheduler` (running + task count), `plugins` (total/enabled/tools). |
| `GET /status/providers` | Per-provider health: key pool size, active/configured/no_keys status, and usage (`totalRequests`, `totalTokens`, `totalCost`) sourced from `database.getLlmUsageByProvider()`. |
| `POST /settings/test-llm` | Ad-hoc connectivity test against a given provider (latency + ok/error), not persisted. |
| `POST /settings/reload` | Hot-reloads LLM API keys/provider/model from credentials without restarting. |

### `FeedbackRoutes.kt` (`gateway/src/jvmMain/kotlin/dev/promethe/gateway/FeedbackRoutes.kt`)

| Route | Description |
|---|---|
| `GET /stats` | `StatsResponse`: `totalTokens`, `totalRequests`, `estimatedCost` (all from `llmAdapter.getStats()`), plus `avgFeedback` / `feedbackCount` from `FeedbackCollector.getGlobalStats()`. |
| `GET /feedback` | Global feedback average + count. |
| `POST /feedback` | Records a feedback score (used to compute the GEPA reward signal). |
| `POST/GET /gepa/*` | GEPA optimization job control (not usage stats, but surfaced on the same screen). |

---

## UI (StatsScreen)

`StatsScreen.kt` (`composeApp/src/commonMain/kotlin/dev/promethe/app/screens/StatsScreen.kt`), driven by `StatsViewModel`, polls `getStats()`, `getSystemStatus()`, and `getProviderHealth()` on load and every 30 seconds (`startAutoRefresh`). Layout:

- **KPI grid** (4 cards): total tokens, total requests, estimated cost (USD), average feedback.
- **Système** section: runtime card (uptime, model, provider, execution backend, version); LLM card (requests, prompt/completion tokens, cost) and Cache card (hits/misses/size/rate) side by side; Scheduler / Plugins / Memory cards.
- **Fournisseurs LLM**: a chip per configured provider, colored by status (`configured` = green, `no_keys` = red), showing key-pool size.
- **Hooks**: a chip per registered hook id (from `HookManager.listHooks()`), rendered as `⚡ <id>`.
- **GEPA** section: a button to trigger prompt optimization, polling job status, then rendering accuracy/improvement/generations/candidates and a preview of the best prompt.

A green/red dot in the top bar reflects whether `/status` responded (`healthOk = state.systemStatus != null`).

---

## Limitations

- **Tracing is metadata-only by design.** Prompt and response bodies are excluded, so detailed content inspection requires a separate explicitly consented diagnostic workflow.
- **`MetricsHook` is registered but not exposed.** Its `getMetrics()` (tool call counts, error count, session count) has no HTTP route or UI panel reading it, and it does not persist across restarts.
- **`LoggingHook` output goes to process logs only** — no structured/query-able audit trail beyond whatever log aggregation is set up outside Prométhé.
- **Pricing for providers without a pricing API is static.** OpenAI/Anthropic/Google/DeepSeek costs use a hardcoded `KNOWN_PRICING` table in `ModelPricingService.kt` that must be updated manually as vendors change prices; unlisted models silently fall back to a `$1/$1` per-million default, which can under- or over-estimate cost.
- **`/status/providers` usage figures depend on the SQLite `llm_usage_logs` table** — if the DB insert fails (logged as a warning, non-fatal), that request's usage is silently missing from persisted aggregates even though the in-memory counters still include it until restart.
