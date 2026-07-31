---
name: claude-code
description: "Delegate coding tasks to Claude Code CLI — features, PRs, reviews, refactoring."
source: BUNDLED
requires_cli: claude
platforms: jvm
---

# Claude Code — Promethe Orchestration Guide

Delegate coding tasks to [Claude Code](https://docs.anthropic.com/en/docs/claude-code) (Anthropic's autonomous coding agent CLI) via Promethe's `execute_command` tool. Claude Code v2.x can read files, write code, run shell commands, spawn subagents, and manage git workflows autonomously.

## Prerequisites

- **Install:** `npm install -g @anthropic-ai/claude-code`
- **Auth:** run `claude` once to log in (browser OAuth for Pro/Max, or set `ANTHROPIC_API_KEY`)
- **Console auth:** `claude auth login --console` for API key billing
- **Health check:** `claude doctor`
- **Version check:** `claude --version` (requires v2.x+)

## Promethe Integration

### Mode 1: Print Mode (`-p`) — PREFERRED for most tasks

Print mode runs a one-shot task, returns the result, and exits. Use via `execute_command`:

```
execute_command(command="claude", args=["-p", "Add error handling to all API calls in src/", "--allowedTools", "Read,Edit", "--max-turns", "10"])
```

**When to use print mode:**
- One-shot coding tasks (fix a bug, add a feature, refactor)
- CI/CD automation and scripting
- Structured data extraction with `--json-schema`
- Any task where you don't need multi-turn conversation

**Print mode skips ALL interactive dialogs** — no workspace trust prompt, no permission confirmations. Ideal for automation.

### Mode 2: Interactive (when needed)

For multi-turn sessions, use `execute_command` with longer timeouts:

```
execute_command(command="claude", args=["--dangerously-skip-permissions", "-p", "Refactor auth module then add tests", "--max-turns", "20"])
```

## CLI Subcommands Reference

| Subcommand | Purpose |
|------------|---------|
| `claude -p "query"` | Print mode (non-interactive, exits when done) |
| `claude -c` | Continue most recent conversation in this directory |
| `claude -r "id"` | Resume a specific session by ID |
| `claude mcp add <name> -- <cmd>` | Add an MCP server |
| `claude mcp list` | List configured MCP servers |
| `claude doctor` | Health check |
| `claude update` | Update to latest version |

## Structured JSON Output

```
execute_command(command="claude", args=["-p", "Analyze auth.py for security issues", "--output-format", "json", "--max-turns", "5"])
```

Returns JSON with: `type`, `subtype` (success/error), `result`, `session_id`, `num_turns`, `total_cost_usd`, `duration_ms`.

## Piped Input Patterns

```
execute_command(command="bash", args=["-c", "cat src/auth.py | claude -p 'Review this code for bugs' --max-turns 1"])
execute_command(command="bash", args=["-c", "git diff HEAD~3 | claude -p 'Summarize these changes' --max-turns 1"])
```

## Session Continuation

```
# Resume most recent session
execute_command(command="claude", args=["-p", "Continue and add connection pooling", "--continue", "--max-turns", "5"])

# Fork a session
execute_command(command="claude", args=["-p", "Try a different approach", "--resume", "<id>", "--fork-session", "--max-turns", "10"])
```

## Key CLI Flags

### Session & Environment
| Flag | Effect |
|------|--------|
| `-p, --print` | Non-interactive one-shot mode |
| `-c, --continue` | Resume most recent conversation |
| `-r, --resume <id>` | Resume specific session |
| `--add-dir <paths>` | Grant access to additional directories |

### Model & Performance
| Flag | Effect |
|------|--------|
| `--model <alias>` | `sonnet`, `opus`, `haiku`, or full name |
| `--effort <level>` | `low`, `medium`, `high`, `max`, `auto` |
| `--max-turns <n>` | Limit agentic loops (prevents runaway) |
| `--max-budget-usd <n>` | Cap API spend in dollars |
| `--fallback-model <model>` | Auto-fallback when overloaded |

### Permission & Safety
| Flag | Effect |
|------|--------|
| `--dangerously-skip-permissions` | Auto-approve ALL tool use |
| `--permission-mode <mode>` | `default`, `acceptEdits`, `plan`, `auto` |
| `--allowedTools <tools>` | Whitelist specific tools |
