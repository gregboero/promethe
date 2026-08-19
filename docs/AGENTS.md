# AI Agent Guide — Internal Architecture

> Technical documentation of the Prométhé agent engine: PRA loop, tools, memory, resilience and self-evolution.

## Overview

Prométhé is an autonomous AI agent whose core is the `AIAgent` class in the `shared/` module. It runs a **Perception-Reasoning-Action (PRA) loop** that:

1. **Perceives** — loads context (profile, memory, skills, FTS5 history)
2. **Reasons** — calls the LLM with the enriched system prompt
3. **Acts** — executes a tool or responds directly to the user
4. **Learns** — extracts memory facts and synthesizes skills

```
┌──────────────────────────────────────────────┐
│              AIAgent.executeLoop()            │
│                                               │
│  ┌─────────┐   ┌───────────┐   ┌──────────┐ │
│  │ Perceive│──▶│  Reason   │──▶│   Act    │ │
│  │ context │   │  (LLM)    │   │ (tools)  │ │
│  └─────────┘   └───────────┘   └──────────┘ │
│       ▲                             │        │
│       └─────── observation ─────────┘        │
│                                               │
│  Post-loop: Memory extraction + Skill synth  │
└──────────────────────────────────────────────┘
```

## Main components

| File | Role |
|---|---|
| `AIAgent.kt` | Main PRA loop (589 lines) |
| `AgentConfig.kt` | Configuration (provider, model, temperature, etc.) |
| `AgentState.kt` | Current agent state (session, iteration) |
| `ActionExecutor.kt` | Dispatches tool execution |
| `ToolRegistry.kt` | Global tool registry (`object` singleton) |
| `ScopedToolRegistry.kt` | Filtered view of tools per agent (whitelist) |
| `BuiltinTools.kt` | Native tools (terminal, file_read, file_write, http_fetch) |
| `ProfileManager.kt` | Profile loading (USER.md, MEMORY.md, SOUL.md) |
| `SkillLoader.kt` | Skill loading with keyword index |
| `SkillWriter.kt` | Writing of automatically synthesized skills |

## PRA loop in detail

### 1. Initialization

```kotlin
class AIAgent(
    config: AgentConfig,       // LLM + execution config
    database: PrometheDatabaseApi,  // SQLite
    llmAdapter: KoogLlmAdapter,     // Multi-LLM adapter
    profileManager: ProfileManager,  // Profile files
    actionExecutor: ActionExecutor,  // Tool executor
    skillLoader: SkillLoader,        // Learned skills
    trajectoryEvaluator: TrajectoryEvaluator,
    skillWriter: SkillWriter,
    memoryLayer: MemoryLayer?,       // Multi-tier memory
    resilience: ResilienceStrategy?, // Error escalation
    hookManager: HookManager?,       // Pre/post hooks
    contextCompressor: ContextCompressor?, // Context compression
    dryRun: Boolean = false,         // Shadowing mode
)
```

### 2. Perception (context)

At each iteration, the agent loads:

| Source | Method | Description |
|---|---|---|
| **USER.md** | `profileManager.readFile()` | User profile |
| **Memory** | `memoryLayer.buildMemoryContext()` | L0-L3 facts from the provider |
| **MEMORY.md** | `profileManager.readFile()` | File memory (fallback) |
| **Skills** | `skillLoader.findRelevantSkills()` | Relevant skills by keyword |
| **FTS5** | `database.searchMessages()` | Semantic history (5 results) |
| **Project context** | `ContextFileLoader.loadContextFiles()` | .promethe.md, SOUL.md, AGENTS.md |
| **Session checkpoint** | `database.getLatestCheckpoint()` | Explicit session snapshot and rollback |

### 3. Reasoning (LLM)

The system prompt is built dynamically from all the sources above, then sent to the LLM via `KoogLlmAdapter.complete()`.

**Multi-model routing**: `MultiModelRouter` + `FallbackChain` handle automatic failover between providers.

### 4. Action (tools)

