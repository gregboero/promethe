---
name: claude-code
description: "Delegate repository work to the authenticated local Claude Code CLI."
source: BUNDLED
requires_cli: claude
platforms: jvm
---

# Claude Code local delegation

Use `claude_code_delegate` for focused coding, review, debugging, and repository
tasks. The tool is present only when Promethe detects a runnable, authenticated
Claude Code CLI.

## Arguments

- `task`: the complete task and acceptance criteria.
- `accessMode`: `READ_ONLY` for analysis and review; `WORKSPACE_WRITE` only when edits are required.
- `externalSessionId`: optional Claude session id returned by an earlier delegation.

Promethe uses Claude Code print mode with streaming JSON. Workspace mutations are
relayed to Promethe's local approval service through an ephemeral MCP permission
tool. Never invoke Claude through `execute_command`, `shell`, or a shell wrapper.
`--dangerously-skip-permissions` is forbidden.

After the tool returns, inspect its summary and synthesize the final answer inside
the current Promethe agent loop. Use `delegate_task` only for Promethe subagents,
not for Claude Code.
