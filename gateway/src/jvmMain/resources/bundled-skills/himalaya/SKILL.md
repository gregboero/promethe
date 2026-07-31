---
name: himalaya
description: "Himalaya CLI: IMAP/SMTP email from terminal."
source: BUNDLED
requires_cli: himalaya
platforms: jvm
---

# Himalaya Email CLI

Himalaya is a CLI email client that lets you manage emails from the terminal using IMAP, SMTP, Notmuch, or Sendmail backends.

Use this skill when the agent needs to operate a mailbox — read, send, search, or organize email — via terminal commands.

## References

- `references/configuration.md` (config file setup + IMAP/SMTP authentication)
- `references/message-composition.md` (MML syntax for composing emails)

## Prerequisites

1. Himalaya CLI installed (verify with `execute_command(command="himalaya", args=["--version"])`)
2. A configuration file at `~/.config/himalaya/config.toml`
3. IMAP/SMTP credentials configured (password stored securely)

### Installation

```
# Pre-built binary (Linux/macOS — recommended)
execute_command(command="sh", args=["-c", "curl -sSL https://raw.githubusercontent.com/pimalaya/himalaya/master/install.sh | PREFIX=~/.local sh"])

# macOS via Homebrew
execute_command(command="brew", args=["install", "himalaya"])

# Or via cargo (any platform with Rust)
execute_command(command="cargo", args=["install", "himalaya", "--locked"])
```

## Configuration Setup

Run the interactive wizard to set up an account:

```
execute_command(command="himalaya", args=["account", "configure"])
```

Or create `~/.config/himalaya/config.toml` manually using `file_write`:

```toml
[accounts.personal]
email = "you@example.com"
display-name = "Your Name"
default = true

backend.type = "imap"
backend.host = "imap.example.com"
backend.port = 993
backend.encryption.type = "tls"
backend.login = "you@example.com"
backend.auth.type = "password"
backend.auth.cmd = "pass show email/imap"  # or use keyring

message.send.backend.type = "smtp"
message.send.backend.host = "smtp.example.com"
message.send.backend.port = 587
message.send.backend.encryption.type = "start-tls"
message.send.backend.login = "you@example.com"
message.send.backend.auth.type = "password"
message.send.backend.auth.cmd = "pass show email/smtp"

# Folder aliases (himalaya v1.2.0+ syntax). Required whenever the
# server's folder names don't match himalaya's canonical names
# (inbox/sent/drafts/trash). Gmail is the common case — see
# references/configuration.md for the Gmail mapping.
folder.aliases.inbox = "INBOX"
folder.aliases.sent = "Sent"
folder.aliases.drafts = "Drafts"
folder.aliases.trash = "Trash"
```

> **Heads up on the alias syntax.** Pre-v1.2.0 docs used a
> `[accounts.NAME.folder.alias]` sub-section (singular `alias`).
> v1.2.0 silently ignores that form — TOML parses fine, but the
> alias resolver never reads it. Use the dotted `folder.aliases.*` form above.

## Core Usage

### List messages

```
execute_command(command="himalaya", args=["envelope", "list", "-f", "INBOX"])
execute_command(command="himalaya", args=["envelope", "list", "-f", "INBOX", "--page-size", "20"])
```

### Read a message

```
execute_command(command="himalaya", args=["message", "read", "MESSAGE_ID"])
```

### Search messages

```
execute_command(command="himalaya", args=["envelope", "list", "-f", "INBOX", "--query", "subject:meeting AND from:boss@example.com"])
```

### Send a message (MML format)

```
execute_command(command="himalaya", args=["message", "send"], stdin="From: you@example.com\nTo: them@example.com\nSubject: Hello\n\nMessage body here")
```

### Reply to a message

```
execute_command(command="himalaya", args=["message", "reply", "MESSAGE_ID"], stdin="Reply body here")
```

### Forward a message

```
execute_command(command="himalaya", args=["message", "forward", "MESSAGE_ID", "--to", "other@example.com"])
```

### Manage folders

```
execute_command(command="himalaya", args=["folder", "list"])
execute_command(command="himalaya", args=["message", "move", "MESSAGE_ID", "-f", "INBOX", "--to", "Archive"])
execute_command(command="himalaya", args=["message", "delete", "MESSAGE_ID", "-f", "INBOX"])
```

### Manage flags

```
execute_command(command="himalaya", args=["flag", "add", "MESSAGE_ID", "-f", "INBOX", "Seen"])
execute_command(command="himalaya", args=["flag", "remove", "MESSAGE_ID", "-f", "INBOX", "Flagged"])
```

## Gmail-Specific Notes

For Gmail, use App Passwords (Settings → Security → App Passwords) for auth. The folder alias mapping for Gmail:

```toml
folder.aliases.sent = "[Gmail]/Sent Mail"
folder.aliases.drafts = "[Gmail]/Drafts"
folder.aliases.trash = "[Gmail]/Trash"
folder.aliases.spam = "[Gmail]/Spam"
```
