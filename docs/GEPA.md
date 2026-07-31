# GEPA — Genetic-Pareto Prompt Evolution

> Self-evolution system that mutates and evaluates prompts/skills to improve agent performance over time.

## Overview

GEPA (Genetic-Pareto) is Promethe's self-evolution engine. It takes a piece of text that drives
agent behavior — the main system prompt, a skill, or an agent profile's system prompt — and
searches for a better version of it using a genetic-algorithm loop:

1. **Seed** a population of candidate prompts (the original + LLM-generated rewrites).
2. **Evaluate** each candidate against a set of test cases (exact match, substring match, or
   LLM-as-judge scoring).
3. **Select** the Pareto-optimal candidates — those not dominated by any other candidate across
   accuracy, token count, and latency.
4. **Reproduce** the next generation via crossover, mutation, or straight copy of tournament
   winners.
5. Repeat until `maxGenerations` is reached or the improvement between generations drops below a
   convergence threshold.

There are two independent implementations sharing the same Pareto/genetic vocabulary:

| Implementation | Optimizes | Evaluation method |
|---|---|---|
| `GepaEngine` (`dev.promethe.core`) | A full system prompt, against `GepaTestCase`s | Exact match / contains / LLM judge scoring a single output |
| `GepaEvolver` (`dev.promethe.core.evolution`) | A single skill's Markdown content | Pairwise LLM-as-judge comparison against the original, on completeness/clarity/robustness |

`GepaJobManager` (gateway) dispatches to one or the other depending on the requested target.

---

## Architecture

### Engine — `GepaEngine`

File: `shared/src/commonMain/kotlin/dev/promethe/core/GepaEngine.kt`

`GepaEngine(llmAdapter, config, gepaConfig)` owns one `GepaMutations`, one `GepaEvaluator`, and one
`ParetoSelector`. Its `optimize(originalPrompt, testCases, onProgress)` entry point:

- Seeds a population of size `gepaConfig.populationSize`.
- For each generation: evaluates the population in parallel (`async`/`awaitAll`), computes Pareto
  fronts, emits a `GenerationSnapshot` via `onProgress`, checks convergence
  (`currentBest.accuracy - bestEval.accuracy < convergenceThreshold` after generation 2), and — if
  not converged/finished — reproduces the next generation.
- Wraps the whole run in a `Tracing.span("gepa.optimize")` with attributes for population size,
  test case count, max generations, best accuracy, and improvement.
- Returns a `GepaResult` (best prompt, best candidate, best evaluation, per-generation history,
  original prompt, overall improvement).

Reproduction (`reproduce`) fills the next generation by:
- Copying the top `eliteCount` candidates from the first Pareto front (elitism).
- Rolling a random number against `crossoverRate` / `mutationRate` to decide, per slot, whether to
  crossover two tournament winners, mutate one tournament winner, or copy one tournament winner
  as-is — using `ParetoSelector.tournamentSelect` to pick parents.

### Config — `GepaConfig`

File: `shared/src/commonMain/kotlin/dev/promethe/core/gepa/GepaModels.kt`

```kotlin
data class GepaConfig(
    val populationSize: Int = 8,
    val maxGenerations: Int = 5,
    val eliteCount: Int = 2,
    val crossoverRate: Double = 0.4,
    val mutationRate: Double = 0.3,
    val tournamentSize: Int = 3,
    val convergenceThreshold: Double = 0.01,
)
```

Other key models in the same file: `PromptCandidate` (id, promptText, generation, parentIds,
mutationType), `MutationType` (`SEED`, `LLM_REPHRASE`, `CROSSOVER`, `SEGMENT_INSERT`,
`SEGMENT_DELETE`, `SEGMENT_MODIFY`), `CandidateEvaluation` (accuracy, tokenCount, avgLatencyMs,
per-test-case results, plus a `dominates()` method implementing Pareto dominance), `GepaTestCase`
(userInput, expectedBehavior, optional expectedOutput, `EvaluationType`), and `GenerationSnapshot`
(per-generation stats surfaced to callers/UI).

