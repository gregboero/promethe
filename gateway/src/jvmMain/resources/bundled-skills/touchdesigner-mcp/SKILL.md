---
name: touchdesigner-mcp
description: "Control a running TouchDesigner instance via twozero MCP — create operators, set parameters, wire connections, execute Python, build real-time visuals."
source: BUNDLED
platforms: jvm
---

# TouchDesigner Integration (twozero MCP)

## CRITICAL RULES

1. **NEVER guess parameter names.** Call `td_get_par_info` for the op type FIRST.
2. **If `tdAttributeError` fires, STOP.** Call `td_get_operator_info` on the failing node before continuing.
3. **NEVER hardcode absolute paths** in script callbacks. Use `me.parent()` / `scriptOp.parent()`.
4. **Prefer native MCP tools over td_execute_python.** Use `td_create_operator`, `td_set_operator_pars`, `td_get_errors` etc. Only fall back to `td_execute_python` for complex multi-step logic.
5. **Call `td_get_hints` before building.** It returns patterns specific to the op type you're working with.

## Architecture

```
Promethe Agent -> MCP (Streamable HTTP) -> twozero.tox (port 40404) -> TD Python
```

36 native tools. Free plugin (no payment/license).
Context-aware (knows selected OP, current network).
Hub health check: `GET http://localhost:40404/mcp` returns JSON with instance PID, project name, TD version.

## Setup

1. Download twozero.tox from the official releases
2. **Drag `twozero.tox` into the TD network editor** → click Install
3. **Enable MCP:** click twozero icon → Settings → mcp → "auto start MCP" → Yes
4. Verify connection: use `execute_command(command="nc", args=["-z", "127.0.0.1", "40404"])` or equivalent network test

## Environment Notes

- **Non-Commercial TD** caps resolution at 1280×1280. Use `outputresolution = 'custom'` and set width/height explicitly.
- **Codecs:** `prores` (preferred on macOS) or `mjpa` as fallback. H.264/H.265/AV1 require a Commercial license.
- Always call `td_get_par_info` before setting params — names vary by TD version.

## Workflow

### Step 0: Discover (before building anything)

```
Call td_get_par_info with op_type for each type you plan to use.
Call td_get_hints with the topic you're building (e.g. "glsl", "audio reactive", "feedback").
Call td_get_focus to see where the user is and what's selected.
Call td_get_network to see what already exists.
```

### Step 1: Clean + Build

**IMPORTANT: Split cleanup and creation into SEPARATE MCP calls.** Destroying and recreating same-named nodes in one `td_execute_python` script causes "Invalid OP object" errors.

Use `td_create_operator` for each node:

```
td_create_operator(type="noiseTOP", parent="/project1", name="bg", parameters={"resolutionw": 1280, "resolutionh": 720})
td_create_operator(type="levelTOP", parent="/project1", name="brightness")
td_create_operator(type="nullTOP", parent="/project1", name="out")
```

For bulk creation or wiring, use `td_execute_python`:

```python
root = op('/project1')
nodes = []
for name, optype in [('bg', noiseTOP), ('fx', levelTOP), ('out', nullTOP)]:
    n = root.create(optype, name)
    nodes.append(n.path)
for i in range(len(nodes)-1):
    op(nodes[i]).outputConnectors[0].connect(op(nodes[i+1]).inputConnectors[0])
result = {'created': nodes}
```

### Step 2: Set Parameters

Prefer the native tool (validates params, won't crash):

```
td_set_operator_pars(path="/project1/bg", parameters={"roughness": 0.6, "monochrome": true})
```

For expressions or modes, use `td_execute_python`:

```python
op('/project1/time_driver').par.colorr.expr = "absTime.seconds % 1000.0"
```

### Step 3: Wire

Use `td_execute_python` — no native wire tool exists:

```python
op('/project1/bg').outputConnectors[0].connect(op('/project1/fx').inputConnectors[0])
```

### Step 4: Verify

```
td_get_errors(path="/project1", recursive=true)
td_get_perf()
td_get_operator_info(path="/project1/out", detail="full")
```

### Step 5: Display / Capture

```
td_get_screenshot(path="/project1/out")
```

## MCP Tool Quick Reference

**Core (use these most):**
| Tool | What |
|------|------|
| `td_execute_python` | Run arbitrary Python in TD. Full API access. |
| `td_create_operator` | Create node with params + auto-positioning |
| `td_set_operator_pars` | Set params safely (validates, won't crash) |
| `td_get_operator_info` | Inspect one node: connections, params, errors |
| `td_get_operators_info` | Inspect multiple nodes in one call |
| `td_get_network` | See network structure at a path |
| `td_get_errors` | Find errors/warnings recursively |
| `td_get_par_info` | Get param names for an OP type (replaces discovery) |
| `td_get_hints` | Get patterns/tips before building |
| `td_get_focus` | What network is open, what's selected |

**Read/Write:**
| Tool | What |
|------|------|
| `td_read_dat` | Read DAT text content |
| `td_write_dat` | Write/patch DAT content |
| `td_read_chop` | Read CHOP channel values |
| `td_read_textport` | Read TD console output |

**Visual:**
| Tool | What |
|------|------|
| `td_get_screenshot` | Capture one OP viewer to file |
| `td_get_screenshots` | Capture multiple OPs at once |
| `td_get_screen_screenshot` | Capture actual screen via TD |
| `td_navigate_to` | Jump network editor to an OP |

**Search:**
| Tool | What |
|------|------|
| `td_find_op` | Find ops by name/type across project |
| `td_search` | Search code, expressions, string params |

**System:**
| Tool | What |
|------|------|
| `td_get_perf` | Performance profiling (FPS, slow ops) |
