# Context Management & Resilience

> How Promethe keeps long-running agent sessions within token budgets and recovers
> from LLM/tool failures without dropping the conversation.

## Overview

Seven cooperating components handle the "things go wrong" and "context gets too big"
problems:

| Component | File | Role |
|---|---|---|
| `ContextCompressor` | `shared/src/commonMain/kotlin/dev/promethe/core/ContextCompressor.kt` | Keeps conversation history within the model's context window |
| `ToolOutputPruner` | `shared/src/commonMain/kotlin/dev/promethe/core/ToolOutputPruner.kt` | Pre-cleans verbose tool output before compression |
| `ArtifactStore` | `shared/src/commonMain/kotlin/dev/promethe/core/ArtifactStore.kt`, `FileArtifactStore.kt` | Stores large text tool observations by SHA-256 and returns compact durable references |
| `ResilienceStrategy` | `shared/src/commonMain/kotlin/dev/promethe/core/ResilienceStrategy.kt` | 3-level failure escalation (retry → replan → decompose) for the agent loop |
| `AutoHealingExecutor` | `shared/src/jvmMain/kotlin/dev/promethe/core/AutoHealingExecutor.kt` | Wraps `ActionExecutor` with transparent tool-call retries |
| `KoogLlmAdapter` fallback chain | `shared/src/commonMain/kotlin/dev/promethe/core/KoogLlmAdapter.kt` | Intra-provider then cross-provider model fallback on LLM call failure |
| `MultiModelRouter` / `FallbackChain` | `shared/src/commonMain/kotlin/dev/promethe/core/MultiModelRouter.kt`, `FallbackChain.kt` | Per-provider executor pool + generic priority-ordered fallback chain used to build `MultiModelRouter.buildFallbackChain()` |

All of this is wired up in `AgentBootstrap.kt`, which constructs one
`ResilienceStrategy` and one `ContextCompressor` per agent and passes them into
`AIAgent`, plus one `AutoHealingExecutor` wrapping the shared `ActionExecutor`.

Before a successful tool result enters the conversation, `ActionExecutor` lets
the after-tool hooks inspect the complete value. Results larger than 8 KiB are
then written outside the workspace under the active Promethe profile's
`artifacts/sha256/` directory. The observation keeps a bounded head and tail,
the SHA-256 hash, byte size and an `artifact://sha256/...` URI. The hash is also
stored in `tool_intents` and `agent_run_events`; `artifact_read` retrieves at
most 4096 bytes per call. Failed and blocked results are never externalized and
remain bounded by the existing output limit.

---

## Context compression

`ContextCompressor.compress(systemPrompt, messages)` runs on every turn of the
agent loop (`AIAgent.kt`), right before the LLM call, on the raw `(role, content)`
history pulled from the DB.

### Token budget

Token counts are estimated by `TokenCounter` using a **4 characters ≈ 1 token**
heuristic (no tiktoken dependency) — accurate to roughly ±10% for English/code.
Each message adds ~4 tokens of overhead for role/delimiters; the system prompt
adds its own 4-token overhead.

Compression triggers when `totalTokens (system + messages) > maxContextTokens *
compressionThreshold`. Defaults (from `AgentConfig` in `AgentState.kt`):
`maxContextTokens = 100_000`, `compressionThreshold = 0.8` — compression kicks
in past 80% of the 100K-token window. Both are user-configurable (see
Configuration below).

If the message list is too short to compress meaningfully
(`messages.size <= keepFirstN + keepLastN + 1`, i.e. 9 or fewer), compression is
skipped even if over budget.

### What gets compressed

1. **Prune pass** — `ToolOutputPruner.prune()` runs first. It truncates any
   `tool`-role message (or long `assistant` message) over 2000 characters:
   raw HTML dumps become a `[HTML output — N chars, not shown]` placeholder,
   stack traces collapse to the first 5 frames plus a "`... N more stack frames
   pruned ...`" marker, and everything else keeps the first/last 500 characters
   with a `[...output pruned: N chars removed...]` marker in between. If
   pruning alone brings the total back under threshold, summarization is
   skipped entirely.
2. **Sliding-window summarization** — if still over budget, the compressor keeps
   the first 2 messages (`keepFirstN`) and last 6 messages (`keepLastN`)
   untouched, and replaces everything in between with one `system` message:
   `[Context Summary — N messages compressed]` followed by an LLM-generated
   summary (prompted to stay under 300 words, temperature 0.1, using the
   agent's configured model).
3. **Summary caching** — summaries are cached in-memory keyed by the hash of
   the compressed message block, so the same block is never re-summarized
   twice in a session. Capped at 50 entries (oldest evicted first); can be
   cleared via `clearCache()` (e.g. on session reset).

If there's nothing between head and tail to compress, the original messages are
returned unchanged.

---

## Retry / resilience strategy

`ResilienceStrategy` implements a Hermes-style 3-level escalation, tracked via an
`EscalationState` (level, attempt count, accumulated errors, current/original
task) that lives inside the `AIAgent` loop across turns:

```
RETRY (same prompt, up to retryAttempts=3)
   ↓ exhausted