### Mutations — `GepaMutations`

File: `shared/src/commonMain/kotlin/dev/promethe/core/gepa/GepaMutations.kt`

- `seedPopulation(originalPrompt, size)` — keeps the original prompt as candidate 0, then generates
  `size - 1` LLM rephrasings (`llmRephrase`) cycling through four styles: more concise, more
  structured/numbered, more explicit about edge cases, or focused on clarity/redundancy removal.
  Falls back to the original prompt text if the LLM call throws.
- `crossover(p1, p2, gen)` — splits both parents' prompt text into lines, picks a random cut point
  in each, and joins `p1`'s head with `p2`'s tail.
- `mutateSegment(parent, gen)` — picks one of `SEGMENT_INSERT` (adds a fixed instruction line at a
  random position), `SEGMENT_DELETE` (removes a random line, only if more than 3 lines remain), or
  `SEGMENT_MODIFY` (uppercases a random line) — a deliberately simple, non-LLM mutation.

### Evaluation — `GepaEvaluator`

File: `shared/src/commonMain/kotlin/dev/promethe/core/gepa/GepaEvaluator.kt`

`evaluate(candidate, testCases)` runs all test cases in parallel and aggregates into a
`CandidateEvaluation`: mean score as `accuracy`, `tokenCount` estimated as `text.length / 4.0`
(character-based heuristic, not a real tokenizer), and mean latency in ms.

Per test case (`evaluateSingle`), the candidate's prompt text is used as the system prompt for a
single completion, scored according to `EvaluationType`:
- `EXACT_MATCH` — trimmed string equality against `expectedOutput`.
- `CONTAINS` — case-insensitive substring check.
- `LLM_JUDGE` — a second LLM call (`temperature = 0.0`) asked to output a bare `0.0`–`1.0` score;
  falls back to `0.5` on parse failure or exception.

### Pareto selection — `ParetoSelector`

File: `shared/src/commonMain/kotlin/dev/promethe/core/gepa/ParetoSelector.kt`

Brute-force non-dominated sorting, `O(n²)` per front — the docstring notes this is "optimal for
GEPA population sizes (8-20)," i.e. deliberately not optimized for larger populations.

- `computeParetoFronts(evals)` — repeatedly extracts the set of candidates not dominated by any
  remaining candidate, producing successive fronts.
- `tournamentSelect(evals, fronts, k)` — samples `k` random contestants and returns the one with
  the best (lowest-index) Pareto front rank.
- `selectElites(fronts, count)` — takes the top `count` candidates of front 0, sorted by accuracy.

### Trajectory evaluator (skill synthesis, not GEPA evolution)

File: `shared/src/commonMain/kotlin/dev/promethe/core/TrajectoryEvaluator.kt`

Related but distinct from prompt evolution: decides whether a completed conversation trajectory is
good enough to synthesize into a new Skill (`shouldSynthesize` requires ≥3 tool steps, no
`[ERROR]` observations, and a final response), then calls the LLM to write a `SKILL.md`-shaped
document (`synthesize`), validating required sections (`# Skill:`, `## Objective`, `## Steps`)
before accepting it.

### Reward signal — `RewardSignal`

File: `shared/src/commonMain/kotlin/dev/promethe/core/RewardSignal.kt`

A small heuristic, not a GEPA fitness function: `adjustConfig(avgScore, current)` nudges
`AgentConfig.temperature` up when feedback is poor (`avgScore < 0.4`, +0.1, capped at 0.9) and down
when feedback is strong (`avgScore > 0.8`, -0.05, floored at 0.05).
`shouldAllowSynthesis(sessionScore)` gates skill synthesis on a session score ≥ 0.6 (or null,
i.e. no feedback recorded).

### Skill evolution — `GepaEvolver` and `GepaScheduler`

File: `shared/src/commonMain/kotlin/dev/promethe/core/evolution/GepaEvolver.kt`

`GepaEvolver` implements the same six-stage loop (collect → reflect → mutate → evaluate → select →
integrate) but targets a single skill's Markdown content instead of the system prompt:

