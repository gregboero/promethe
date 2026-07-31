# Task Scheduler

> Scheduled and recurring tasks: CRUD, cron matching, and the `cronjob` agent tool.

## Overview

Promethe can run tasks on a schedule — recurring cron jobs (e.g. "every day at 9am") or
one-shot tasks (run once at a given epoch timestamp). A scheduled task is a stored
`(cron expression | runAt, prompt, profileId, enabled)` tuple that is dispatched to the
agent loop through the same A2A execution pipeline used by chat, webhooks, and goals.

Scheduled tasks can be created three ways:
- By the agent itself, via the `cronjob` tool (self-scheduling).
- By a human, via the REST API (`/api/v1/scheduler/tasks`).
- By a human, via the desktop UI's **Scheduler** screen.

---

## Architecture

Scheduling is split across two modules, matching the CRUD/execution boundary used
elsewhere in Promethe:

| Component | Module | Responsibility |
|---|---|---|
| `TaskScheduler` | `shared/src/jvmMain/kotlin/dev/promethe/core/TaskScheduler.kt` | CRUD operations and cron utilities only: `schedule()`, `listJobs()`, `cancel()`, `getTaskCount()`, plus the static cron matcher/next-run calculator used by both the tool and the executor. |
| `TaskExecutor` | `gateway/src/jvmMain/kotlin/dev/promethe/gateway/TaskExecutor.kt` | The runtime tick loop that polls the database, determines which tasks are due, and executes them via `A2AInternalClient`. |

This split is deliberate: `TaskScheduler` has no knowledge of `A2AInternalClient` or the
agent loop — it only reads/writes `ScheduledTaskRow` rows and does cron math. All actual
task *execution* (calling the agent, updating `lastRunAt`/`nextRunAt`/`lastRunStatus`) lives
in the gateway's `TaskExecutor`, so scheduled runs go through the same unified pipeline as
chat, webhooks, goals, and ACP invoke (`A2AInternalClient.execute(sessionId, prompt,
channelHint)`).

`TaskExecutor` is instantiated and started in `OmnichannelGateway.kt`:

```kotlin
val a2aClient = A2AInternalClient(agent, database)
val taskExecutor = TaskExecutor(database, a2aClient)
if (taskScheduler != null) {
    taskExecutor.start()
}
```

Its tick loop runs every 30 seconds (`gateway/.../TaskExecutor.kt`):

```kotlin
job = scope.launch {
    while (isActive) {
        try { tick() } catch (e: Exception) { logger.error(e) { "Error during scheduler tick" } }
        delay(30_000) // Check every 30 seconds
    }
}
```

On each tick, `TaskExecutor` loads all enabled tasks (`database.getEnabledScheduledTasks()`)
and, for each one, calls `shouldRun(task, now)`:
- **One-shot tasks** (`runAt != null`): due when `now >= runAt`.
- **Recurring tasks**: due when `nextRunAt` has passed (or is null) *and*
  `TaskScheduler.matchesCron(task.cronExpression, now)` matches the current minute.

When a task runs, `A2AInternalClient.execute()` is invoked with a synthetic session ID
(`"scheduled-${task.id}-$now"`) and `channelHint = "scheduler"`. Afterward:
- One-shot tasks are disabled (`enabled = false`) and get `nextRunAt = null`, regardless of
  success or failure — they only ever run once.
- Recurring tasks get a freshly computed `nextRunAt` via `TaskScheduler.computeNextRun()`
  and keep running on schedule even after a failed execution.
- `lastRunAt` and `lastRunStatus` (`"success"`, `"completed"`, or `"error"`) are recorded in
  both cases.

`TaskExecutor.isRunning()` and `TaskScheduler.getTaskCount()` back the scheduler section of
the `/api/v1/status` dashboard (see `StatusRoutes.kt`), reporting whether the tick loop is
active and how many enabled tasks exist.

---

## Task model

A scheduled task is persisted as a `ScheduledTaskRow` (`shared/.../db/PrometheDatabaseApi.kt`):

