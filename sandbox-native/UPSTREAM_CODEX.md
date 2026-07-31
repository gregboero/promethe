# Codex sandbox provenance

Promethe's native sandbox follows the architecture and security boundaries of
the OpenAI Codex sandbox. The implementation is rewritten around Promethe's
versioned JSONL IPC protocol; no Codex source file is vendored verbatim.

- Upstream repository: https://github.com/openai/codex
- Reference commit: `07490c75234ed3c63291a8eb7629f04f647038fa`
- Upstream license: Apache License 2.0
- Reference date: 2026-07-28

Reference modules:

- Linux: `codex-rs/linux-sandbox/src/` and `codex-rs/sandboxing/src/bwrap.rs`
- macOS: `codex-rs/sandboxing/src/seatbelt.rs` and the adjacent SBPL policies
- Windows: `codex-rs/windows-sandbox-rs/src/`, especially elevated execution,
  ACL, restricted token, Job Object, DPAPI, and WFP setup modules

Promethe-specific changes:

- A small, provider-neutral helper with a stable JSONL request/response schema.
- Explicit fail-closed behavior for unsupported network and interactive modes.
- Promethe workspace metadata protections for `.git`, `.promethe`, `.codex`,
  and `.agents`.
- Gateway-owned approval fingerprints, status reporting, and helper discovery.
- No Codex CLI installation or runtime dependency.
