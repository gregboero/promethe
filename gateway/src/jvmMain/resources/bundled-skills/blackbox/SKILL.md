---
name: blackbox
description: "Delegate coding tasks to Blackbox AI multi-model agent."
source: BUNDLED
requires_cli: blackbox
platforms: jvm
---

# Blackbox AI — Promethe Orchestration Guide

Delegate coding tasks to [Blackbox](https://www.blackbox.ai/) — a multi-model AI coding agent supporting GPT-4, Claude, Gemini, and local models.

## Prerequisites

- **Install:** `npm install -g @blackbox-ai/cli`
- **Auth:** run `blackbox auth login` or set `BLACKBOX_API_KEY`

## One-Shot Tasks

```
execute_command(command="blackbox", args=["run", "Optimize database queries in src/db/"])
```

## Key Flags

| Flag | Effect |
|------|--------|
| `run "prompt"` | One-shot execution |
| `--model <name>` | Select specific model |
| `--auto-commit` | Auto-commit changes |

## When to Choose Blackbox

- Multi-model routing (automatically picks the best model for the task)
- Code generation with built-in web search context
- As a cost-effective alternative to Claude Code or Codex