The LLM responds either with:
- **JSON `{ "action": { "tool_name": "...", "args": {...} } }`** → the tool is executed
- **Free text** → final response to the user

### 5. Resilience (escalation)

On error, the `ResilienceStrategy` escalates through 3 levels:

```
L1: RETRY     → Retry the same action
L2: REPLAN    → Reformulate the task via the LLM
L3: DECOMPOSE → Decompose into sub-tasks
L4: EXHAUSTED → Abandon cleanly
```

### 6. Post-loop

After the loop, the agent:
- **Extracts memory facts** via `memoryLayer.extractFacts()`
- **Synthesizes skills** if the trajectory succeeded (gated by `RewardSignal`)

## Tool system

### ToolRegistry (global)

```kotlin
object ToolRegistry {
    fun register(tool: ToolBase<*, *>)
    fun getTool(name: String): ToolBase<*, *>?
    fun listTools(): List<ToolBase<*, *>>
}
```

### Native tools (BuiltinTools)

| Tool | Description |
|---|---|
| `execute_command` | Executes a system command (local/docker/ssh/daytona) |
| `file_read` | Read a file |
| `file_write` | Write a file |
| `http_fetch` | HTTP GET/POST request |
| `git_*` | Git operations (clone, commit, push, diff) |
| `patch_file` | Apply a patch to a file |
| `web_search` | Web search (Tavily, Brave, DuckDuckGo, SearXNG) |
| `memory_*` | Memory tools (search, add, delete facts) |
| `introspection_*` | Agent self-analysis |

### MCP tools (McpBridge)

MCP servers are connected via `McpBridge` and their tools are automatically registered in the `ToolRegistry`:

```
McpBridge → McpStdioTransport (stdin/stdout JSON-RPC)
          → McpSseTransport   (HTTP + SSE)
```

### ScopedToolRegistry (per-agent)

Filters the tools accessible per agent via a whitelist:

```kotlin
// Agent with restricted access
ScopedToolRegistry.fromToolList(listOf("file_read", "web_search"))

// Agent with full access
ScopedToolRegistry.unrestricted()
```

## Memory system

4 tiers inspired by TencentDB Agent Memory:

| Tier | Name | Content | Lifetime |
|---|---|---|---|
| L0 | Conversation | Raw messages | Session |
| L1 | Atomic | Extracted facts (preferences, constraints) | Long term |
| L2 | Scenario | Aggregated scenario blocks | Long term |
| L3 | Persona | Synthesized user profile | Permanent |

### Providers

| Provider | Backend | When to use |
|---|---|---|
| `EmbeddedMemoryProvider` | SQLite + FTS5 | Default, zero config |
| `HonchoMemoryProvider` | Honcho REST API | Dedicated external service |
| `TencentMemoryProvider` | TencentDB REST API | Self-hosted or cloud |
| `FallbackMemoryProvider` | Fallback chain | Primary + secondary |

## GEPA — Prompt self-evolution

**Genetic Evolution of Prompt Architecture**: a genetic algorithm that optimizes the system prompt.

```
GepaEngine
├── GepaEvolver      → Generates prompt mutations
├── GepaMutations    → Mutation types (swap, add, remove, rephrase)
├── GepaEvaluator    → Evaluates candidates on test cases
├── ParetoSelector   → Multi-objective selection (accuracy, cost, latency)
└── GepaScheduler    → Scheduling of optimizations
```

## System hooks

The `HookManager` emits events at each phase:

| Event | When |
|---|---|
| `MESSAGE_RECEIVED` | User message received |
| `SESSION_START` | Session start |
| `SESSION_END` | Session end |
| `TOOL_CALLED` | Before tool execution |
| `TOOL_RESULT` | After tool execution |

Hooks are extensible via `BuiltinHooks` (logging, metrics, GEPA feedback).

## Project context files

The agent automatically loads these files if they exist at the root:

