# Security Policy

> **Note:** This file is the *vulnerability disclosure policy* for the Promethe
> project. The security *architecture* documentation (sandboxing, guardrails,
> approval gates) lives in the public
> [`docs/SECURITY.md`](https://github.com/gregboero/promethe/blob/main/docs/SECURITY.md).

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | :white_check_mark: |

Only the latest patch release of the supported 1.0 minor version receives
security fixes. Older minor versions are not backported.

Prométhé is self-hosted and single-owner: reports should include the affected
deployment and owner-controlled configuration where relevant. Prométhé does not
claim artifact signing or certification unless a specific release explicitly
documents an automated mechanism for it.

## Reporting a Vulnerability

**Please do not open a public issue for security vulnerabilities.**

Use [GitHub Private Vulnerability Reporting](https://github.com/gregboero/promethe/security/advisories/new)
to submit your report. This ensures the details stay confidential until a fix is
available.

### What to Include

- A clear description of the vulnerability and its potential impact.
- Steps to reproduce, including any proof-of-concept code or configuration.
- The affected version(s) and component(s) (e.g., gateway, shared modules, desktop UI).
- Any suggested mitigation or fix, if you have one.

### Response Timeline

| Milestone              | Target          |
| ---------------------- | --------------- |
| Initial acknowledgment | **72 hours**    |
| Triage & severity      | 5 business days |
| Fix or mitigation      | Best effort, varies by severity |

We will keep you informed of our progress throughout the process.

## Scope

### In Scope

- **Core agent** — the gateway server, shared modules, and desktop UI.
- **Default-mode behavior** — configurations shipped out of the box
  (`approvalMode=dangerous`, `executionBackend=docker`).
- Authentication, authorization, and session handling in the gateway.
- MCP and A2A protocol implementations.
- Guardrail and approval-gate bypasses.
- Dependency vulnerabilities that are reachable in production paths.

### Out of Scope

- **Local execution with sandboxing explicitly disabled.** If a user sets
  `executionBackend=local` and `approvalMode=auto` (overriding the secure
  defaults), they are knowingly opting out of the sandbox. Vulnerabilities
  that require this configuration are informational only.
- Third-party LLM provider APIs — report those to the respective provider.
- Social engineering or phishing attacks against project maintainers.
- Denial-of-service attacks against personal development instances.

## Safe Harbor

We consider security research conducted in good faith to be authorized and
welcome it. We will not pursue legal action against researchers who:

1. Make a good-faith effort to avoid privacy violations, data destruction, and
   service disruption.
2. Only interact with accounts they own or with explicit permission of the
   account holder.
3. Report vulnerabilities through the process described above.
4. Allow reasonable time for a fix before any public disclosure.

## Disclosure

We follow [coordinated disclosure](https://en.wikipedia.org/wiki/Coordinated_vulnerability_disclosure).
Once a fix is released, we will publish a security advisory on GitHub and credit
the reporter (unless they prefer to remain anonymous).

## Contact

For questions about this policy, open a regular (non-security) GitHub issue or
reach out to the maintainer, **Grégory Boero-Teyssier**, through GitHub.
