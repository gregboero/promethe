# Plugin System

## Overview

Plugins extend Promethe's capabilities without modifying the core system. They can add tools, intercept events via hooks, or expose new API routes.

**Directory**: `~/.promethe/plugins/`

> **LAB and local-only:** plugins are disabled by default. The local process
> owner must set `PROMETHE_ENABLE_LAB_PLUGINS=true` before startup. A manifest
> with `"enabled": true` cannot activate the runtime by itself, and the remote
> API cannot enable plugins. Executable hooks require mandatory approval and
> run only through the native sandbox.

### Main components

| Component | Role |
|---|---|
| `PluginLoader.kt` | Discovers and loads plugins from the directory |
| `HookManager` | Central hook registry — dispatches events to plugins |
| `HotReloadWatcher` | Watches the filesystem and reloads modified plugins |

---

## Structure of a plugin

Each plugin is a subdirectory of `~/.promethe/plugins/`:

```
~/.promethe/plugins/
└── my-plugin/
    ├── plugin.json          # Manifest (metadata, configuration)
    ├── hooks/               # Registered hooks
    │   ├── on-message.kt    # Hook triggered on every message
    │   └── on-tool-call.kt  # Hook triggered before a tool call
    ├── tools/               # Additional tools exposed to agents
    │   └── my-tool.json     # Tool schema
    └── resources/           # Auxiliary files
```

### `plugin.json` manifest

```json
{
  "name": "my-plugin",
  "version": "1.0.0",
  "description": "Short description of the plugin",
  "author": "author",
  "hooks": ["on-message", "on-tool-call"],
  "enabled": true
}
```

| Field | Required | Description |
|---|---|---|
| `name` | ✅ | Unique plugin identifier |
| `version` | ✅ | Semantic version |
| `description` | ✅ | Short description |
| `hooks` | ❌ | List of declared hooks |
| `enabled` | ❌ | Manifest preference (default: `true`); effective only when the local LAB runtime opt-in is enabled |

---

## Hooks

Hooks allow plugins to intercept and modify the execution flow.

### Lifecycle

1. `PluginLoader.discover()` — Scans `~/.promethe/plugins/`, rejects escaping names/paths, and identifies valid plugins.
2. The local `PROMETHE_ENABLE_LAB_PLUGINS=true` opt-in combines with the manifest's `enabled` field.
3. `PluginLoader.loadAndRegisterHooks(hookManager)` — Registers structured executable hooks only when sandbox and approval services are present.
4. The `HookManager` dispatches events to registered hooks in priority order.

### Available hooks

| Hook | Trigger | Can modify |
|---|---|---|
| `on-message` | Receipt of a user message | Message content |
| `on-tool-call` | Before a tool executes | Parameters, authorization |
| `on-response` | Before sending the response | Response content |
| `on-error` | Error during processing | Error handling |
| `on-session-start` | Start of a new session | Initial configuration |
| `on-session-end` | End of a session | Cleanup |

### Hook example

```kotlin
// hooks/on-tool-call.kt
fun onToolCall(context: HookContext): HookResult {
    val toolName = context.get<String>("toolName")
    if (toolName == "run_command") {
        logger.info("Intercepted command: ${context.get<String>("command")}")
    }
    return HookResult.Continue
}
```

---

## Hot-reload

`HotReloadWatcher` continuously watches the `~/.promethe/plugins/` directory:

- **Addition** of a new plugin → automatic discovery and loading.
- **Modification** of a file → reload of the affected plugin.
- **Removal** → unregistration of the associated hooks.

Reloading is transparent: ongoing sessions continue with the new hooks without interruption.

---

## API Reference

Base: `/api/v1/plugins`

| Method | Route | Description |
|---|---|---|
| `GET` | `/api/v1/plugins` | Lists all plugins and their status |
| `GET` | `/api/v1/plugins/:name` | Details of a plugin |
| `POST` | `/api/v1/plugins/:name/toggle` | Validates a requested toggle but currently returns `501 Not Implemented`; runtime enable/disable is not available yet. |

There are currently no API endpoints to delete, reload, enable, or disable a plugin at runtime. Plugin
discovery and filesystem hot reload remain local runtime behavior, not remotely mutable management actions.

### Example response for `GET /api/v1/plugins`

```json
[
  {
    "name": "my-plugin",
    "version": "1.0.0",
    "enabled": true,
    "hooks": ["on-message", "on-tool-call"],
    "status": "loaded"
  }
]
```