- `evolve(skillName, failedTrajectories)` seeds a population from the original skill content plus
  LLM-generated "reflective mutations" (`reflectAndMutate`) that analyze up to 3 recent failed
  trajectories (query, score, feedback, last 3 trajectory steps) and produce a targeted, minimal
  edit to the skill's Markdown, preserving its `# Skill: / ## Objective / ## Steps` structure.
- Candidates are scored with `evaluatePairwise`: two LLM-judge calls comparing candidate vs.
  original on `completeness`, `clarity`, `robustness`, run twice with swapped positions to mitigate
  position bias, then averaged.
- `selectParetoFront` / `dominates` reimplement Pareto dominance over those three named criteria
  (separately from `ParetoSelector`/`CandidateEvaluation` used by `GepaEngine`).
- Returns `null` if the best candidate's average score doesn't beat the original's implicit
  baseline (0.5 on every criterion) — i.e., no forced improvement.
- `apply(result)` deletes the old skill via `SkillLoader` and rewrites it via `SkillWriter`.

File: `shared/src/commonMain/kotlin/dev/promethe/core/evolution/GepaScheduler.kt`

`GepaScheduler` is the orchestrator sitting above `GepaEvolver`:

- Buffers up to the last 100 recorded trajectories in memory (`recordTrajectory`).
- `runEvolutionCycle()` filters trajectories with `score < 0.6` as "failures," groups them by the
  skill(s) used (falling back to a synthetic `_system_prompt` bucket if no skill is identified),
  and runs `GepaEvolver.evolve` per skill.
- If `autoApply` is true, successful evolutions are applied immediately; otherwise they're logged
  and left for manual application.
- `startPeriodicEvolution(intervalMinutes)` launches a background loop that calls
  `runEvolutionCycle()` on a fixed delay; `stop()` cancels it.
- `getStatus()` / `getEvolutionHistory()` expose in-memory state (pending/failed trajectory counts,
  last evolution summary, full history) — not currently wired to an HTTP route.

---

## Feedback capture

### `GepaFeedbackHook`

File: `shared/src/commonMain/kotlin/dev/promethe/core/hooks/GepaFeedbackHook.kt`

Registered as a `Hook` on `HookEvent.SESSION_END` (priority 80 — runs late, after
metrics/logging). It is the integration point that closes the loop: *agent runs → hook captures
trajectory → GEPA analyzes failures → skills improve*.

- `recordStep(sessionId, step, originalQuery)` is called externally by the agent loop during
  execution to accumulate trajectory steps and the originating query per session, in an in-memory
  buffer keyed by session ID.
- On `SESSION_END`, it pulls the buffered trajectory, computes a heuristic score
  (`computeTrajectoryScore`: base 0.7, -0.3 for a session-level error, -0.1 per `[ERROR]`
  observation capped at -0.3, +0.2 if a final response exists, -0.05 per step beyond 10 capped at
  -0.2, clamped to `[0, 1]`), detects skill tool calls used (`skill_*` / containing `"skill"`), and
  forwards everything to `GepaScheduler.recordTrajectory`.

### `FeedbackCollector`

File: `shared/src/commonMain/kotlin/dev/promethe/core/FeedbackCollector.kt`

Handles *explicit* user feedback (as opposed to the heuristic trajectory scoring above):

- `parseScore(input)` accepts emoji/word shorthands (`👍`/`+`/`y`/`yes`/`good` → 1.0, `👎`/`-`/`n`/
  `no`/`bad` → 0.0, `😐`/`~`/`meh`/`ok` → 0.5) or a numeric string, normalizing a 0–10 scale to
  0–1.
- `record(sessionId, score, comment)` persists to the database via
  `PrometheDatabaseApi.insertFeedback`.
- `getGlobalStats()` returns the average score and count across all recorded feedback
  (`database.getAverageFeedback()`).

### `TrajectoryExporter`

File: `shared/src/commonMain/kotlin/dev/promethe/core/export/TrajectoryExporter.kt`