| File | Content |
|---|---|
| `.promethe.md` | Project-specific instructions |
| `SOUL.md` | Agent personality and tone |
| `AGENTS.md` | Multi-agent configuration |
| `USER.md` | User profile |
| `MEMORY.md` | Long-term memory (file fallback) |

## Multi-agent delegation (AgentOrchestrator)

`AgentOrchestrator` (`shared/src/jvmMain/kotlin/dev/promethe/core/AgentOrchestrator.kt`) manages
sub-agent lifecycle: spawning child agents with isolated sessions, tracking parent-child
relationships, cascading cancellation, and collecting results.

### Delegation flow

The LLM drives delegation through two tools (`DelegateTaskTool.kt` / `AgentManagementTools.kt`):

1. **`delegate_task`** — spawns a sub-agent (`AgentOrchestrator.delegateTask()`), which gets its
   own session id (`sub-<uuid>`) and runs in the background. If `blocking=true` (default), the
   tool polls `orchestrator.isRunning()` and returns the result inline; if `blocking=false`, it
   returns immediately with just the sub-agent's session id (an async subtask), leaving the
   caller to check on it later.
2. **`get_subtask_result`** — retrieves a previously spawned (non-blocking) sub-agent's result by
   session id (`GetSubtaskResultTool`), reporting `SUCCESS` / `ERROR` / `CANCELLED` and duration.

Execution routes through the `AgentA2ARegistry` when the target `profileId` (or `"main"`) is a
registered A2A agent (`registry.sendTask(...)`); otherwise it falls back to a direct
`llmAdapter.complete()` / `completeForProfile()` call with a generated sub-agent system prompt.

`list_agents` (`ListAgentsTool`) lets the LLM discover existing profiles (filterable by
`system` / `custom` / `ephemeral` / `persistent`) before delegating, and `create_agent`
(`CreateAgentTool`) creates new profiles — optionally inheriting provider/model/tools/temperature
from a `parentProfileId`.

### Ephemeral agents and promotion

`create_agent(ephemeral=true, ...)` creates a temporary, task-scoped profile (its id gets a random
suffix to avoid collisions). Ephemeral agents can become permanent in two ways:

- **Manually**: `promote_agent` (`PromoteAgentTool`) flips `ephemeral=false` for a given agent id.
- **Automatically**: `EphemeralAgentPromoter` (`shared/src/commonMain/kotlin/dev/promethe/core/EphemeralAgentPromoter.kt`)
  tracks per-agent usage/success counts via `onDelegationComplete()`, called after each delegation
  finishes in `AgentOrchestrator`. Once an ephemeral agent has been used at least
  `DEFAULT_USAGE_THRESHOLD` (3) times with a success rate `>= MIN_SUCCESS_RATE` (70%), it is
  auto-promoted to permanent.
- Expired ephemeral agents (not promoted) are removed via `cleanup_ephemeral_agents`
  (`CleanupEphemeralAgentsTool`), which deletes ephemeral profiles older than a configurable
  `olderThanHours` (default 24h); system and permanent agents are never affected.

## A2A integration

Agent profiles are automatically exposed as A2A agents, not just usable via `delegate_task`.
`AgentA2ABootstrap` (`shared/src/jvmMain/kotlin/dev/promethe/core/AgentA2ABootstrap.kt`) registers
the main agent and every `AgentProfileRow` from the database into the `AgentA2ARegistry` at
startup, and **hot-registers** new agents at runtime: `create_agent`'s `onAgentCreated` callback
invokes `registerSingleAgent(profileRow)` so a freshly created agent is immediately addressable via
`delegate_task` — no restart required. See **A2A.md** for the registry, executors
(`AIAgentA2AExecutor`, `ProfileAgentA2AExecutor`), and the gateway's JSON-RPC endpoint
(`POST /agents/`).

## Dry-Run / Shadowing Mode

Enabled via `dryRun = true` — the agent reasons and proposes actions but **never executes them**. Useful for:
- Testing a new profile without risk
- Auditing the agent's decisions
- Validating a skill before deployment
