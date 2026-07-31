---
name: apple-notes
description: "Manage Apple Notes via memo CLI: create, search, edit."
source: BUNDLED
requires_cli: memo
platforms: jvm
---

# Apple Notes

Use `memo` to manage Apple Notes directly from the terminal. Notes sync across all Apple devices via iCloud.

## Prerequisites

- **macOS** with Notes.app
- Install: `brew tap antoniorodr/memo && brew install antoniorodr/memo/memo`
- Grant Automation access to Notes.app when prompted (System Settings → Privacy → Automation)

## When to Use

- User asks to create, view, or search Apple Notes
- Saving information to Notes.app for cross-device access
- Organizing notes into folders
- Exporting notes to Markdown/HTML

## When NOT to Use

- Obsidian vault management → use the `obsidian` skill
- Bear Notes → separate app (not supported here)
- Quick agent-only notes → use internal memory instead

## Quick Reference

### View Notes

```bash
execute_command(command="memo", args=["notes"])                        # List all notes
execute_command(command="memo", args=["notes", "-f", "Folder Name"])   # Filter by folder
execute_command(command="memo", args=["notes", "-s", "query"])         # Search notes (fuzzy)
```

### Create Notes

```bash
execute_command(command="memo", args=["notes", "-a"])                  # Interactive editor
execute_command(command="memo", args=["notes", "-a", "Note Title"])    # Quick add with title
```

### Edit Notes

```bash
execute_command(command="memo", args=["notes", "-e"])                  # Interactive selection to edit
```

### Delete Notes

```bash
execute_command(command="memo", args=["notes", "-d"])                  # Interactive selection to delete
```

### Move Notes

```bash
execute_command(command="memo", args=["notes", "-m"])                  # Move note to folder (interactive)
```

### Export Notes

```bash
execute_command(command="memo", args=["notes", "-ex"])                 # Export to HTML/Markdown
```

## Limitations

- Cannot edit notes containing images or attachments
- Interactive prompts require terminal access
- macOS only — requires Apple Notes.app

## Rules

1. Prefer Apple Notes when user wants cross-device sync (iPhone/iPad/Mac)
2. Use internal memory for agent-only notes that don't need to sync
3. Use the `obsidian` skill for Markdown-native knowledge management