A standalone export utility for trajectory data — not directly wired into the GEPA loop itself, but
useful for offline analysis or external fine-tuning pipelines. Supports three output formats
(`ExportFormat`): `SHAREGPT` (conversations array), `JSONL` (one message per line), and
`OPENAI_FINETUNE` (`messages` array per line, filtered to system/user/assistant roles). Filtering
via `TrajectoryFilter` supports minimum step count, success-only (no `[ERROR]`-prefixed messages),
timestamp range, and tag matching.

---

## Scheduling

Configured via environment variables, read in
`shared/src/jvmMain/kotlin/dev/promethe/core/AgentBootstrap.kt`:

```kotlin
gepaEnabled = ConfigProvider.get().getBoolean("GEPA_ENABLED", false),
gepaIntervalMinutes = ConfigProvider.get().getLong("GEPA_INTERVAL_MINUTES", 60),
gepaAutoApply = ConfigProvider.get().getBoolean("GEPA_AUTO_APPLY", false),
```

| Variable | Default | Effect |
|---|---|---|
| `GEPA_ENABLED` | `false` | Whether `GepaScheduler.startPeriodicEvolution` is called at all |
| `GEPA_INTERVAL_MINUTES` | `60` | Delay between automatic evolution cycles |
| `GEPA_AUTO_APPLY` | `false` | Whether successful skill evolutions are applied automatically vs. left for manual review |

At bootstrap, `GepaScheduler` is always constructed (with `autoApply = resolvedConfig.gepaAutoApply`)
and `GepaFeedbackHook` is always registered against it, regardless of `GEPA_ENABLED` — only the
periodic background loop is gated by that flag. If disabled, the log states: *"GEPA self-evolution
disabled (set GEPA_ENABLED=true to activate)"*.

---

## Job management & HTTP API

### `GepaJobManager`

File: `gateway/src/jvmMain/kotlin/dev/promethe/gateway/GepaJobManager.kt`

Runs GEPA optimizations as background jobs on a dedicated `CoroutineScope(Dispatchers.Default +
SupervisorJob())`, independent of any single HTTP request's lifecycle — a client starts a job, then
polls for status; navigating away and back still works.

`startOptimization(request: GepaOptimizeRequest)` dispatches on `request.target`:

| Target prefix | Behavior |
|---|---|
| `"skill:<name>"` | Builds a `GepaEvolver` (requires `llmAdapter`, `config`, `skillLoader`, `skillWriter` all non-null) and calls `evolve(skillName, emptyList())` — manual runs pass no failed trajectories, so mutation relies purely on reflective analysis of the skill content. Auto-applies the result if one is found. |
| `"profile:<id>"` | Loads the profile from the database, requires a non-blank `systemPrompt`, runs the main agent's `optimizeSystemPrompt` as a proxy optimizer, then writes the resulting best prompt back onto the profile row. |
| *(default)* `"system_prompt"` | Calls `agent.optimizeSystemPrompt`, which builds `GepaTestCase`s from the last 10 sessions' user messages (up to 5), falling back to 3 hardcoded French test cases if there's no history, then runs `GepaEngine(llmAdapter, config).optimize(...)`. |

Job state (`GepaJobState`) tracks `status` (`"running"` / `"completed"` / `"failed"`), `progress`
(0–1, driven by `onProgress` snapshots), `currentGeneration`, `maxGenerations`, `result`
(`GepaResultDto`), `error`, and `startedAt`. `getJob`, `getAllJobs`, `hasRunningJob`, and
`getRunningJob` expose state for polling; only one job is allowed to run at a time
(`/gepa/optimize` returns `409 Conflict` with the running job if one is already in progress).

### HTTP routes

File: `gateway/src/jvmMain/kotlin/dev/promethe/gateway/FeedbackRoutes.kt`, mounted under `/api`
(see `route("api")` in `OmnichannelGateway.kt`):

