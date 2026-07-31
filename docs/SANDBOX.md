# Promethe Sandbox

## Scope and status

Promethe process tools are designed to fail closed when the native sandbox is
not available. The gateway sends versioned JSON Lines requests to the native
`promethe-sandbox` helper. The current protocol is **JSONL v1**. It supports
bounded, non-interactive executions with an executable and literal arguments;
it does not provide persistent stdin/stdout sessions.

| Platform/profile | Current implementation | Network | Public status |
|---|---|---|---|
| Linux | Bubblewrap namespaces, seccomp and `no_new_privs` | Denied | Implemented; runtime certification still required on target distributions |
| macOS | Seatbelt profile retained for testing | Denied | `UNAVAILABLE` until detached descendants cannot escape supervision |
| Windows | Elevated setup, restricted token, ACLs, firewall and Job Objects | Denied by the native profile | Implemented; setup must be run and verified by an administrator |
| Docker / WSL2 | Fallback choice in the product design | Depends on the selected external runner | Not automatic yet |
| MCP stdio | Requires persistent bidirectional sessions | Unavailable | Disabled until IPC v2 |

`FULL_ACCESS` and `ALLOWLIST` are rejected by the current native helper. They
are not silently downgraded to a weaker or unsandboxed mode. The default new
installation policy is `WORKSPACE_WRITE` with `ON_REQUEST` approval and
network `OFF`. Existing installations keep their existing stricter policy
until an explicit validated change.

## Trust boundary

The Kotlin gateway is responsible for policy, owner authentication, approval
and request construction. The native helper is responsible for starting and
constraining the child process. The helper is discovered from
`PROMETHE_SANDBOX_HELPER` or from a packaged platform/architecture resource;
`PROMETHE_SANDBOX_HELPER_SHA256` must pin its SHA-256 checksum when an external
helper path is configured.

Only the trusted helper launcher may create the native process. Tool code must
provide an executable plus an argument list. Shell strings, pipes,
redirections, `sh -c` and `cmd /c` are not a supported execution interface.
MCP stdio and any unsupported interactive transport return
`SANDBOX_PROTOCOL_V2_REQUIRED` rather than starting an unmanaged process.

The workspace broker canonicalizes paths and rejects traversal, symlink escape
and writes to protected metadata such as `.git`, `.promethe`, `.codex` and
`.agents`. Approval fingerprints include the canonical tool arguments and the
active sandbox policy fingerprint. Changing either invalidates an existing
grant. Logs must contain redacted errors and must never contain provider keys,
session tokens or command secrets.

## Configuration

The principal variables are:

| Variable | Meaning |
|---|---|
| `SANDBOX_BACKEND` | `auto` or an explicitly selected native backend |
| `SANDBOX_WORKSPACE` | Workspace root; defaults to `~/.promethe/workspace`, outside credentials and runtime metadata |
| `SANDBOX_MODE` | `READ_ONLY`, `WORKSPACE_WRITE`, or an unsupported profile such as `FULL_ACCESS` |
| `SANDBOX_APPROVAL_POLICY` | Approval policy, normally `ON_REQUEST` |
| `SANDBOX_NETWORK_MODE` | `OFF` by default; `ALLOWLIST` is currently rejected fail-closed |
| `SANDBOX_ALLOWED_DOMAINS` | Reserved for a future approved network proxy; it does not enable network today |
| `PROMETHE_SANDBOX_HELPER` | Absolute path to the trusted native helper |
| `PROMETHE_SANDBOX_HELPER_SHA256` | Required 64-character lowercase/uppercase SHA-256 pin for an external helper |

When discovery, checksum validation, helper startup, protocol validation or
self-test fails, process tools become unavailable. Chat and other non-process
features may continue to operate.

## Windows setup

Windows execution is deliberately unavailable in this release. The helper
returns `BACKEND_UNAVAILABLE` because the elevated account/ACL backend has not
yet demonstrated complete read confinement outside the workspace. The scripts
below prepare the experimental backend for security testing only; they do not
enable process execution.

Run the setup script from an elevated PowerShell prompt and pass every
workspace that the sandbox is allowed to access:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
& .\sandbox-native\windows\setup.ps1 -WorkspaceRoot 'C:\path\to\workspace'
```

The script creates or verifies the dedicated sandbox identities, ACLs,
credentials and setup manifest under `%ProgramData%\Promethe\sandbox`.
It does not run as part of ordinary application startup and has not been
executed by the project test suite. A failed or missing setup is an expected
closed failure, not permission to use an unelevated backend.

To repair, run the same setup command again as administrator. To remove the
native Windows setup:

```powershell
& .\sandbox-native\windows\uninstall.ps1 -WorkspaceRoot 'C:\path\to\workspace'
```

Review the `ShouldProcess` confirmation and verify that the intended setup
root, accounts, group, ACLs and temporary files were removed. Workspace data
is not deleted by the uninstall script.

## Current limitations

- No runtime self-test has been performed here on Linux with the installed
  Bubblewrap/seccomp combination. This must be run on target distributions.
- macOS process execution is fail-closed and unavailable until descendants
  cannot detach from supervision with `setsid()`.
- Windows process execution is fail-closed and unavailable until a restricted
  token/AppContainer design proves read confinement outside the workspace.
- Docker and WSL2 are not automatically selected as native fallbacks.
- MCP stdio needs IPC v2 for streaming, stdin, cancellation and persistent
  process lifecycle management.
- Network allowlists and a local authenticated proxy are not available in the
  current helper; network remains disabled.
- `PERSISTENT` approval grants are currently process-lifetime grants; durable
  database-backed approval persistence is not implemented yet.
- The native helper is not usable until its platform artifact is packaged or
  `PROMETHE_SANDBOX_HELPER` points to a verified executable.

## Provenance and licensing

The architecture was rewritten for Promethe around the JSONL protocol. No
Codex source file is vendored verbatim. See
[`sandbox-native/UPSTREAM_CODEX.md`](../sandbox-native/UPSTREAM_CODEX.md) for
the upstream reference commit, Apache-2.0 notice and referenced modules.