REPLAN (LLM reformulates the task from the errors so far, up to replanAttempts=2)
   ↓ exhausted
DECOMPOSE (LLM breaks the task into 2-5 sub-tasks) — one-shot
   ↓ fails
EXHAUSTED — error propagated to the user
```

`escalate(state, error)` advances the state machine. A small set of error
strings are treated as **permanent** and skip straight to `EXHAUSTED` without
consuming any retry budget: any message containing `"cannot determine"`,
`"not initialized"`, `"invalid api"`, `"401"`, or `"403"` (case-insensitive).

`AIAgent.kt` calls `resilience.escalate()` on two categories of failure: an
exception from the LLM completion call (`fetchLlmCompletionFromPairs`), or an
exception from `actionExecutor.execute(toolName, args)`.

On `REPLAN`, `resilience.replan(originalTask, errors, history)` asks the LLM to
produce a reformulated task description from the last 3 errors, falling back to
the original task text if the LLM call itself fails. On `DECOMPOSE`,
`resilience.decompose(originalTask, errors)` asks the LLM for a JSON array of
2-5 sub-task strings, falling back to `[originalTask]` if the call fails or the
JSON can't be parsed. `describe(state)` renders a human-readable status line
(e.g. `⟳ Retry 1/3`, `🔄 Replan 1/2`, `❌ All escalation levels exhausted (N
errors)`) that gets emitted into the trajectory log / UI.

---

## Auto-healing (tool-call retries)

`AutoHealingExecutor` wraps `ActionExecutor.execute()` and adds a *lower-level*,
per-call retry loop distinct from `ResilienceStrategy`'s escalation (it's
constructed alongside `ResilienceStrategy` in `AgentBootstrap.kt` but is a
separate wrapper — `resilience` is passed to `AIAgent`, `autoHealing` wraps the
shared `ActionExecutor`).

`executeWithHealing(toolName, args)` retries up to `maxRetries = 3` times when:

- the tool result string starts with `[ERROR]`, `[BLOCKED]`, or `Error:`
  (checked via `isErrorResult`), or
- an exception is thrown **and** it looks transient (`isTransient`): message
  contains `"timeout"`, `"connection"`, `"rate limit"`, `"429"`, `"503"`,
  `"502"`, `"socket"`, `"reset"`, or the exception is a
  `java.net.SocketTimeoutException` / `java.net.ConnectException`.

Non-transient exceptions are not retried — the failure is returned immediately.
Between retries it sleeps `retryDelayMs * attempt` (default `retryDelayMs =
1000`ms), i.e. simple linear backoff (1s, 2s, ...). The result is an
`AutoHealResult(success, result, attempts, errors, healed)` where `healed` is
true only if a later attempt succeeded after at least one prior failure.

---

## LLM fallback chain

There are two distinct fallback mechanisms in the codebase:

### 1. Built-in adapter fallback (opt-in)

`KoogLlmAdapter.executeCompletion()` wraps every `exec.execute(...)` call in a
try/catch. On failure it builds an ordered fallback list and tries each in turn:

When `FALLBACK_CHAIN_ENABLED=true`, the adapter may try a current same-provider candidate:
`google/gemini-3.1-flash-lite`, `openai/gpt-5.6-terra`,
`anthropic/claude-sonnet-5`, or `deepseek/deepseek-v4-flash`. NIM, LiteLLM,
OpenRouter and Ollama have no invented global fallback. The optional cross-provider candidate is
`google/gemini-3.1-flash-lite`.

Each fallback entry is attempted with its own executor (obtained from the
`MultiModelRouter` when the fallback provider differs from the original); if all
fallbacks fail, the original exception is re-thrown. On success, a
`fallbackNotice` string (French, e.g. "⚠️ Le modèle **X** (provider) n'est pas
disponible. Réponse générée par **Y** (provider).") is attached to the
`LlmResponse` and surfaced to the user — `AIAgent.kt` persists it as a `system`
message and emits a trajectory noting which model/provider was actually used.
Pricing/cost tracking uses the *actual* model/provider used, not the requested
one.

### 2. Profile-level fallback chain (`MultiModelRouter` / `FallbackChain`)

`MultiModelRouter.buildFallbackChain(profileId)` builds a priority-ordered
`FallbackChain` from the agent profile's primary provider/model plus one entry
per configured provider that has a non-blank certified candidate. Providers whose catalog depends
on the installation or account are omitted until a model is selected explicitly.

`FallbackChain.executeWithFallback(block)` runs entries in priority order,
classifying errors via `isRetryableError`: `401`/`403` and "invalid api key"
style messages are **not** retried (thrown immediately); `429`, `500`/`502`/`503`,
timeouts, and connection errors **are** retried against the next entry, with
linear backoff (`retryDelayMs * (index + 1)`, default `retryDelayMs = 1000`ms).
If every entry fails, a `RuntimeException` summarizing all attempts is thrown.

`MultiModelRouter` also maintains a **round-robin credential pool** per
provider — an API key value can be a comma-separated list (`"sk-key1,sk-key2"`)
and `getNextKey(provider)` rotates through them on each call, independent of
the fallback chain.

---

## Configuration

All values below are fields on `AgentConfig` (`shared/.../AgentState.kt`),
resolved at startup by `AgentBootstrap.kt` (see the Setup Screen / CLI flags —
not meant to be hand-edited in `.env` per the project's runtime-config
convention):

| Field | Default | Effect |
|---|---|---|
| `maxContextTokens` | `100_000` | Token budget used by `ContextCompressor` |
| `compressionThreshold` | `0.8` | Fraction of `maxContextTokens` that triggers compression |

The Compose Desktop Settings screen exposes these as **Context Window** /
**Compression Threshold** sliders (`ContextWindowSection.kt`,
`SettingsViewModel.kt`, `CredentialManager.kt` — UI-side default there is
`128000` tokens / `0.8`, overridden by whatever the user has saved).

`ResilienceStrategy` and `AutoHealingExecutor` retry counts are **constructor
defaults, not exposed as env vars or Setup Screen fields**:

| Component | Parameter | Default |
|---|---|---|
| `ResilienceStrategy` | `retryAttempts` | `3` |
| `ResilienceStrategy` | `replanAttempts` | `2` |
| `AutoHealingExecutor` | `maxRetries` | `3` |
| `AutoHealingExecutor` | `retryDelayMs` | `1000` |
| `FallbackChain` | `retryDelayMs` | `1000` |

Both `ResilienceStrategy` and `ContextCompressor` are constructed once per agent
in `AgentBootstrap.kt`; there is currently no env var to override the
retry/backoff constants without changing code.

Agent introspection tools (`agent_status`, `token_budget` in
`IntrospectionTools.kt`) let the agent query cumulative prompt/completion
tokens, request count, estimated cost, and prompt-cache hit rate via
`KoogLlmAdapter.getStats()` — useful for reasoning about its own budget, though
it does not read `maxContextTokens`/`compressionThreshold` directly.

---

## Limitations

- Token counts are **estimates** (4 chars/token heuristic), not exact
  tokenizer output — actual usage can drift from the configured budget by
  roughly ±10%.
- Compression only triggers when there are more than `keepFirstN + keepLastN +
  1` (9) messages; short conversations are never compressed even if a single
  huge message blows the budget.
- The summary cache is in-memory only (per process) and capped at 50 entries;
  it is not persisted across restarts.
- The ArtifactStore currently covers successful text tool outputs only. It has
  no encryption, owner quota, retention/garbage collection, remote backend or
  first-class capture/file/audio metadata yet.
- `ResilienceStrategy`'s non-retryable error detection is a simple substring
  match on the error message (`"401"`, `"403"`, `"invalid api"`, etc.) — it is
  not based on structured error codes/types.
- `AutoHealingExecutor.maxRetries` / `retryDelayMs` and
  `ResilienceStrategy.retryAttempts` / `replanAttempts` are hardcoded
  constructor defaults in `AgentBootstrap.kt`; changing them requires a code
  change, not a config toggle.
- The optional cross-provider candidate is `google/gemini-3.1-flash-lite`; if Google is not
  configured, the router skips or fails that candidate and propagates the original error.
- `DECOMPOSE` is one-shot: if decomposition itself fails or the sub-tasks also
  fail, the state goes straight to `EXHAUSTED` with no further escalation.
