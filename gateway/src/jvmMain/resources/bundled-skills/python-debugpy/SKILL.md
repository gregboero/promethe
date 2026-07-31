---
name: python-debugpy
description: "Debug Python: pdb REPL + debugpy remote (DAP)."
source: BUNDLED
requires_cli: python
platforms: jvm
---

# Python Debugger (pdb + debugpy)

## Overview

Three tools, picked by situation:

| Tool | When |
|---|---|
| **`breakpoint()` + pdb** | Local, interactive, simplest. Add `breakpoint()` in the source, run normally, get a REPL at that line. |
| **`python -m pdb`** | Launch an existing script under pdb with no source edits. Useful for quick poking. |
| **`debugpy`** | Remote / headless / "attach to already-running process." Talks DAP, scriptable from terminal, works for long-lived processes (servers, daemons, workers). |

**Start with `breakpoint()`.** It's the cheapest thing that works.

## When to Use

- A test fails and the traceback doesn't reveal why a value is wrong
- You need to step through a function and watch a collection mutate
- A long-running process misbehaves and you can't restart it
- Post-mortem: an exception fired and you want to inspect locals at the crash site
- A subprocess or worker is the actual bug site

**Don't use for:** things `print()` / `logging.debug` solve in under a minute, or things `pytest -vv --tb=long --showlocals` already reveals.

## pdb Quick Reference

Inside any pdb prompt (`(Pdb)`):

| Command | Action |
|---|---|
| `h` / `h cmd` | help |
| `n` | next line (step over) |
| `s` | step into |
| `r` | return from current function |
| `c` | continue |
| `unt N` | continue until line N |
| `j N` | jump to line N (same function only) |
| `l` / `ll` | list source around current line / full function |
| `w` | where (stack trace) |
| `u` / `d` | move up / down in the stack |
| `a` | print args of the current function |
| `p expr` / `pp expr` | print / pretty-print expression |
| `display expr` | auto-print expr on every stop |
| `b file:line` | set breakpoint |
| `b func` | break on function entry |
| `b file:line, cond` | conditional breakpoint |
| `cl N` | clear breakpoint N |
| `tbreak file:line` | one-shot breakpoint |
| `!stmt` | execute arbitrary Python (assignments included) |
| `interact` | drop into full Python REPL in current scope (Ctrl+D to exit) |
| `q` | quit |

The `interact` command is the most powerful — you can import anything, inspect complex objects, even call methods that mutate state.

## Recipe 1: Local breakpoint

Edit the file using `file_write` or manually:

```python
def compute(x, y):
    result = some_helper(x)
    breakpoint()           # <-- drops into pdb here
    return result + y
```

Run the code normally. You land at the `breakpoint()` line with full access to locals.

**Don't forget to remove `breakpoint()` before committing.** Check with:
```bash
grep -rn 'breakpoint()' --include='*.py'
```

## Recipe 2: Launch a script under pdb (no source edits)

```bash
python -m pdb path/to/script.py arg1 arg2
# Lands at first line of script
(Pdb) b path/to/script.py:42
(Pdb) c
```

## Recipe 3: Debug a pytest test

```bash
# Drop to pdb on failure:
python -m pytest tests/path/to/test_file.py::test_name --pdb

# Drop to pdb at the START of the test:
python -m pytest tests/path/to/test_file.py::test_name --trace

# Show locals in tracebacks without pdb:
python -m pytest tests/path/to/test_file.py --showlocals --tb=long
```

Note: pdb does NOT work with xdist parallel execution. Disable it:
```bash
python -m pytest tests/foo_test.py::test_bar --pdb -p no:xdist
```

## Recipe 4: Post-mortem on any exception

```python
import pdb, sys
try:
    run_the_thing()
except Exception:
    pdb.post_mortem(sys.exc_info()[2])
```

Or wrap a whole script:
```bash
python -m pdb -c continue script.py
# When it crashes, pdb catches it and you're in the frame of the exception
```

## Recipe 5: Remote debug with debugpy (attach to running process)

For long-lived processes: servers, daemons, workers that can't be restarted clean.

### Setup

```bash
pip install debugpy
```

Use `execute_command(command="pip", args=["install", "debugpy"])` to install.

### Pattern A: Source-edit — process waits for debugger at launch

Add near the top of the entry point:

```python
import debugpy
debugpy.listen(("127.0.0.1", 5678))
print("debugpy listening on 5678, waiting for client...", flush=True)
debugpy.wait_for_client()
debugpy.breakpoint()       # optional: pause immediately once attached
```

Start the process; it blocks on `wait_for_client()`.

### Pattern B: No source edit — launch with `-m debugpy`

```bash
python -m debugpy --listen 127.0.0.1:5678 --wait-for-client path/to/script.py
```

### Connect from VS Code or DAP client

In VS Code `launch.json`:
```json
{
  "name": "Attach debugpy",
  "type": "debugpy",
  "request": "attach",
  "connect": {"host": "127.0.0.1", "port": 5678}
}
```

### Connect from terminal (pdb over DAP)

```bash
python -m debugpy --connect 127.0.0.1:5678 --pid <PID>
```

## Tools Reference

| Promethe Tool | Usage in This Skill |
|---------------|-------------------|
| `execute_command` | Run scripts under pdb, install debugpy, launch debug sessions |
| `file_write` | Insert breakpoints or debugpy listeners into source files |
| `file_read` | Inspect source code to decide where to set breakpoints |
