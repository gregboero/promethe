# Sandbox Manual Test Matrix

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
| S05 | Read the outside file | Read is denied for the active profile. Do not infer this from a Kotlin unit test; verify on the target OS. |
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
| S17 | Request `FULL_ACCESS` or `ALLOWLIST` | The helper returns a policy/setup error. It must not silently downgrade or enable network. |
| S18 | Change the permission profile from a remote owner session to full local access | The API returns forbidden; remote access cannot grant full local privileges. |
| S19 | Submit an approval, mutate one argument, then reuse the approval | The mutated request requires a new approval. The old grant does not match. |
| S20 | Exercise ONCE, SESSION and PERSISTENT approval scopes | ONCE is consumed, SESSION expires/revokes with the session, and PERSISTENT lasts only for the current gateway process until durable persistence is implemented. Deny always wins. |
| S21 | Start MCP stdio | It fails closed with `SANDBOX_PROTOCOL_V2_REQUIRED`; no unmanaged stdio process is created. |
| S22 | Start MCP over an implemented network transport | It follows its own authenticated gateway/network policy; this test does not imply stdio support. |
| S23 | Run the Linux helper self-test | On a Linux target with compatible Bubblewrap/seccomp, status is available and self-test passes; otherwise process tools remain unavailable. |
| S24 | Run the macOS helper self-test | The helper reports `BACKEND_UNAVAILABLE` until detached-descendant confinement is certified; no process starts. |
| S25 | Run Windows setup as a standard user | Setup fails with an elevation error and makes no partial unsafe configuration. |
| S26 | Run Windows setup elevated, then inspect manifest and ACLs | Dedicated identities, ACLs, firewall/credential setup and Job Object prerequisites are present. Record evidence; this has not been run in the project environment. |
| S27 | Remove or corrupt the Windows setup manifest | The helper reports setup unavailable; it does not use the unelevated backend. |
| S28 | Repair Windows setup by rerunning elevated setup | Setup is repaired idempotently and the helper self-test can pass. |
| S29 | Run elevated uninstall | The setup identities, group, credentials and temporary setup data are removed; workspace files remain. |
| S30 | Kill the gateway during a running execution | The child is cleaned up by the helper/job/process group; restart reports a clean sandbox status. |

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
the source compiles on another operating system. The current project work did
not execute the Windows administrative scripts or the Linux/macOS native
self-tests.