| Field | Type | Notes |
|---|---|---|
| `id` | `String` | Unique ID. Agent-created jobs default to `cron_<timestamp>`; API-created tasks default to `task-<timestamp>`. |
| `name` | `String` | Display name. |
| `cronExpression` | `String` | 5-field cron string (`min hour dom month dow`). Empty/unused for pure one-shot tasks created via the API. |
| `prompt` | `String` | The message/instruction sent to the agent when the task fires. |
| `profileId` | `String?` | Optional agent profile to run the task under. |
| `enabled` | `Boolean` | Default `true`. Disabled tasks are skipped by the tick loop. |
| `lastRunAt` / `nextRunAt` | `Long?` | Epoch milliseconds. |
| `lastRunStatus` | `String?` | `"success"`, `"completed"` (one-shot), or `"error"`. |
| `createdAt` | `Long` | Epoch milliseconds. |
| `runAt` | `Long?` | If set, the task is one-shot: it runs once at this epoch-ms timestamp and is then disabled, regardless of `cronExpression`. |

### Cron expression format

Standard 5-field cron (`minute hour day-of-month month day-of-week`), matched in UTC by
`TaskScheduler.matchesCron()`. Supported syntax per field:

- `*` — any value.
- Exact number, e.g. `35`.
- Lists, e.g. `1,3,5`.
- Ranges, e.g. `1-5`.
- Steps, e.g. `*/5` or `1-10/2`.

`day-of-week` uses `0` = Sunday. Expressions that don't split into exactly 5
whitespace-separated fields are rejected (`schedule()` throws; `matchesCron()` returns
`false`).

`TaskScheduler.computeNextRun(expression, fromMs)` scans forward minute-by-minute (starting
at the next full minute after `fromMs`, capped at ~1 year / 525,600 minutes) until it finds a
match, or returns `null` if none is found in that window — e.g. for an impossible expression
like minute `60`.

### Enable / disable

Tasks can be toggled without deleting them:
- `TaskScheduler` only ever creates tasks with `enabled = true`; there is no
  disable/re-enable method on `TaskScheduler` itself (the agent-facing `cronjob` tool only
  supports create/list/delete).
- The HTTP API and UI support toggling via `POST /api/v1/scheduler/tasks/{id}/toggle`, which
  flips the current `enabled` value.
- Disabled tasks are excluded from the executor's tick loop (`getEnabledScheduledTasks()`),
  so they neither run nor advance `nextRunAt`.

---

## Agent tool (`cronjob`)

Defined in `shared/src/jvmMain/kotlin/dev/promethe/core/tools/builtin/AgentTools.kt` as
`CronjobTool`, wrapping `TaskScheduler` so the agent can self-schedule recurring work —
described in the source as matching Hermes' `cronjob` tool.

**Args (`CronjobArgs`):**

| Field | Description |
|---|---|
| `cronExpression` | 5-field cron string, e.g. `*/30 * * * *`. |
| `task` | The task description/prompt to execute on schedule. |
| `jobId` | Optional; auto-generated as `cron_<timestamp>` if blank. |
| `action` | One of `"create"`, `"list"`, `"delete"` (default `"create"`). |

**Behavior:**
- `create` — calls `scheduler.schedule(jobId, cronExpression, task)`; returns a confirmation
  string with the job ID and cron expression, or an `[ERROR]` string if `schedule()` throws
  (e.g. invalid cron format).
- `list` — calls `scheduler.listJobs()` and renders each as `id — cronExpression —
  description`, or `"No scheduled jobs."` if empty.
- `delete` — requires `jobId`; calls `scheduler.cancel(jobId)`, returning success/failure
  text. Returns `[ERROR] jobId required for delete.` if `jobId` is blank.
- Any other `action` value returns an `[ERROR] Unknown action` message.

Tasks created through this tool are always recurring (no `runAt`/one-shot support at the
tool level) and always `enabled = true` at creation.

---

## HTTP API

Base path: `/api/v1/scheduler/tasks` (mounted in `gateway/.../SchedulerRoutes.kt`, only wired up
when a `TaskScheduler` instance is available).

| Method | Route | Description |
|---|---|---|
| `GET` | `/api/v1/scheduler/tasks` | List **all** tasks (enabled and disabled) — `ScheduledTaskListResponse`. |
| `POST` | `/api/v1/scheduler/tasks` | Create a task from a `ScheduledTaskRequest` body. Generates ID `task-<timestamp>`. If `runAt` is set, `nextRunAt = runAt` (one-shot); otherwise `nextRunAt` is computed from `cronExpression` via `TaskScheduler.computeNextRun()`. |
| `GET` | `/api/v1/scheduler/tasks/{id}` | Get one task; `404` if not found. |
| `PUT` | `/api/v1/scheduler/tasks/{id}` | Update `name`, `cronExpression`, `prompt`, `profileId`, `enabled` for an existing task; `404` if not found. Does **not** recompute `nextRunAt`. |
| `DELETE` | `/api/v1/scheduler/tasks/{id}` | Delete a task. |
| `POST` | `/api/v1/scheduler/tasks/{id}/toggle` | Flip `enabled`; `404` if not found. |

