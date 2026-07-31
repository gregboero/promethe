---
name: node-inspect-debugger
description: "Debug Node.js via --inspect + Chrome DevTools Protocol CLI."
source: BUNDLED
requires_cli: node
platforms: jvm
---

# Node.js Inspect Debugger — Promethe Guide

Drive Node's built-in V8 inspector programmatically. Get real breakpoints, step in/over/out, call-stack walking, scope dumps, and expression evaluation.

## When to Use

- A Node test fails and you need to see intermediate state
- You need to inspect a value in a closure that `console.log` can't reach
- Perf: capture a CPU profile or heap snapshot
- **Don't use for:** things `console.log` solves in under a minute

## Quick Start: `node inspect`

```
execute_command(command="node", args=["inspect", "path/to/script.js"])
```

With TypeScript via tsx:
```
execute_command(command="node", args=["--inspect-brk", "node_modules/.bin/tsx", "path/to/script.ts"])
```

## REPL Commands

| Command | Action |
|---------|--------|
| `c` / `cont` | Continue |
| `n` / `next` | Step over |
| `s` / `step` | Step into |
| `o` / `out` | Step out |
| `sb('file.js', 42)` | Set breakpoint at line 42 |
| `sb('functionName')` | Break when function called |
| `cb('file.js', 42)` | Clear breakpoint |
| `bt` | Backtrace (call stack) |
| `list(5)` | Show 5 lines around current position |
| `watch('expr')` | Watch expression on every pause |
| `repl` | Drop into REPL in current scope |
| `exec expr` | Evaluate expression once |

## Automated Debugging Pattern

For agent-driven debugging without interactive REPL, use CDP (Chrome DevTools Protocol):

```javascript
// debug-script.mjs — run with: node debug-script.mjs
import { createSession } from 'chrome-remote-interface';

const client = await createSession({ port: 9229 });
const { Debugger, Runtime } = client;

await Debugger.enable();
await Debugger.setBreakpointByUrl({
  lineNumber: 41,
  urlRegex: 'auth\\.js$'
});

Debugger.on('paused', async ({ callFrames }) => {
  const frame = callFrames[0];
  const { result } = await Debugger.evaluateOnCallFrame({
    callFrameId: frame.callFrameId,
    expression: 'JSON.stringify({ user, token })'
  });
  console.log('State at breakpoint:', result.value);
  await Debugger.resume();
});
```

## Integration with Promethe

1. Start the target with `--inspect-brk`: `execute_command(command="node", args=["--inspect-brk", "app.js"])`
2. Connect via CDP script: `execute_command(command="node", args=["debug-script.mjs"])`
3. Parse the captured state from stdout
4. Use `file_read` to correlate with source code
