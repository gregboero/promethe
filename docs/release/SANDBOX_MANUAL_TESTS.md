# Sandbox Manual Test Matrix

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** See [project status / statut du projet](../EXPERIMENTAL_STATUS.md).

Run these checks with a release artifact, not from the IDE. Record Promethe
version, helper checksum, OS/build, workspace path, tester, timestamp,
expected result, observed result and a redacted log or screenshot. Do not use
real provider keys or personal files.

## Preconditions

Create a disposable workspace containing:

- `allowed.txt` and a writable `output.txt`;
- a file outside the workspace;
- a symlink to the outside file on Linux/macOS;
- a junction or directory symlink to the outside directory on Windows;
- `.git`, `.promethe`, `.codex` and `.agents` directories;
- a command fixture that sleeps, forks, writes output and attempts network access.

An empty workspace is also a valid fixture. Record that the initial directory
listing is empty; this is expected and is not an installation failure. For the
selected-root tests, prepare one readable directory and one writable directory
outside the workspace, containing only disposable data.

Confirm that the helper is selected through packaging or
`PROMETHE_SANDBOX_HELPER`, and record `PROMETHE_SANDBOX_HELPER_SHA256` when
used. If the helper is absent, the expected result for process tools is
`SANDBOX BACKEND_UNAVAILABLE`.

## Mandatory tests

| ID | Test | Expected result |
|---|---|---|
| S01 | Start with no helper installed | Gateway starts if otherwise valid; process tools are unavailable and no local process is started. |
| S02 | Run a permitted executable with literal arguments | It runs only through the native helper, returns bounded stdout/stderr and an exit code. |
| S03 | Pass `sh -c`, `cmd /c`, a pipe and a redirection as arguments | The request is rejected; no shell interpreter is started. |
| S04 | Use `..`, an absolute outside path and a changed working directory outside the workspace | Each request is rejected with a policy/path error. |
| S05 | Read the outside file | Linux denies the read. Windows permits only locations already readable by the dedicated account; setup must not add an ACE to the owner's profile. Record the selected backend and inspect the real ACL. |
| S06 | Write the outside file | Write is denied and the file checksum is unchanged. |
| S07 | Read/write through a Linux or macOS symlink escaping the workspace | The workspace broker or native profile rejects the operation. |
| S08 | Read/write through a Windows junction or directory symlink escaping the workspace | The operation is rejected and the outside target is unchanged. |
| S09 | Write `.git`, `.promethe`, `.codex` and `.agents` | Each protected metadata path is denied, including through a normalized spelling. |
| S10 | Exceed maximum output bytes | Output is truncated or the result is marked with the output-limit error; memory is not unbounded. |
| S11 | Run a command past the configured timeout | The process group/job is terminated and the result reports a timeout. No child remains. |
| S12 | Cancel a running command | Cancellation terminates the complete process group/job and returns a cancellation error. |
| S13 | Fork/ spawn above the process limit | Creation is denied or the execution is terminated; the host remains responsive. |
| S14 | Allocate above the memory limit | The execution is terminated or denied; the gateway remains available. |
| S15 | Connect to the public internet, loopback, private, link-local and Unix/local sockets | All connections fail because network is disabled. No automatic retry changes the result. |
| S16 | Put an API key, cookie or token in an environment variable or argument | The request is rejected; the value does not appear in gateway/helper logs. |
| S17 | Request `ALLOWLIST` or change network settings while using selected roots or `FULL_ACCESS` | The request is rejected or remains `OFF`; no network access is enabled and no silent downgrade occurs. |
| S18 | Change the permission profile from a remote owner session to `FULL_ACCESS` | The API returns forbidden; remote access cannot grant full local file access. |
| S19 | Submit an approval, mutate one argument, then reuse the approval | The mutated request requires a new approval. The old grant does not match. |
| S20 | Exercise ONCE, SESSION and PERSISTENT approval scopes | ONCE is consumed, SESSION expires/revokes with the session, and PERSISTENT is offered only for an exact CONFIG_CHANGE fingerprint, survives a gateway restart, and remains effective until local-owner revocation. Deny always wins. |
| S21 | Start MCP stdio | It fails closed with `SANDBOX_PROTOCOL_V2_REQUIRED`; no unmanaged stdio process is created. |
| S22 | Start MCP over an implemented network transport | It follows its own authenticated gateway/network policy; this test does not imply stdio support. |
| S23 | Run the Linux helper self-test | On a Linux target with compatible Bubblewrap/seccomp, status is available and self-test passes; otherwise process tools remain unavailable. |
| S24 | Run the macOS helper self-test | The helper reports `BACKEND_UNAVAILABLE` until detached-descendant confinement is certified; no process starts. |
| S25 | Click Install sandbox from an ordinary Windows Desktop session, then cancel UAC | The app remains responsive, reports setup cancellation and leaves process tools unavailable without partial unsafe enablement. |
| S26 | Click Install sandbox and approve UAC, then inspect status, manifest and ACLs | Dedicated identities, AppContainer without network capabilities, protected runner, ACLs, firewall/credential setup and Job Object prerequisites are present; the automatic self-test passes. Record target-machine evidence. |
| S27 | Remove or corrupt the Windows setup manifest | The helper reports setup unavailable; it does not use the unelevated backend. |
| S28 | Click Repair sandbox after corrupting the manifest or changing the workspace | Setup is repaired idempotently with one UAC prompt and the automatic helper self-test passes. |
| S29 | Run elevated uninstall | The setup identities, group, owner/sandbox AppContainer profiles, credentials and temporary setup data are removed; workspace files remain. |
| S30 | Kill the gateway during a running execution | The child is cleaned up by the helper/job/process group; restart reports a clean sandbox status. |
| S31 | Start two concurrent executions and try to connect one to the other runner pipe | Windows executes them serially. The broker rejects every client whose PID is not the suspended runner it created; neither request nor response crosses executions. |
| S32 | Produce recognizable stdout/stderr concurrently, then enumerate the sandbox temp directory | Each response contains only its own bounded output and no shared output capture file exists. |
| S33 | Start a long command, then run Repair sandbox | The helper and complete active job are stopped before ACL or firewall changes; new process requests remain unavailable until setup and self-test finish. |
| S34 | Change the registered workspace and approve Repair, including once with the old volume disconnected | The writer SID rotates, so the new workspace is the only writable root even after the old volume returns. A different Windows owner is rejected until uninstall/reinstall. |
| S35 | Disable or remove a Promethe firewall rule after a passing self-test | The AppContainer still blocks TCP/UDP loopback and non-loopback traffic. Repair restores the defense-in-depth firewall rules; removing the AppContainer network isolation must make the self-test fail closed. |
| S36 | Try to delete/rename each protected directory through its parent, then repeat with a junction as workspace or protected path | Parent `DELETE_CHILD` cannot bypass the explicit deny, and setup rejects every tested reparse point without changing ACLs outside the workspace. |

