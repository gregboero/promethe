---
name: jupyter-live-kernel
description: "Iterative Python via live Jupyter kernel (hamelnb)."
source: BUNDLED
requires_cli: uv,jupyter-lab
platforms: jvm
---

# Jupyter Live Kernel (hamelnb)

Gives you a **stateful Python REPL** via a live Jupyter kernel. Variables persist across executions. Use this instead of one-shot scripts when you need to build up state incrementally, explore APIs, inspect DataFrames, or iterate on complex code.

## When to Use This vs Other Tools

| Tool | Use When |
|------|----------|
| **This skill** | Iterative exploration, state across steps, data science, ML, "let me try this and check" |
| `execute_command` | One-shot scripts, shell commands, builds, installs, git, process management |
| `delegate_task` | Offloading independent sub-tasks to other agents |

**Rule of thumb:** If you'd want a Jupyter notebook for the task, use this skill.

## Prerequisites

1. **uv** must be installed: `execute_command(command="which", args=["uv"])`
2. **JupyterLab** must be installed: `execute_command(command="uv", args=["tool", "install", "jupyterlab"])`
3. A Jupyter server must be running (see Setup below)

## Setup

The hamelnb script location:
```
SCRIPT="$HOME/.agent-skills/hamelnb/skills/jupyter-live-kernel/scripts/jupyter_live_kernel.py"
```

If not cloned yet:
```
execute_command(command="git", args=["clone", "https://github.com/hamelsmu/hamelnb.git", "$HOME/.agent-skills/hamelnb"])
```

### Starting JupyterLab

Check if a server is already running:
```
execute_command(command="uv", args=["run", "$SCRIPT", "servers"])
```

If no servers found, start one:
```
execute_command(command="jupyter-lab", args=[
  "--no-browser", "--port=8888", "--notebook-dir=$HOME/notebooks",
  "--IdentityProvider.token=", "--ServerApp.password="
])
```

Note: Token/password disabled for local agent access. The server runs headless.

### Creating a Notebook for REPL Use

If you just need a REPL (no existing notebook), create a minimal notebook file:

```
execute_command(command="mkdir", args=["-p", "$HOME/notebooks"])
```

Write a minimal .ipynb JSON file with one empty code cell using `file_write`, then start a kernel session via the Jupyter REST API:

```
http_fetch(
  url="http://127.0.0.1:8888/api/sessions",
  method="POST",
  body={
    "path": "scratch.ipynb",
    "type": "notebook",
    "name": "scratch.ipynb",
    "kernel": {"name": "python3"}
  }
)
```

## Core Workflow

All commands return structured JSON. Always use `--compact` to save tokens.

### 1. Discover servers and notebooks

```
execute_command(command="uv", args=["run", "$SCRIPT", "servers"])
execute_command(command="uv", args=["run", "$SCRIPT", "sessions", "--server-url", "http://127.0.0.1:8888"])
```

### 2. Execute code in a kernel

```
execute_command(command="uv", args=["run", "$SCRIPT", "execute",
  "--server-url", "http://127.0.0.1:8888",
  "--kernel-id", "KERNEL_ID",
  "--code", "import pandas as pd; df = pd.read_csv('data.csv'); df.head()",
  "--compact"])
```

### 3. Execute multiple cells

```
execute_command(command="uv", args=["run", "$SCRIPT", "execute",
  "--server-url", "http://127.0.0.1:8888",
  "--kernel-id", "KERNEL_ID",
  "--code", "import numpy as np",
  "--compact"])

execute_command(command="uv", args=["run", "$SCRIPT", "execute",
  "--server-url", "http://127.0.0.1:8888",
  "--kernel-id", "KERNEL_ID",
  "--code", "arr = np.random.randn(100); print(arr.mean(), arr.std())",
  "--compact"])
```

Variables from the first cell persist into the second — that's the whole point.

### 4. Check kernel status

```
execute_command(command="uv", args=["run", "$SCRIPT", "status",
  "--server-url", "http://127.0.0.1:8888",
  "--kernel-id", "KERNEL_ID"])
```

### 5. Restart kernel (clear state)

```
execute_command(command="uv", args=["run", "$SCRIPT", "restart",
  "--server-url", "http://127.0.0.1:8888",
  "--kernel-id", "KERNEL_ID"])
```

## Tips

- **Rich output:** The kernel returns stdout, stderr, and rich outputs (HTML tables, images). Parse the JSON response accordingly.
- **Long-running cells:** Use `--timeout` to extend the default execution timeout for heavy computations.
- **Multiple kernels:** You can run multiple notebooks/kernels simultaneously — track kernel IDs to route code to the right session.
- **Install packages:** Run `!pip install package_name` as code in the kernel, or use `execute_command` to install via `uv`.
