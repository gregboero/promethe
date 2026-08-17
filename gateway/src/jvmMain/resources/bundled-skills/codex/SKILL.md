---
name: codex
description: "Delegate repository work to the authenticated local Codex CLI."
source: BUNDLED
requires_cli: codex
platforms: jvm
---

# Codex local delegation

Use `codex_delegate` for focused coding, review, debugging, and repository tasks.
The tool is present only when Promethe detects a runnable, authenticated Codex CLI.

## Arguments

- `task`: the complete task and acceptance criteria.
- `accessMode`: `READ_ONLY` for analysis and review; `WORKSPACE_WRITE` only when edits are required.
- `externalSessionId`: optional Codex thread id returned by an earlier delegation.

Promethe sends the task over stdin to `codex app-server`; never call Codex through
`execute_command`, `shell`, or a shell wrapper. Promethe owns approval relay,
workspace confinement, cancellation, and session mapping. Dangerous Codex modes
such as `--yolo` and `danger-full-access` are forbidden.

After the tool returns, inspect its summary and synthesize the final answer inside
the current Promethe agent loop. Use `delegate_task` only for Promethe subagents,
not for Codex.