## Selected roots and local full access

| ID | Test | Expected result |
|---|---|---|
| S37 | Start with an empty workspace and ask the agent to list it | The request succeeds through the normal file-tool path and reports no files; no error is inferred from the empty result. |
| S38 | Add a readable directory from local Desktop settings and list/read a disposable file there | The selected directory is visible to file tools after reload; the workspace and selected root are canonicalized, and a remote session cannot add the root. |
| S39 | Add a writable selected directory and create a disposable file there | The write succeeds only after approval; a root configured only as writable is rejected unless it is also readable. |
| S40 | Attempt traversal, symlink/junction escape and access to `.git`, `.promethe`, `.codex` or `.agents` under a selected root | Every escape or protected-path mutation is denied and outside/protected data is unchanged. |
| S41 | Enable `FULL_ACCESS` from local Desktop settings | A clear warning is shown; after confirmation, file tools can access a disposable path outside configured roots only after approval. |
| S42 | Use `FULL_ACCESS` from a remote session, channel or webhook | The request is forbidden and no file boundary changes. |
| S43 | In `FULL_ACCESS`, run a command/code tool against a path outside the workspace | The process sandbox still rejects the path; `FULL_ACCESS` does not expand command or code execution roots. |
| S44 | In `FULL_ACCESS`, inspect network behavior and perform a file mutation | Network remains disabled; the file mutation requires approval and its approval fingerprint includes the active full-access policy. |
| S45 | Switch from `FULL_ACCESS` back to `WORKSPACE_WRITE`, then access the former outside path | The path is denied again unless it is explicitly selected; previously granted approvals do not bypass the new file boundary. |

## Short security recipe

1. Use a disposable workspace and disposable selected directories; verify that
   the initial workspace may be empty.
2. Keep `SANDBOX_NETWORK_MODE=OFF`, use `WORKSPACE_WRITE` by default, and add
   only the smallest readable/writable JSON-array roots needed for the task.
3. From a local Desktop session, enable `FULL_ACCESS` only for a trusted,
   temporary file operation, confirm the warning, and approve each mutation.
4. Verify that commands remain workspace-confined, remote sessions cannot
   enable full access, protected metadata remains blocked, and no secrets or
   file contents appear in logs.
5. Return to `WORKSPACE_WRITE` or `READ_ONLY` and repeat one denied-path test
   before releasing the configuration.

## Credential persistence regression

Use disposable provider credentials and redact all evidence.

| ID | Test | Expected result |
|---|---|---|
| C01 | Configure the gateway API key, one LLM provider, one channel and one voice provider; then change only the sandbox profile in Desktop settings | Every previously configured value remains usable after save and gateway reload. |
| C02 | Change only one provider key, restart Desktop and the gateway, then exercise the other configured integrations | The changed key is active and unrelated credentials or runtime settings are preserved. |
| C03 | Open settings, leave masked secret placeholders unchanged and save | Masked placeholders are never persisted as real secret values; existing secrets remain valid. |
| C04 | Submit an unknown configuration key through `PUT /api/v1/config/env/{key}` | The gateway returns `400`, does not report false success and does not modify `credentials.json`. |
| C05 | Interrupt the process repeatedly while saving settings, then restart | `credentials.json` remains valid JSON and contains either the previous or complete new state, never a partial document. |
| C06 | Trigger two concurrent authenticated configuration updates | Both accepted updates are present after reload; neither silently overwrites the other. |

## Evidence and release gates

Mark a test `PASS` only with target-machine evidence. A Kotlin mock, Rust
compile, or static inspection is useful supporting evidence but does not
certify OS isolation. Any S01, S03-S18, S21, or platform self-test failure is
a release blocker for process tools. Keep the feature unavailable when a
backend is missing, unsupported, unconfigured or fails its self-test.

Do not mark Linux, macOS or Windows runtime support as certified merely because
the source compiles on another operating system. Certification requires the
matching platform self-test plus the relevant manual tests above.
