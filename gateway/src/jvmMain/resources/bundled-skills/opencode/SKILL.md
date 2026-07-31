---
name: opencode
description: "Delegate coding tasks to OpenCode CLI — open-source multi-provider coding agent."
source: BUNDLED
requires_cli: opencode
platforms: jvm
---

# OpenCode CLI — Promethe Orchestration Guide

Delegate coding tasks to [OpenCode](https://github.com/opencode-ai/opencode) — an open-source autonomous coding agent that supports multiple LLM providers (Anthropic, OpenAI, Google, local models via Ollama).

## Prerequisites

- **Install:** `npm install -g opencode` or `go install github.com/opencode-ai/opencode@latest`
- **Auth:** configure provider API key (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GOOGLE_API_KEY`, etc.)
- **Must run inside a git repository**

## One-Shot Tasks

```
execute_command(command="opencode", args=["run", "Add error handling to all API endpoints"])
```

## Key Flags

| Flag | Effect |
|------|--------|
| `run "prompt"` | One-shot execution |
| `--provider <name>` | Select LLM provider (anthropic, openai, google, ollama) |
| `--model <name>` | Select specific model |
| `--auto-approve` | Auto-approve file changes |

## When to Choose OpenCode

- When you want to use **non-Anthropic/non-OpenAI models** (Google Gemini, local Ollama)
- When you need an **open-source** coding agent
- When cost optimization matters (can use cheaper models)
- As a **fallback** when Claude Code or Codex are unavailable

## Integration with Promethe

1. Use `execute_command` to delegate coding tasks
2. Specify the model provider based on available API keys
3. For multi-provider routing, Promethe's `CapabilityRouter` can select the best backend
