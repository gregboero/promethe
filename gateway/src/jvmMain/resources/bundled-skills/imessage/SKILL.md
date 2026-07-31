---
name: imessage
description: "Send and receive iMessages/SMS via the imsg CLI on macOS."
source: BUNDLED
requires_cli: imsg
platforms: jvm
---

# iMessage

Use `imsg` to read and send iMessage/SMS via macOS Messages.app.

## Prerequisites

- **macOS** with Messages.app signed in
- Install: `brew install steipete/tap/imsg`
- Grant Full Disk Access for terminal (System Settings → Privacy → Full Disk Access)
- Grant Automation permission for Messages.app when prompted

## When to Use

- User asks to send an iMessage or text message
- Reading iMessage conversation history
- Checking recent Messages.app chats
- Sending to phone numbers or Apple IDs

## When NOT to Use

- Telegram/Discord/Slack/WhatsApp messages → use the appropriate skill
- Group chat management (adding/removing members) → not supported
- Bulk/mass messaging → always confirm with user first

## Quick Reference

### List Chats

```bash
execute_command(command="imsg", args=["chats", "--limit", "10", "--json"])
```

### View History

```bash
# By chat ID
execute_command(command="imsg", args=["history", "--chat-id", "1", "--limit", "20", "--json"])

# With a specific contact
execute_command(command="imsg", args=["history", "--handle", "+1234567890", "--limit", "20", "--json"])
```

### Send Messages

```bash
# Send to a phone number
execute_command(command="imsg", args=["send", "--to", "+1234567890", "--text", "Hello!"])

# Send to an Apple ID
execute_command(command="imsg", args=["send", "--to", "user@icloud.com", "--text", "Hello!"])
```

### Search Messages

```bash
execute_command(command="imsg", args=["search", "--query", "meeting", "--limit", "10", "--json"])
```

## Rules

1. **Always confirm with the user before sending any message.** Never auto-send.
2. Never display full phone numbers in logs — mask middle digits.
3. Use `--json` output for structured parsing of chat data.
4. macOS only — requires Messages.app with an active Apple ID or phone number.
