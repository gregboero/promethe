---
name: apple-reminders
description: "Apple Reminders via remindctl: add, list, complete."
source: BUNDLED
requires_cli: remindctl
platforms: jvm
---

# Apple Reminders

Use `remindctl` to manage Apple Reminders directly from the terminal. Tasks sync across all Apple devices via iCloud.

## Prerequisites

- **macOS** with Reminders.app
- Install: `brew install steipete/tap/remindctl`
- Grant Reminders permission when prompted
- Check: `remindctl status` / Request: `remindctl authorize`

## When to Use

- User mentions "reminder" or "Reminders app"
- Creating personal to-dos with due dates that sync to iOS
- Managing Apple Reminders lists
- User wants tasks to appear on their iPhone/iPad

## When NOT to Use

- Scheduling agent alerts → use a scheduled task instead
- Calendar events → use Apple Calendar or Google Calendar
- Project task management → use GitHub Issues, Notion, etc.
- If user says "remind me" but means an agent alert → clarify first

## Quick Reference

### View Reminders

```bash
execute_command(command="remindctl", args=[])                    # Today's reminders
execute_command(command="remindctl", args=["today"])              # Today
execute_command(command="remindctl", args=["tomorrow"])           # Tomorrow
execute_command(command="remindctl", args=["week"])               # This week
execute_command(command="remindctl", args=["overdue"])            # Past due
execute_command(command="remindctl", args=["all"])                # Everything
execute_command(command="remindctl", args=["2026-01-04"])         # Specific date
```

### Manage Lists

```bash
execute_command(command="remindctl", args=["list"])               # List all lists
execute_command(command="remindctl", args=["list", "Work"])       # Show specific list
execute_command(command="remindctl", args=["list", "Projects", "--create"])  # Create list
```

### Add Reminders

```bash
execute_command(command="remindctl", args=["add", "Buy groceries"])
execute_command(command="remindctl", args=["add", "Call dentist", "--due", "2026-01-05"])
execute_command(command="remindctl", args=["add", "Submit report", "--list", "Work", "--due", "tomorrow"])
```

### Complete Reminders

```bash
execute_command(command="remindctl", args=["complete", "REMINDER_ID"])
```

## Rules

1. Always confirm destructive operations (delete, complete all) with the user.
2. When creating reminders, include a due date if the user mentioned any time reference.
3. Use `--list` to organize reminders into the appropriate list when context is clear.
