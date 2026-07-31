---
name: codex
description: "Delegate coding tasks to OpenAI Codex CLI — features, PRs, refactoring."
source: BUNDLED
requires_cli: codex
platforms: jvm
---

# Codex CLI — Promethe Orchestration Guide

Delegate coding tasks to [Codex](https://github.com/openai/codex) (OpenAI's autonomous coding agent CLI) via Promethe's `execute_command` tool.

## Prerequisites

- **Install:** `npm install -g @openai/codex`
- **Auth:** either `OPENAI_API_KEY` or Codex OAuth credentials from `codex login`
- **Must run inside a git repository** — Codex refuses to run outside one

## One-Shot Tasks (PREFERRED)

```
execute_command(command="codex", args=["exec", "Add dark mode toggle to settings"])
```

For scratch work (Codex needs a git repo):
```
execute_command(command="bash", args=["-c", "cd $(mktemp -d) && git init && codex exec 'Build a snake game in Python'"])
```

## Full Auto Mode

For autonomous operation with file write approval:
```
execute_command(command="codex", args=["exec", "--full-auto", "Refactor the auth module"])
```

## Key Flags

| Flag | Effect |
|------|--------|
| `exec "prompt"` | One-shot execution, exits when done |
| `--full-auto` | Sandboxed but auto-approves file changes in workspace |
| `--yolo` | No sandbox, no approvals (fastest, most dangerous) |
| `--sandbox danger-full-access` | Full filesystem access |
| `--model <name>` | Select model (default: latest GPT) |

## Integration with Promethe

When delegating to Codex:
1. Ensure the working directory is a git repository
2. Use `execute_command` with appropriate timeout (coding tasks can take minutes)
3. Capture the output and relay results to the user
4. For long tasks, consider using `delegate_task` to run asynchronously
