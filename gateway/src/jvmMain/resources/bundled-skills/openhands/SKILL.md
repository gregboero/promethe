---
name: openhands
description: "Delegate coding to OpenHands autonomous coding agent (formerly OpenDevin)."
source: BUNDLED
requires_cli: openhands
platforms: jvm
---

# OpenHands — Promethe Orchestration Guide

Delegate coding tasks to [OpenHands](https://github.com/All-Hands-AI/OpenHands) (formerly OpenDevin) — an open-source autonomous coding agent with browser, terminal, and file editing capabilities.

## Prerequisites

- **Install via Docker (recommended):**
  ```
  docker pull ghcr.io/all-hands-ai/openhands:latest
  ```
- **Install CLI:** `pip install openhands` or `pipx install openhands`
- **Auth:** set LLM provider key (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, etc.)

## One-Shot Tasks

```
execute_command(command="openhands", args=["run", "--task", "Add authentication middleware to the Express server"])
```

## Docker Mode (Sandboxed)

```
execute_command(command="docker", args=["run", "--rm", "-v", "./:/workspace", "ghcr.io/all-hands-ai/openhands", "run", "--task", "Fix all TypeScript errors"])
```

## Key Features

| Feature | Description |
|---------|-------------|
| **Browser** | Can browse the web, fill forms, click buttons |
| **Terminal** | Full shell access for running commands |
| **File Editor** | Read, write, search files |
| **Sandboxed** | Runs in Docker container for safety |
| **Multi-LLM** | Supports Claude, GPT-4, Gemini, local models |

## When to Choose OpenHands

- When you need **sandboxed execution** (Docker isolation)
- When tasks require **browser interaction** (web testing, scraping)
- When you want an **open-source** solution
- For complex multi-step autonomous tasks
