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
| Windows | Dedicated identity, restricted token, AppContainer, ACLs, firewall and Job Objects | Denied by AppContainer, including loopback | Implemented; Desktop provides an Install/Repair action with UAC |
| Docker / WSL2 | Fallback choice in the product design | Depends on the selected external runner | Not automatic yet |
| MCP stdio | Requires persistent bidirectional sessions | Unavailable | Disabled until IPC v2 |

The default new installation policy is `WORKSPACE_WRITE` with `ON_REQUEST`
approval and network `OFF`. An empty workspace is valid and expected: it is
created for the agent, but Promethe does not populate it with files.

The local Desktop settings can add selected directories as extra roots for
file tools. `FULL_ACCESS` is a separate local-only file-access mode: it
removes file-root confinement for approved file tools, but does not disable
the process sandbox. Commands, code execution, MCP stdio and other process
tools remain confined to the workspace with the normal resource limits.
Every file operation in this mode requires approval. Remote sessions,
channels and webhooks cannot enable or use `FULL_ACCESS`. The network policy
is unchanged and remains `OFF`; `ALLOWLIST` is still rejected fail-closed.
Existing installations keep their stricter policy until an explicit local
change.

## Trust boundary

The Kotlin gateway is responsible for policy, owner authentication, approval
and request construction. The native helper is responsible for starting and
constraining the child process. The helper is discovered from
`PROMETHE_SANDBOX_HELPER` or from a packaged platform/architecture resource;
`PROMETHE_SANDBOX_HELPER_SHA256` must pin its SHA-256 checksum when an external
helper path is configured.

On Windows, the ordinary helper remains the broker. It starts a protected copy
of the same executable as a runner under the dedicated offline identity. The
runner verifies its SID, creates a restricted token and starts the requested
process in an AppContainer without network capabilities and inside a Job Object.
The AppContainer filesystem grants are intersected with the dedicated identity
and restricted-token ACLs. Broker/runner communication uses a random,
access-controlled named pipe. Both ends verify the expected owner/runner
identity and the broker verifies the exact runner PID before sending a request.
Child output uses private inherited pipes with an explicit handle list, not
shared files. The Job Object is assigned before the runner is resumed so
cancellation and gateway failure terminate the complete process tree.
Windows executions are serialized because the restricted processes share one
offline identity; concurrent gateway requests remain queued without allowing
two sandboxed children to inspect each other.

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
| `SANDBOX_MODE` | `READ_ONLY`, `WORKSPACE_WRITE`, or local-only `FULL_ACCESS` for file tools; process tools remain workspace-confined |
| `SANDBOX_READABLE_ROOTS` | JSON array of additional absolute directories readable by file tools, for example `["C:\\Users\\me\\Documents"]`; the workspace root is managed automatically |
| `SANDBOX_WRITABLE_ROOTS` | JSON array of additional absolute directories writable by file tools, for example `["C:\\Users\\me\\Documents\\output"]`; each writable root must also be readable |
| `SANDBOX_APPROVAL_POLICY` | Approval policy, normally `ON_REQUEST` |
| `SANDBOX_NETWORK_MODE` | `OFF` by default; `ALLOWLIST` is currently rejected fail-closed and cannot be enabled by selected roots or `FULL_ACCESS` |
| `SANDBOX_ALLOWED_DOMAINS` | Reserved for a future approved network proxy; it does not enable network today |
| `PROMETHE_SANDBOX_HELPER` | Absolute path to the trusted native helper |
| `PROMETHE_SANDBOX_HELPER_SHA256` | Required 64-character lowercase/uppercase SHA-256 pin for an external helper |

When discovery, checksum validation, helper startup, protocol validation or
self-test fails, process tools become unavailable. Chat and other non-process
features may continue to operate.

## Windows setup

No administrative terminal is required during normal use. Open **Settings >
Sandbox** and select **Install sandbox**. Windows displays one UAC prompt. The
application then creates the dedicated identities and AppContainer profile,
installs the protected runner, applies workspace ACLs and offline firewall
rules, and runs the native self-test. **Repair sandbox** repeats the same
idempotent operation when the
workspace or installation is no longer valid. Cancelling UAC leaves process
tools unavailable; it never enables an unsandboxed fallback.

The setup endpoint is accepted only from loopback with the local API key. The
workspace path comes from the gateway policy and cannot be supplied by a
remote client. One workspace is registered at a time in this version.

For development diagnostics, the same operation can be invoked manually from
the source tree. Both paths are mandatory:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
& .\sandbox-native\windows\setup.ps1 `
  -WorkspaceRoot 'C:\path\to\workspace' `
  -HelperPath '.\sandbox-native\target\release\promethe-sandbox.exe'
```

The setup state is stored under `%ProgramData%\Promethe\sandbox`. Credentials
are encrypted with machine-scoped DPAPI and protected by ACLs. The runner and
manifest are read-only to sandbox identities; only the private temp directory
is writable. Every install or repair rotates the workspace-writer group SID,
so an old or temporarily disconnected workspace cannot retain write access.
A setup owned by another Windows user is rejected and must be uninstalled
before reinstalling. A behavioral self-test verifies the
execution identity, workspace writes, control-plane write denial and TCP/UDP
network denial on IPv4, IPv6, loopback and a local non-loopback address before the
backend is marked available. Network certification is refreshed while the
helper is running; a failed refresh disables process execution.

The workspace root does not grant `DELETE_CHILD` to the writer identity.
Ordinary descendants receive Modify through an inheritance-only ACE, while
`.git`, `.promethe`, `.codex` and `.agents` have explicit write/delete denies.
Setup refuses workspace roots or protected paths that are reparse points.

The Windows setup does not change ACLs on the owner's profile. Commands may
read system/runtime locations already available to the dedicated identity and
the registered workspace, can write only inside that workspace, and cannot use
the network. Selected roots and `FULL_ACCESS` affect file tools only; they do
not expand the native command runner. A future Docker/WSL2 profile may provide
a different isolation boundary, but is not silently substituted for this
profile.

## Selected roots and full local access

The workspace may contain no files. This is normal: the user can place files
there, attach files through the application, or select additional directories
from **Settings > Sandbox**. Selected roots are canonicalized and checked for
symlink/reparse-point escapes. They can be readable or writable, and protected
metadata such as `.git`, `.promethe`, `.codex` and `.agents` remains protected.
The agent can call `workspace_roots` to discover the active workspace and
selected directories before using the other file tools.

`FULL_ACCESS` is available only to a local Desktop session. It is intended for
trusted personal workflows and displays a warning. It disables file-root
confinement for file tools, while approvals remain mandatory for every file
operation. It does not grant remote clients, does not expose network access,
and does not change the command/code/MCP process sandbox, resource limits or
process approvals. Returning to `READ_ONLY` or `WORKSPACE_WRITE` restores
file-root confinement.

To repair, run the same setup command again as administrator. To remove the
native Windows setup:

```powershell
& .\sandbox-native\windows\uninstall.ps1
```

Review the `ShouldProcess` confirmation and verify that the intended setup
root, accounts, group, ACLs and temporary files were removed. Workspace data
is not deleted by the uninstall script.

## Current limitations

- No runtime self-test has been performed here on Linux with the installed
  Bubblewrap/seccomp combination. This must be run on target distributions.
- macOS process execution is fail-closed and unavailable until descendants
  cannot detach from supervision with `setsid()`.
- Windows process execution remains fail-closed until elevated setup and all
  behavioral self-tests pass on the current machine.
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