**Request body (`ScheduledTaskRequest`):**

```jsonc
{
  "name": "Backup job",
  "cronExpression": "0 0 * * *",   // "" for a pure one-shot task
  "prompt": "Perform database backup",
  "profileId": null,                // optional agent profile
  "enabled": true,
  "runAt": null                      // epoch ms; if set, task is one-shot
}
```

**Response body (`ScheduledTaskResponse`)** mirrors `ScheduledTaskRow`: `id`, `name`,
`cronExpression`, `prompt`, `profileId`, `enabled`, `lastRunAt`, `nextRunAt`,
`lastRunStatus`, `createdAt`, `runAt`.

Example:

```bash
# List all scheduled tasks
curl http://localhost:8080/api/v1/scheduler/tasks

# Create a recurring task
curl -X POST http://localhost:8080/api/v1/scheduler/tasks \
  -H "Content-Type: application/json" \
  -d '{"name":"Daily standup summary","cronExpression":"0 9 * * 1-5","prompt":"Summarize yesterday'\''s commits"}'

# Pause a task
curl -X POST http://localhost:8080/api/v1/scheduler/tasks/task-123/toggle

# Delete a task
curl -X DELETE http://localhost:8080/api/v1/scheduler/tasks/task-123
```

---

## UI

`composeApp/src/commonMain/kotlin/dev/promethe/app/screens/SchedulerScreen.kt` provides a
full CRUD screen (title "Planificateur") backed by `SchedulerViewModel`:

- A `LazyColumn` of task cards, each showing: enabled/paused status, the raw cron
  expression, a human-readable French label (derived client-side by `cronToHumanLabel()`,
  e.g. "Tous les jours à 9h"), the assigned agent profile (if any), the last-run status
  badge (`success`/`completed` → green check, `error` → red cross, `running` → blue), and a
  "next run" countdown chip computed from `nextRunAt`.
- Per-task actions: toggle enable/pause (`POST .../toggle`) and delete.
- A floating "+" button opens a **Create Task** dialog with: name, optional agent profile
  picker, prompt/message field, quick-pick templates (hourly, "every morning 8am", "every
  evening 8pm", every 30 min, weekly Monday 9am, monthly on the 1st), an hour-of-day
  selector, a recurring on/off switch, and an interval+period picker (minutes/hours/days/
  months). The dialog builds the final cron expression client-side
  (`buildCronExpression()`) and previews the next 3 run times before submission.
- One-shot tasks (recurring switch off) are submitted as `0 <hour> * * *`, which the backend
  currently still stores as a cron expression rather than as a `runAt` timestamp — the UI
  does not expose the `runAt` one-shot field from `ScheduledTaskRequest` directly.

---

## Limitations

- **Polling granularity**: the executor ticks every 30 seconds and cron matching only has
  minute resolution, so scheduled tasks fire on their target minute with up to ~30s of
  jitter, not at the exact second.
- **UTC only**: `TaskScheduler.matchesCron()` evaluates all cron fields against
  `LocalDateTime.ofEpochSecond(timestampMs / 1000, 0, ZoneOffset.UTC)` — there is no
  per-task timezone.
- **`computeNextRun` bound**: next-run computation scans forward at most ~1 year
  (525,600 minutes); a cron expression that can never match (e.g. minute `60`) returns
  `null` rather than looping forever.
- **No retry/backoff**: failed recurring tasks simply continue on their normal schedule
  (`lastRunStatus = "error"`); failed one-shot tasks are disabled like successful ones —
  there is no automatic retry.
- **No concurrency guard visible in `TaskExecutor`**: each tick re-evaluates all enabled
  tasks against `now`; a slow-running agent execution does not visibly block or skip the
  next tick's evaluation of the same task beyond what `nextRunAt` enforces.
- **`cronjob` tool has no update/toggle action** — only `create`, `list`, `delete`; pausing
  a task the agent created itself is only possible through the HTTP API/UI toggle endpoint,
  not through the tool.
- **PUT does not recompute `nextRunAt`**: updating a task's `cronExpression` via
  `PUT /api/v1/scheduler/tasks/{id}` updates the stored expression but leaves the existing
  `nextRunAt` in place until the executor naturally recomputes it after the next run.
