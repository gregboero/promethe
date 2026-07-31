---
name: xurl
description: "X/Twitter via xurl CLI: post, search, DM, media, v2 API."
source: BUNDLED
requires_cli: xurl
platforms: jvm
---

# xurl — X (Twitter) API via the Official CLI

`xurl` is the X developer platform's official CLI for the X API. It supports shortcut commands for common actions AND raw curl-style access to any v2 endpoint. All commands return JSON to stdout.

Use this skill for:
- posting, replying, quoting, deleting posts
- searching posts and reading timelines/mentions
- liking, reposting, bookmarking
- following, unfollowing, blocking, muting
- direct messages
- media uploads (images and video)
- raw access to any X API v2 endpoint
- multi-app / multi-account workflows

---

## Secret Safety (MANDATORY)

Critical rules when operating inside an agent session:

- **Never** read, print, parse, summarize, upload, or send `~/.xurl` to context.
- **Never** ask the user to paste credentials/tokens into chat.
- The user must fill `~/.xurl` with secrets manually on their own machine.
- **Never** recommend or execute auth commands with inline secrets in agent sessions.
- **Never** use `--verbose` / `-v` in agent sessions — it can expose auth headers/tokens.
- To verify credentials exist, only use: `xurl auth status`.

Forbidden flags in agent commands (they accept inline secrets):
`--bearer-token`, `--consumer-key`, `--consumer-secret`, `--access-token`, `--token-secret`, `--client-id`, `--client-secret`

App credential registration and credential rotation must be done by the user manually, outside the agent session.

---

## Installation

Pick ONE method:

```bash
# Shell script (installs to ~/.local/bin)
execute_command(command="bash", args=["-c", "curl -fsSL https://raw.githubusercontent.com/xdevplatform/xurl/main/install.sh | bash"])

# Homebrew (macOS)
execute_command(command="brew", args=["install", "--cask", "xdevplatform/tap/xurl"])

# npm
execute_command(command="npm", args=["install", "-g", "@xdevplatform/xurl"])

# Go
execute_command(command="go", args=["install", "github.com/xdevplatform/xurl@latest"])
```

Verify:

```bash
execute_command(command="xurl", args=["--help"])
execute_command(command="xurl", args=["auth", "status"])
```

---

## One-Time User Setup (user runs these outside the agent)

These steps must be performed by the user directly, NOT by the agent, because they involve pasting secrets. Direct the user to this block; do not execute it for them.

1. Create or open an app at https://developer.x.com/en/portal/dashboard
2. Set the redirect URI to `http://localhost:8080/callback`
3. Copy the app's Client ID and Client Secret
4. Register the app locally: `xurl auth apps add my-app --client-id YOUR_CLIENT_ID --client-secret YOUR_CLIENT_SECRET`
5. Authenticate: `xurl auth oauth2 --app my-app`
6. Set as default: `xurl auth default my-app`
7. Verify: `xurl auth status` and `xurl whoami`

> **Common pitfall:** If you omit `--app my-app` from `xurl auth oauth2`, the OAuth token is saved to the built-in `default` app profile — which has no client-id. Commands will fail with auth errors. Re-run with `--app my-app`.

---

## Quick Reference

| Action | Command |
| --- | --- |
| Post | `execute_command(command="xurl", args=["post", "Hello world!"])` |
| Reply | `execute_command(command="xurl", args=["reply", "POST_ID", "Nice post!"])` |
| Quote | `execute_command(command="xurl", args=["quote", "POST_ID", "My take"])` |
| Delete | `execute_command(command="xurl", args=["delete", "POST_ID"])` |
| Read a post | `execute_command(command="xurl", args=["read", "POST_ID"])` |
| Search | `execute_command(command="xurl", args=["search", "QUERY", "-n", "10"])` |
| Who am I | `execute_command(command="xurl", args=["whoami"])` |
| User lookup | `execute_command(command="xurl", args=["user", "@handle"])` |
| Timeline | `execute_command(command="xurl", args=["timeline", "-n", "20"])` |
| Mentions | `execute_command(command="xurl", args=["mentions", "-n", "10"])` |
| Like | `execute_command(command="xurl", args=["like", "POST_ID"])` |
| Repost | `execute_command(command="xurl", args=["repost", "POST_ID"])` |
| Bookmark | `execute_command(command="xurl", args=["bookmark", "POST_ID"])` |
| Follow | `execute_command(command="xurl", args=["follow", "@handle"])` |
| DM | `execute_command(command="xurl", args=["dm", "send", "USER_ID", "message"])` |

## Rules

1. **Never expose credentials** — see Secret Safety section above.
2. Always confirm with the user before posting, replying, DMing, or following.
3. Parse all output as JSON for structured handling.
4. Use `xurl auth status` to check if auth is configured before attempting operations.
