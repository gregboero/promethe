---
name: computer-use
description: "Drive the user's desktop in the background — clicking, typing, scrolling, dragging — without stealing cursor or keyboard focus. Cross-platform."
source: BUNDLED
platforms: jvm
---

# Computer Use (universal, cross-platform)

You have a `computer_use` tool that drives the user's desktop in the **background** — your actions do NOT move the user's cursor, steal keyboard focus, or switch virtual desktops / Spaces. The user can keep typing in their editor while you click around in a browser in another window.

Everything here works with any tool-capable model.

## The canonical workflow

**Step 1 — Capture first.** Almost every task starts with:

```
computer_use(action="capture", mode="som", app="<the app you're driving>")
```

Returns a screenshot with numbered overlays on every interactable element AND an AX-tree index like:

```
#1  AXButton 'Back' @ (12, 80, 28, 28) [Chrome]
#2  AXTextField 'Address bar' @ (80, 80, 900, 32) [Chrome]
#7  Link 'Sign In' @ (900, 420, 80, 24) [Chrome]
```

The role names match the host platform's accessibility framework (`AXButton` on macOS, `Button` on Windows UIA, `push button` on Linux AT-SPI) — treat them as labels, not strict types.

**Step 2 — Click by element index.** The single most important habit:

```
computer_use(action="click", element=7)
```

Much more reliable than pixel coordinates for every model.

**Step 3 — Verify.** After any state-changing action, re-capture. Save a round-trip with inline capture:

```
computer_use(action="click", element=7, capture_after=True)
```

## Capture modes

| `mode` | Returns | Best for |
|---|---|---|
| `som` (default) | Screenshot + numbered overlays + AX index | Vision models; preferred default |
| `vision` | Plain screenshot | When SOM overlay interferes with verification |
| `ax` | AX tree only, no image | Text-only models, or no pixels needed |

## Actions

```
capture           mode=som|vision|ax   app=…  (default: current app)
click             element=N     OR     coordinate=[x, y]    button=left|right|middle
double_click      element=N     OR     coordinate=[x, y]
right_click       element=N     OR     coordinate=[x, y]
middle_click      element=N     OR     coordinate=[x, y]
drag              from_element=N, to_element=M        (or from/to_coordinate)
scroll            direction=up|down|left|right   amount=3 (ticks)
type              text="…"
key               keys="<save shortcut>" | "return" | "escape" | "<modifier>+t"
wait              seconds=0.5
list_apps
focus_app         app="<app name>"   raise_window=false   (default: don't raise)
```

All actions accept optional `capture_after=True` for a follow-up screenshot. All element-targeting actions accept `modifiers=[…]` for held keys.

### Key shortcuts vary per platform

Use the host's idiomatic modifier:

| Common action | macOS | Windows / Linux |
|---|---|---|
| Save | `cmd+s` | `ctrl+s` |
| New tab | `cmd+t` | `ctrl+t` |
| Close tab / window | `cmd+w` | `ctrl+w` |
| Copy / paste | `cmd+c` / `cmd+v` | `ctrl+c` / `ctrl+v` |
| Address bar | `cmd+l` | `ctrl+l` |
| App switcher | `cmd+tab` | `alt+tab` |

When in doubt, capture and look for menu hints, or ask the user which shortcut to use.

## Background rules (the whole point)

1. **Never `raise_window=True`** unless the user explicitly asked you to bring a window to front. Input routing works without raising.
2. **Scope captures to an app** (`app="Chrome"`) — less noisy, fewer elements, doesn't leak other windows.
3. **Don't switch virtual desktops / Spaces.** Elements on any desktop/Space are accessible regardless of which one is visible.
4. **The user can be on the same machine.** Don't grab focus. Don't pop modals to the front.

## Drag & drop

Prefer element indices:

```
computer_use(action="drag", from_element=3, to_element=17)
```

For pixel-precise positioning, use `from_coordinate` / `to_coordinate` instead.
