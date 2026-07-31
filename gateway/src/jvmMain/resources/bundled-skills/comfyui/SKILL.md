---
name: comfyui
description: "Generate images, video, and audio with ComfyUI — install, launch, manage nodes/models, run workflows with parameter injection."
source: BUNDLED
requires_cli: python3,comfy
platforms: jvm
---

# ComfyUI

Generate images, video, audio, and 3D content through ComfyUI using the official `comfy-cli` for setup/lifecycle and direct REST/WebSocket API for workflow execution.

## When to Use

- User asks to generate images with Stable Diffusion, SDXL, Flux, SD3, etc.
- User wants to run a specific ComfyUI workflow file
- User wants to chain generative steps (txt2img → upscale → face restore)
- User needs ControlNet, inpainting, img2img, or other advanced pipelines
- User asks to manage ComfyUI queue, check models, or install custom nodes
- User wants video/audio/3D generation via AnimateDiff, Hunyuan, Wan, AudioCraft, etc.

## Architecture: Two Layers

```
┌─────────────────────────────────────────────────────┐
│ Layer 1: comfy-cli (official lifecycle tool)        │
│   Setup, server lifecycle, custom nodes, models     │
│   → comfy install / launch / stop / node / model    │
└─────────────────────────┬───────────────────────────┘
                          │
┌─────────────────────────▼───────────────────────────┐
│ Layer 2: REST/WebSocket API + skill scripts         │
│   Workflow execution, param injection, monitoring   │
│   POST /api/prompt, GET /api/view, WS /ws           │
│   → run_workflow.py, run_batch.py, ws_monitor.py    │
└─────────────────────────────────────────────────────┘
```

## Quick Start

### Detect environment

```
execute_command(command="comfy", args=["--version"])
```

Check if server is running:
```
http_fetch(url="http://127.0.0.1:8188/system_stats", method="GET")
```

Hardware check:
```
execute_command(command="python3", args=["scripts/hardware_check.py"])
```

### Health check

```
execute_command(command="python3", args=["scripts/health_check.py"])
```

## Core Workflow

### Step 1: Get a workflow JSON in API format

Workflows must be in API format (each node has `class_type`). Sources:
- ComfyUI web UI → **Workflow → Export (API)**
- This skill's `workflows/` directory
- Community downloads (civitai, Reddit) — usually editor format, re-export needed

### Step 2: See what's controllable

```
execute_command(command="python3", args=["scripts/extract_schema.py", "workflow_api.json", "--summary-only"])
```

### Step 3: Run with parameters

```
execute_command(command="python3", args=[
  "scripts/run_workflow.py",
  "--workflow", "workflow_api.json",
  "--args", "{\"prompt\": \"a beautiful sunset over mountains\", \"seed\": -1, \"steps\": 30}",
  "--output-dir", "./outputs"
])
```

For cloud execution:
```
execute_command(command="python3", args=[
  "scripts/run_workflow.py",
  "--workflow", "workflow_api.json",
  "--args", "{\"prompt\": \"...\"}",
  "--host", "https://cloud.comfy.org",
  "--output-dir", "./outputs"
])
```

### Step 4: Present results

Scripts emit JSON to stdout describing every output file with status, prompt_id, and file paths.

## Decision Tree

| User says | Tool | Command |
|-----------|------|---------|
| **Lifecycle** | | |
| "install ComfyUI" | comfy-cli | `execute_command(command="bash", args=["scripts/comfyui_setup.sh"])` |
| "start ComfyUI" | comfy-cli | `execute_command(command="comfy", args=["launch", "--background"])` |
| "stop ComfyUI" | comfy-cli | `execute_command(command="comfy", args=["stop"])` |
| "install X node" | comfy-cli | `execute_command(command="comfy", args=["node", "install", "<name>"])` |
| "download X model" | comfy-cli | `execute_command(command="comfy", args=["model", "download", "--url", "<url>"])` |
| **Execution** | | |
| "what can I change?" | script | `execute_command(command="python3", args=["scripts/extract_schema.py", "W.json"])` |
| "check deps" | script | `execute_command(command="python3", args=["scripts/check_deps.py", "W.json"])` |
| "fix missing deps" | script | `execute_command(command="python3", args=["scripts/auto_fix_deps.py", "W.json"])` |
| "generate image" | script | `execute_command(command="python3", args=["scripts/run_workflow.py", ...])` |
| "batch generate" | script | `execute_command(command="python3", args=["scripts/run_batch.py", ...])` |

## Scripts Reference

| Script | Purpose |
|--------|---------|
| `hardware_check.py` | Probe GPU/VRAM/disk → recommend local vs Cloud |
| `comfyui_setup.sh` | Hardware check + comfy-cli + ComfyUI install + launch + verify |
| `extract_schema.py` | Read a workflow → list controllable params + model deps |
| `check_deps.py` | Check workflow against running server → list missing nodes/models |
| `auto_fix_deps.py` | Run check_deps then auto-install missing deps |
| `run_workflow.py` | Inject params, submit, monitor, download outputs |
| `run_batch.py` | Submit a workflow N times with sweeps |
| `health_check.py` | Verification checklist runner |
| `fetch_logs.py` | Pull traceback / status messages for a given prompt_id |
