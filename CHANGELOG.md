# Changelog

All notable changes to the Promethe project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-07-12

Initial public release for the self-hosted, single-owner deployment model.

### Release profiles

- **STABLE** — recommended for normal use and production self-hosting.
- **BETA** — preview features that may change before the next stable release.
- **LAB** — experimental features for evaluation and development.

### Supported platforms

- Desktop: Windows, macOS and Linux.
- Web: `wasmJs` client.
- Android and iOS targets are not supported release platforms in 1.0.0.

### Changed

- **Breaking:** Default `approvalMode` changed from `auto` to `dangerous` —
  tool calls to `write_file`, `execute_code`, `browser_eval`, `send_email`, and
  `twilio` now require explicit user approval by default.
- **Breaking:** Default `executionBackend` in the desktop UI changed from
  `local` to `docker` — commands run inside a Docker container by default.
- **Breaking:** `execute_code` tool is now gated by the approval gate and
  routed through the configured execution backend instead of running directly
  on the host.
- **Breaking:** the LLM fallback chain (silent model swap on failure) is now
  opt-in via `FALLBACK_CHAIN_ENABLED` (default `false`) — LLM failures
  propagate as errors instead of transparently switching models.

### Fixed

- Tool approval gate is now properly wired to `ActionExecutor` — it was created
  but never injected, so approval checks were silently skipped.
- 5 of 6 `DANGEROUS_TOOLS` entries corrected to match actual tool registration
  names (e.g., `writeFile` → `write_file`).
- Fork-bomb regex in `GuardrailHook` fixed to actually match the canonical
  `:(){ :|:& };:` pattern.
- `execute_code` no longer falls back silently to unsandboxed local execution
  when the configured backend is unavailable or unsupported — it refuses with
  an explicit error instead (`EXEC_BACKEND=local` must be set deliberately).
- The `docker` execution backend is now actually implemented for
  `execute_code`: scripts run in an isolated container (`--network none`,
  memory/CPU limits, read-only mount), with per-language default images
  overridable via `DOCKER_IMAGE`.
- Removed the duplicate approval prompt for `execute_code` — approval is
  enforced once at the dispatch layer (`ActionExecutor`), which previously
  caused every code execution to require two approvals.
- Bash script blocklist check in `execute_code` could never trigger (it
  checked the literal string `bash -c <code>` against command prefixes);
  it now checks each script line against the blocklist.
- `VideoGenerateTool`/`VideoAnalyzeTool` were registered twice at bootstrap
  (in both registration phases); now registered once, guarded by a
  duplicate-registration test.
- `--allow-local-exec` / `ALLOW_LOCAL_EXEC` had no effect (it set a system
  property that the config layer never read); it is now a top-priority
  process-level config override, and `--daemon`/`--cli` modes now load the
  same credentials-aware config as desktop mode.
- `speech_to_text` silently called OpenAI Whisper regardless of the configured
  STT provider; it now honors `STT_PROVIDER`/`STT_BASE_URL`/`STT_API_KEY` for
  OpenAI-compatible endpoints and refuses unsupported providers explicitly.
- `GET /status/metrics` counters (`MetricsHook`) are now thread-safe.

### Added

- `GuardrailPresets` (`DEV_SAFE`, `PRODUCTION_STRICT`) with comprehensive
  dangerous-command patterns covering fork bombs, disk wipes, privilege
  escalation, and network exfiltration.
- `SECURITY.md` vulnerability disclosure policy (`.github/SECURITY.md`).
- `CONTRIBUTING.md` contributor guidelines.
- `CODE_OF_CONDUCT.md` (Contributor Covenant 2.1).
- Security unit tests for the approval gate and guardrail pattern matching.
- 12 subsystem docs (`MEMORY`, `RAG`, `MCP`, `SCHEDULER`, `GEPA`, `PROVIDERS`,
  `INTEGRATIONS`, `OBSERVABILITY`, `RESILIENCE`, `A2A`, `A2UI`, `UI`) and a
  docs-vs-code consistency checker (`docs/verify-docs.ps1`); the full
  documentation corpus is now in English.
- Route integration tests for sessions, skills, context files, memory facts,
  ACP (agent card / invoke / health), goals, and env config.
- Optional OAuth routes behind `OAUTH_ENABLED=true` +
  `OAUTH_<PROVIDER>_CLIENT_ID`/`_CLIENT_SECRET` (github, google); disabled by
  default.
- Full FR/EN UI localization (Compose resources) with a language selector in
  Settings — works on the supported Desktop and Web clients.

### Migration

Existing installs that already have a `credentials.json` will keep their saved
settings and are **not** affected by the default changes. To explicitly restore
the previous (less restrictive) behavior:

```properties
APPROVAL_MODE=auto
EXEC_BACKEND=local
```

> [!WARNING]
> Running with `approvalMode=auto` and `executionBackend=local` disables both
> the approval gate and the Docker sandbox. **Do not use this configuration
> with untrusted inputs or in production.**

[1.0.0]: https://github.com/gregboero/promethe/releases/tag/v1.0.0
