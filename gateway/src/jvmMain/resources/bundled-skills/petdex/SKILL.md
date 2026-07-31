---
name: petdex
description: "Browse, install, and select animated petdex mascots."
source: BUNDLED
platforms: jvm
---

# Petdex Skill

Browse, install, and select animated "pet" mascots from the public [petdex](https://github.com/crafter-station/petdex) gallery. An installed pet reacts to agent activity (idle, running a tool, reviewing, error, done) across the CLI, TUI, and desktop app.

## When to Use

- The user wants a desktop/terminal mascot or asks about "pets" / petdex.
- The user wants to change, preview, or disable the active pet.
- Diagnosing why a pet isn't showing (terminal graphics support, config).

## Prerequisites

- Network access to `petdex.dev` for the gallery/manifest (read-only, no auth).
- Pillow for sprite decoding.
- For full-fidelity terminal rendering: a graphics-capable terminal (kitty, Ghostty, WezTerm, iTerm2, or sixel). Otherwise a truecolor Unicode half-block fallback is used automatically.

## How to Run

Use `execute_command` to run petdex subcommands.

## Quick Reference

| Goal | Command |
| --- | --- |
| Browse the gallery | `execute_command(command="petdex", args=["list"])` |
| Filter gallery | `execute_command(command="petdex", args=["list", "cat"])` |
| List installed pets | `execute_command(command="petdex", args=["list", "--installed"])` |
| Install a pet | `execute_command(command="petdex", args=["install", "<slug>"])` |
| Install and activate | `execute_command(command="petdex", args=["install", "<slug>", "--select"])` |
| Set the active pet | `execute_command(command="petdex", args=["select", "<slug>"])` |
| Preview a pet | `execute_command(command="petdex", args=["preview", "<slug>"])` |
| Disable the pet | `execute_command(command="petdex", args=["disable"])` |

## Gallery API

You can also browse the gallery via HTTP:
```
http_fetch(url="https://petdex.dev/api/manifest.json", method="GET")
```
