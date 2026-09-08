# A2UI — Agent-to-UI Protocol

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** See [project status / statut du projet](EXPERIMENTAL_STATUS.md).

## Overview

A2UI lets the agent render interactive UI — forms, tables, buttons, cards — directly in the
chat instead of falling back to plain Markdown. It is inspired by
[Flutter Remote Widgets (rfw)](https://pub.dev/packages/rfw): the agent sends a tree of
generic `UiNode`s over the wire, and the client dispatches each node to a registered
Composable renderer by its `type` string. Unknown types degrade gracefully instead of
crashing the chat.

The flow in one sentence: the agent calls the `render_ui` tool with a JSON `UiNode` tree →
the gateway extracts it from the tool output and republishes it as a `ChatEvent` →
the desktop app's `A2UIRegistry` renders it recursively, resolving data bindings against a
local `DynamicContent` store → user interactions (taps, form submits) are sent back to the
agent as actions.

Three `ChatEvent` types carry the protocol (see `api/src/commonMain/kotlin/dev/promethe/api/ChatModels.kt`):

| Event type | Direction | Purpose |
|---|---|---|
| `ui_layout` | agent → client | Full `UiNode` tree to render |
| `ui_data` | agent → client | Data update without resending the layout |
| `ui_action` | client → agent | User interaction (button tap, form submit) |

`ui_clear` also exists as a core A2UI event type for clearing a rendered UI, alongside these three.

---

## Protocol schema

Defined in `api/src/commonMain/kotlin/dev/promethe/api/a2ui/A2UIModels.kt`. Both types are
`@Serializable` and shared (JVM + wasmJs) between the gateway and the Compose UI.

### `UiNode`

```kotlin
data class UiNode(
    val type: String,
    val id: String = "",
    val props: Map<String, JsonElement> = emptyMap(),
    val bindings: Map<String, String> = emptyMap(),
    val handlers: Map<String, ActionDef> = emptyMap(),
    val children: List<UiNode> = emptyList(),
)
```

| Field | Type | Description |
|---|---|---|
| `type` | `String` | Widget type: `"text"`, `"button"`, `"card"`, `"form"`, `"row"`, `"column"`, `"table"`, `"chart"`, `"image"` (open string, not a sealed class — see below) |
| `id` | `String` | Optional unique ID for targeting updates |
| `props` | `Map<String, JsonElement>` | Static properties (label, style, color, etc.) |
| `bindings` | `Map<String, String>` | Data bindings: prop name → data path (e.g. `"text"` → `"user.name"`) |
| `handlers` | `Map<String, ActionDef>` | Event handlers: event name → action definition (e.g. `"onTap"` → `ActionDef`) |
| `children` | `List<UiNode>` | Child nodes for recursive composition |

`type` is intentionally a plain `String`, not a sealed class. This lets the client-side
`A2UIRegistry` be extended with custom renderers without touching this schema — the same
pattern as Flutter rfw's `LocalWidgetLibrary`.

### `ActionDef`

```kotlin
data class ActionDef(
    val action: String,
    val params: Map<String, String> = emptyMap(),
)
```

| Field | Type | Description |
|---|---|---|
| `action` | `String` | Action identifier sent back to the agent (e.g. `"approve"`, `"submit_form"`, `"navigate"`) |
| `params` | `Map<String, String>` | Additional parameters (e.g. `"id"` → `"123"`) |

Both models round-trip through `kotlinx.serialization` JSON and ProtoBuf (see
`A2UISerializationTest.kt`) — ProtoBuf is the smaller wire format but cannot encode
`Map<String, JsonElement>`, so `props` is JSON-only in practice.

---

## The `render_ui` tool

Defined in `shared/src/commonMain/kotlin/dev/promethe/core/tools/builtin/RenderUITool.kt`
(`ai.koog` `SimpleTool`). The agent calls this tool instead of writing Markdown when it needs
user input, structured data display, or interactive elements.

### Arguments (`RenderUIArgs`)

| Field | Type | Description |
|---|---|---|
| `uiTree` | `String` | JSON string of the `UiNode` tree (type, id, props, children, bindings, handlers) |
| `data` | `String?` | Optional JSON object of initial data to populate the UI bindings |

### Output markers

The tool doesn't render anything itself — `execute()` just validates and passes the tree
through as plain text with sentinel markers that a downstream layer parses out:

```
[A2UI] UI tree accepted for rendering.
__a2ui_tree__=<uiTree JSON>
__a2ui_data__=<data JSON>          (only present if `data` was supplied)
```

`PrometheA2AExecutor` (gateway) matches these markers with regex
(`__a2ui_tree__=(.+)` / `__a2ui_data__=(.+)`), strips them plus the `[A2UI] ...` banner out
of the visible response text, and republishes the tree/data as `ChatEvent(type = "ui_layout")`
/ `ChatEvent(type = "ui_data")` metadata instead. The chat text the user sees is the cleaned
response with all three marker lines removed.

---

## Rendering pipeline in the desktop app

Package: `composeApp/src/commonMain/kotlin/dev/promethe/app/a2ui/`.

1. `ChatViewModel` receives the `ui_layout` / `ui_data` `ChatEvent`s and feeds data updates
   into a per-session `DynamicContent` instance (`updateDynamicContent(data: JsonObject)`).
2. `A2UIRegistry.Render(node, data, onAction)` looks up a `Renderer` for `node.type` in an
   internal `MutableMap<String, Renderer>` and invokes it, or falls back if nothing is
   registered.
3. `A2UIRegistry.RenderChildren(node, data, onAction)` recurses into `node.children` — any
   composite renderer (card, row, column, form, table) calls this to render its contents.
4. `registerBuiltInRenderers()` (in `BuiltInRenderers.kt`) wires up the built-in type strings
   at app startup; it must be called once (e.g. from `App.kt` or DI init) before any tree is
   rendered.

### Renderer signature

```kotlin
typealias Renderer = @Composable (
    node: UiNode,
    data: DynamicContent,
    onAction: (action: String, params: Map<String, String>) -> Unit,
) -> Unit
```

### Fallback for unknown types

If `node.type` has no registered renderer, `A2UIRegistry.Render` calls a private
`FallbackRenderer` that prints `[<type>]` as small outline-colored debug text and still
recurses into `RenderChildren` — so an unsupported node in the middle of a tree doesn't take
the rest of the tree down with it.

---

## Built-in renderers

Registered by `registerBuiltInRenderers()`:

```kotlin
A2UIRegistry.register("text", ::TextRenderer)
A2UIRegistry.register("button", ::ButtonRenderer)
A2UIRegistry.register("card", ::CardRenderer)
A2UIRegistry.register("row", ::RowRenderer)
A2UIRegistry.register("column", ::ColumnRenderer)
A2UIRegistry.register("form", ::FormRenderer)
A2UIRegistry.register("input", ::InputRenderer)
A2UIRegistry.register("table", ::TableRenderer)
A2UIRegistry.register("table_row", ::TableRowRenderer)
```

### `text`

`TextRenderer` (`renderers/TextRenderer.kt`). Props: `"text"`, `"style"` (`headline` | `title`
| `label` | body-default). Binding: `"text"` → data path. Resolves through
`A2UIRegistry.resolveText`, which prefers the binding over the static prop.

### `button`

`ButtonRenderer` (`renderers/ButtonRenderer.kt`). Props: `"label"`, `"variant"` (`filled` |
`outlined`). Handler: `"onTap"` → `ActionDef`, invoked via `onAction(action, params)` on click.

### `card`

`CardRenderer` (`renderers/CardRenderer.kt`). No specific props — a rounded, elevated
`Surface` container that renders its `children` inside via `RenderChildren`.

### `form` + `input`

`FormRenderer` (`renderers/FormRenderers.kt`). Renders its `children` (typically `input`
nodes), then, if an `"onSubmit"` handler is present, appends a submit `Button`. On click, it
merges `data.snapshot()` (the current `DynamicContent` state) into the handler's `params`
and fires `onAction`. Prop `"submitLabel"` controls the button text (default `"Submit"`).

`InputRenderer`: props `"label"`, `"placeholder"`, `"field"` (data path where the value is
stored locally; falls back to the node's `id`). Renders an `OutlinedTextField` whose
`onValueChange` calls `data.setLocal(field, it)` — purely local state, no round-trip to the
agent until the form is submitted.

### `table` (+ `table_row`)

`TableRenderer` (`renderers/TableRenderer.kt`). Prop `"columns"` is a comma-separated header
string (e.g. `"Name,Email"`). Renders a bordered header row, then `RenderChildren` — each
child is expected to be a `table_row` node.

`TableRowRenderer`: prop/binding `"cells"` is a comma-separated string of cell values for that
row, split and rendered as equally-weighted `Text` cells. An `"onTap"` handler is read but not
yet wired to a click target on the row itself in the current implementation.

### `row` / `column`

`RowRenderer` / `ColumnRenderer` (`renderers/LayoutRenderers.kt`). Prop `"spacing"` (dp,
default 8 for row / 4 for column). Pure layout containers — arrange `children` horizontally
or vertically with `Arrangement.spacedBy(spacing.dp)`.

---

## Data bindings

`DynamicContent` (`composeApp/.../a2ui/DynamicContent.kt`) is the observable store bindings
resolve against — a `SnapshotStateMap<String, JsonElement>` so Compose recomposes when values
change.

- **`update(data: JsonObject)`** — merges server data (from a `ui_data` event) into the
  store. Nested `JsonObject`s are flattened: `{"user": {"name": "Alice"}}` becomes the key
  `"user.name"` → `"Alice"`.
- **`setLocal(path, value)` / `setLocalBool(path, value)`** — sets a value locally (e.g. form
  input, toggle) without any server round-trip.
- **`resolve(path)` / `resolveString(path)`** — reads back the raw `JsonElement` or a display
  string for a given path.
- **`snapshot()`** — rebuilds a nested `JsonObject` from the flat key space, used to send form
  state back to the agent on submit.
- **`has(path)` / `remove(path)` / `clear()`** — existence check, single-key removal, full
  reset.

Renderers resolve a bound prop through `A2UIRegistry.resolveText(node, propName, data,
default)`, which checks `node.bindings[propName]` first (dynamic, resolved against
`DynamicContent`) and only falls back to the static `node.props[propName]` if the binding is
absent or resolves empty.

---

## Extending with a custom renderer

Because `UiNode.type` is a plain string and `A2UIRegistry` is a mutable registry, adding a new
widget type requires no change to the shared schema:

```kotlin
A2UIRegistry.register("chart") { node, data, onAction ->
    // read node.props / node.bindings, resolve via A2UIRegistry.resolveText(...) or
    // data.resolve(path) directly, then emit a @Composable
}
```

Call `register` once at startup — alongside or after `registerBuiltInRenderers()` — before any
tree containing the new type is rendered. If the type is later removed or the renderer isn't
registered on a given platform, `A2UIRegistry.Render` transparently falls back to
`FallbackRenderer`, so the rest of the tree still renders.

---

## JSON examples

### A form

Matches `RenderUIArgs.uiTree`: a `form` with two `input` children and an `onSubmit` handler.

```json
{
  "type": "form",
  "id": "contact-form",
  "handlers": {
    "onSubmit": { "action": "submit_form", "params": { "formId": "contact" } }
  },
  "props": {
    "submitLabel": "Send"
  },
  "children": [
    {
      "type": "input",
      "id": "name",
      "props": { "label": "Name", "field": "form.name" }
    },
    {
      "type": "input",
      "id": "email",
      "props": { "label": "Email", "placeholder": "you@example.com", "field": "form.email" }
    }
  ]
}
```

### A table

```json
{
  "type": "table",
  "id": "users-table",
  "props": { "columns": "Name,Email" },
  "children": [
    {
      "type": "table_row",
      "props": { "cells": "Alice,alice@example.com" }
    },
    {
      "type": "table_row",
      "props": { "cells": "Bob,bob@example.com" }
    }
  ]
}
```

### A card with a bound text and a button (from `A2UISerializationTest`)

```json
{
  "type": "card",
  "id": "user-card",
  "props": { "elevation": 4, "title": "User Info" },
  "bindings": { "subtitle": "user.email" },
  "handlers": {
    "onTap": { "action": "view_profile", "params": { "userId": "123" } }
  },
  "children": [
    { "type": "text", "props": { "style": "headline" }, "bindings": { "text": "user.name" } },
    { "type": "button", "props": { "label": "Edit" }, "handlers": { "onTap": { "action": "edit_user" } } }
  ]
}
```

---

## Limitations

- `table` rows are static children (`table_row` nodes), not a binding to a data array — the
  agent must emit one `table_row` node per row rather than pointing `rows` at a collection
  path.
- `TableRowRenderer` reads an `"onTap"` handler but does not currently attach a click target
  to the row.
- ProtoBuf encoding of `UiNode` cannot carry `props` (`Map<String, JsonElement>` is
  polymorphic and unsupported by `kotlinx.serialization.protobuf`) — only `bindings`/`handlers`
  (`Map<String, String>`-based) round-trip through ProtoBuf today; JSON is the primary wire
  format end-to-end.
- The A2UI bridge in the gateway (`PrometheA2AExecutor`) locates the tree/data markers with a
  regex over the tool's text output rather than a structured tool-result channel — the marker
  strings (`__a2ui_tree__=`, `__a2ui_data__=`) are effectively part of the wire contract and
  must not appear verbatim elsewhere in a `render_ui` response.
- There is no explicit size/depth limit on the `UiNode` tree documented in code — very deep or
  large trees are rendered recursively with no guard rail.