| Method | Route | Description |
|---|---|---|
| `POST` | `/api/v1/gepa/optimize` | Starts a job (`GepaOptimizeRequest`: `maxGenerations` default 5, `populationSize` default 8, `target` default `"system_prompt"`). Returns `202 Accepted` + `GepaStartResponse(jobId)`, or `409 Conflict` + the currently running job if one exists. |
| `GET` | `/api/v1/gepa/jobs/{id}` | Returns a single `GepaJobResponse`, or `404` if not found. |
| `GET` | `/api/v1/gepa/jobs` | Lists all jobs, most recent first. |
| `GET` | `/api/v1/gepa/current` | Returns the currently running job, or `204 No Content` if none. |

Request/response DTOs live in `api/src/commonMain/kotlin/dev/promethe/api/AgentModels.kt`
(`GepaOptimizeRequest`, `GepaResultDto`, `GepaStartResponse`, `GepaJobResponse`,
`GenerationSnapshot`).

---

## UI — GEPA dashboard

File: `composeApp/src/commonMain/kotlin/dev/promethe/app/screens/StatsScreen.kt` (view) and
`composeApp/src/commonMain/kotlin/dev/promethe/app/screens/viewmodel/StatsViewModel.kt` (state).

The Stats screen has an "Optimisation de Prompt (GEPA)" section with a single button
(`stats_gepa_optimize` test tag) that triggers `StatsViewModel.runGepaOptimization()`:

1. Sets `isOptimizing = true` and calls `client.startGepaOptimization()` to get a `jobId`.
2. Polls `client.getGepaJobStatus(jobId)` every 2 seconds.
3. On `"completed"`, stores the result in `state.gepaResult` and clears `isOptimizing`.
4. On `"failed"`, logs a warning and clears `isOptimizing` (no error surfaced to the user beyond
   that).

When a result is present, a card renders: accuracy, improvement (`+X%`), generation count,
candidates evaluated, and a preview of the best prompt (`bestPromptPreview`, truncated to 200 chars
server-side). Currently this only exercises the default `"system_prompt"` target — the UI has no
control to target a specific skill or profile, even though the API supports it.

---

## Limitations

- **Two divergent implementations.** `GepaEngine` (system prompt) and `GepaEvolver` (skills) both
  use "GEPA / Pareto" vocabulary but differ in evaluation strategy (single-output scoring vs.
  pairwise LLM judging), dominance criteria, and don't share a Pareto selector implementation.
- **Token counting is a heuristic.** `GepaEvaluator.estimateTokens` is `text.length / 4.0` — not a
  real tokenizer, so token-count-based Pareto dominance is approximate.
- **`ParetoSelector` is explicitly non-scaling.** Brute-force `O(n²)` sorting is documented as fine
  only for population sizes in the 8–20 range.
- **LLM-judge scoring is inherently noisy.** Both `GepaEvaluator.llmJudge` and
  `GepaEvolver.evaluatePairwise` fall back to a neutral `0.5` on parse failure or exception, which
  can mask evaluation errors as mediocre-but-valid scores.
- **`optimizeSystemPrompt`'s test cases are shallow.** Real test cases only carry `userInput` and a
  generic `expectedBehavior` derived from the last 10 sessions' user turns, with no
  `expectedOutput` — evaluation always falls through to the soft `LLM_JUDGE` path.
- **Manual skill optimization has no failure data.** `GepaJobManager.optimizeSkill` calls
  `evolver.evolve(skillName, emptyList())`; since `reflectAndMutate` returns `null` without a
  failure summary, on-demand skill optimization may produce zero reflective mutations.
- **`GepaScheduler` status/history isn't exposed over HTTP.** `getStatus()` /
  `getEvolutionHistory()` exist but no route surfaces them — only `GepaJobManager`'s job-based API
  is reachable from the UI.
- **In-memory state doesn't survive restarts.** Both `GepaJobManager`'s job map and
  `GepaScheduler`'s trajectory buffer/evolution history have no persistence.
- **The UI only targets the system prompt.** `GepaOptimizeRequest.target` supports `skill:<name>`
  and `profile:<id>`, but `StatsScreen`/`StatsViewModel` always call the default target with no way
  to pick another one from the dashboard.
