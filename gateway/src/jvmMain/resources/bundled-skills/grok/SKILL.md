---
name: grok
description: "Delegate coding tasks to xAI's Grok CLI coding agent."
source: BUNDLED
requires_cli: grok
platforms: jvm
---

# Grok CLI — Promethe Orchestration Guide

Delegate coding tasks to [Grok](https://x.ai/) (xAI's autonomous coding agent CLI).

## Prerequisites

- **Install:** `npm install -g @xai/grok-cli`
- **Auth:** set `XAI_API_KEY` or run `grok auth login`

## One-Shot Tasks

```
execute_command(command="grok", args=["run", "Add input validation to all form handlers"])
```

## Key Flags

| Flag | Effect |
|------|--------|
| `run "prompt"` | One-shot execution |
| `--auto` | Auto-approve changes |
| `--model <name>` | Select model variant |

## When to Choose Grok

- When using xAI's models (Grok-3, Grok-3 Mini)
- When you want real-time web search integrated with coding
- As a multi-provider option in Promethe's agent fleet
