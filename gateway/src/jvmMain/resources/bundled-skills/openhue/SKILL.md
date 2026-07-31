---
name: openhue
description: "Control Philips Hue lights, scenes, rooms via OpenHue CLI."
source: BUNDLED
requires_cli: openhue
platforms: jvm
---

# OpenHue CLI

Control Philips Hue lights and scenes via a Hue Bridge from the terminal.

## Prerequisites

```bash
# Linux (pre-built binary)
execute_command(command="curl", args=["-sL", "https://github.com/openhue/openhue-cli/releases/latest/download/openhue-linux-amd64", "-o", "~/.local/bin/openhue"])
execute_command(command="chmod", args=["+x", "~/.local/bin/openhue"])

# macOS
execute_command(command="brew", args=["install", "openhue/cli/openhue-cli"])
```

First run requires pressing the button on your Hue Bridge to pair. The bridge must be on the same local network.

## When to Use

- "Turn on/off the lights"
- "Dim the living room lights"
- "Set a scene" or "movie mode"
- Controlling specific Hue rooms, zones, or individual bulbs
- Adjusting brightness, color, or color temperature

## Common Commands

### List Resources

```bash
execute_command(command="openhue", args=["get", "light"])       # List all lights
execute_command(command="openhue", args=["get", "room"])        # List all rooms
execute_command(command="openhue", args=["get", "scene"])       # List all scenes
execute_command(command="openhue", args=["get", "zone"])        # List all zones
```

### Control Lights

```bash
# Turn on/off
execute_command(command="openhue", args=["set", "light", "LIGHT_NAME", "--on"])
execute_command(command="openhue", args=["set", "light", "LIGHT_NAME", "--off"])

# Set brightness (0-100)
execute_command(command="openhue", args=["set", "light", "LIGHT_NAME", "--brightness", "50"])

# Set color (hex)
execute_command(command="openhue", args=["set", "light", "LIGHT_NAME", "--color", "#FF5500"])

# Set color temperature (mirek, 153-500)
execute_command(command="openhue", args=["set", "light", "LIGHT_NAME", "--ct", "300"])
```

### Control Rooms

```bash
execute_command(command="openhue", args=["set", "room", "Living Room", "--on"])
execute_command(command="openhue", args=["set", "room", "Bedroom", "--off"])
execute_command(command="openhue", args=["set", "room", "Office", "--brightness", "75"])
```

### Activate Scenes

```bash
execute_command(command="openhue", args=["set", "scene", "Movie Mode"])
execute_command(command="openhue", args=["set", "scene", "Relax"])
```

## Rules

1. Always list available lights/rooms first if the user is vague about which light to control.
2. Use room-level commands when the user refers to a room, not individual bulbs.
3. Brightness values are 0-100. Color temperature (mirek) is 153 (cool) to 500 (warm).
4. The Hue Bridge must be reachable on the local network for commands to work.
