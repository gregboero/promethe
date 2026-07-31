---
name: findmy
description: "Track Apple devices/AirTags via FindMy.app on macOS."
source: BUNDLED
platforms: jvm
---

# Find My (Apple)

Track Apple devices and AirTags via the FindMy.app on macOS. Since Apple doesn't provide a CLI for FindMy, this skill uses AppleScript to open the app and screen capture to read device locations.

## Prerequisites

- **macOS** with Find My app and iCloud signed in
- Devices/AirTags already registered in Find My
- Screen Recording permission for terminal (System Settings → Privacy → Screen Recording)
- **Optional but recommended**: Install `peekaboo` for better UI automation:
  `brew install steipete/tap/peekaboo`

## When to Use

- User asks "where is my [device/cat/keys/bag]?"
- Tracking AirTag locations
- Checking device locations (iPhone, iPad, Mac, AirPods)
- Monitoring pet or item movement over time (AirTag patrol routes)

## Method 1: AppleScript + Screenshot (Basic)

### Open FindMy and Navigate

```bash
# Open Find My app
execute_command(command="osascript", args=["-e", "tell application \"FindMy\" to activate"])

# Wait for it to load
execute_command(command="sleep", args=["3"])

# Take a screenshot of the Find My window
execute_command(command="screencapture", args=["-w", "-o", "/tmp/findmy.png"])
```

Then analyze the screenshot to read device positions and locations.

### Switch Between Tabs

```bash
# Switch to Devices tab
execute_command(command="osascript", args=["-e", "tell application \"System Events\" to tell process \"FindMy\" to click button \"Devices\" of toolbar 1 of window 1"])

# Switch to Items tab (AirTags)
execute_command(command="osascript", args=["-e", "tell application \"System Events\" to tell process \"FindMy\" to click button \"Items\" of toolbar 1 of window 1"])

# Switch to People tab
execute_command(command="osascript", args=["-e", "tell application \"System Events\" to tell process \"FindMy\" to click button \"People\" of toolbar 1 of window 1"])
```

## Method 2: Peekaboo (Recommended)

If `peekaboo` is installed, use it for more reliable UI automation and element inspection:

```bash
execute_command(command="peekaboo", args=["--app", "FindMy", "--format", "json"])
```

## Limitations

- No official CLI — relies on screen capture and AppleScript
- Location data is approximate (based on what FindMy.app displays)
- Cannot programmatically play sounds or mark items as lost (use the app UI)
- macOS only

## Rules

1. Always open FindMy.app first and wait for it to load before capturing.
2. Report locations as shown in the app — do not extrapolate or guess.
3. If the user asks to play a sound or mark lost, instruct them to do it in the app directly.
