# Security

> **Personal research sandbox — not for production / Projet expérimental — non destiné à la production.** See [project status / statut du projet](EXPERIMENTAL_STATUS.md).

## Secure Defaults

Promethe is a single-owner personal gateway. A normal installation listens only on
`GATEWAY_BIND_HOST=127.0.0.1`; Docker publishes `127.0.0.1:8080:8080` by default. Remote access is
opt-in through `REMOTE_ACCESS_ENABLED=true` and should be placed behind HTTPS.

The gateway refuses to start when remote access or OAuth is enabled without a valid
`PROMETHE_MASTER_KEY`. The key must be Base64 and decode to exactly 32 bytes. It is read only from the
process environment and is never persisted by Promethe.

## Gateway Authentication

### Local administrative key

The generated `pk-prom-` key is sent as `Authorization: Bearer <key>`. It is accepted only from a
loopback peer. It is used by the embedded Desktop gateway and to bootstrap the remote owner; it is not
a remote credential.

### Owner sessions

Remote owners authenticate with `POST /auth/login`. Passwords are stored as Argon2id hashes. A successful
login issues an opaque `pss_` token with a configurable lifetime (`REMOTE_SESSION_TTL_MINUTES`, 15-1440).
Only a SHA-256 digest of that random token is stored in SQLite. Sessions can expire, be revoked at logout,
or all be revoked by replacing the owner.

Native clients keep a session token only in memory. Browser clients receive a `Secure`, `HttpOnly`,
`SameSite=Strict` cookie and do not receive the token in the response body. URL query credentials are
not accepted.

### Owner bootstrap

`PUT /api/v1/security/remote-owner` is the only owner-creation route. It requires remote access to be
enabled, a loopback connection, and the local administrative key. This prevents a network caller from
taking over a newly exposed gateway.

Login failures are rate-limited per remote address. Authentication, owner changes, session logout,
OAuth events, and MCP configuration changes are written to the security audit log without credentials
or token values. New entries are append-only and linked to the previous entry with SHA-256; startup and
diagnostic code can verify the chain. This detects database alteration but is not a blockchain, an external
timestamp, or a substitute for protected backups.

## OAuth and Persisted Secrets

OAuth is limited to GitHub and Google Calendar in this release. Starting an OAuth flow requires an owner
session. The callback accepts only the provider code and a one-use signed state value; the server uses a
fixed `OAUTH_REDIRECT_URI` and PKCE. OAuth tokens are AES-256-GCM encrypted in SQLite and refreshed
server-side when possible.

UI-managed MCP headers and environment values use the same versioned AES-256-GCM envelope. `MCP_SERVERS`
remains process-owned and takes precedence over encrypted UI configuration.

Regular local provider configuration still lives in `~/.promethe/credentials.json` with best-effort
owner-only file permissions. Do not commit that file, and prefer environment variables for deployments.

## CORS and MCP Origins

`CORS_ALLOWED_ORIGINS` is a comma-separated allow-list of absolute HTTP(S) origins. Wildcards, paths,
credentials, queries, and fragments are rejected. With no configured origin, browser cross-origin access
is denied. The MCP HTTP endpoint applies the same policy to its `Origin` header.

## Tool Execution and Approval

The former raw HTTP command execution endpoint has been removed. The `shell` tool remains available only
through a structured interface (`executable` plus literal `arguments`) and always requires human approval.
It rejects shell interpreters, pipes, redirects, command substitution, and workspaces outside the configured
project root. Its execution backend is the native sandbox helper; unsupported or failed backends deny
process execution instead of falling back to an unmanaged process. Linux uses Bubblewrap/seccomp. Windows
uses dedicated identities, a restricted token, an AppContainer without network capabilities, workspace
ACLs, Job Objects and firewall rules after a
loopback-only UAC setup and behavioral self-test. Setup never broadens ACLs on the owner's Windows profile:
the dedicated identity can read only locations already readable to it plus the registered workspace, can
write only inside that workspace, and has network access disabled.

Mandatory approval also covers destructive file operations, Docker, process termination, Git writes,
external sending tools, browser evaluation, and configuration changes. `APPROVAL_MODE=auto` does not
bypass this mandatory set.

Every tool invocation is also evaluated by the versioned `PolicyKernel`. Immutable system rules deny
unknown tools, prevent untrusted content from initiating effects, prevent `SECRET` data from using egress,
and preserve owner-only restrictions. Organization, project and session rules may only strengthen these
rules. Policy audit entries contain the tool name, origin, contract metadata and an invocation fingerprint;
raw arguments and secrets are not stored. MCP and legacy voice schema exports omit tools denied by this
policy. Destination-level HTTP allow-lists and explicit owner identity propagation remain required before
the egress and owner policy work can be considered complete.

Remote tool observations are passed through `UntrustedReader` before they return to the model. External
markup is escaped, provenance is retained and `PrivilegedController` taints the remainder of that run.
After a web, MCP, ACP, integration, plugin or local-agent observation, the same run may continue using
trusted local read-only tools, but it cannot write, execute, control a device or perform further egress.
A contaminated run is also excluded from automatic skill synthesis and memory fact extraction. A new
explicit owner turn starts a new trust decision. This containment reduces indirect prompt-injection risk;
it does not claim that arbitrary model output has been proven safe.

## Discord Access and Knowledge Capture

Discord access can be restricted with `DISCORD_ALLOWED_USER_IDS`. An empty value preserves the
backward-compatible behavior; a non-empty value fails closed and only valid listed user IDs may invoke
the agent. The restriction applies to the persistent Gateway and signed slash-command interactions.

Passive Discord capture is disabled by default. `DISCORD_KNOWLEDGE_CHANNEL_IDS` explicitly selects the
channels whose new messages are stored as JSON-LD under `~/.promethe/knowledge/discord/`. Archived
conversation is injected only for the same channel, is escaped and labelled as untrusted external data,
and cannot grant permissions or bypass tool approvals. Anyone allowed to invoke Promethe in such a
channel may receive answers derived from that channel's archive, so configure channel permissions and
the user allow-list together. Remove the channel ID to stop future capture; existing archive files remain
until the owner deletes them locally.

The owner may also maintain a structured live policy through authenticated `/api/v1/channels/discord/policy`
routes or the `discord_policy` agent tool. Runtime rules are stored in SQLite and can allow or deny a user,
limit an allowed user to deterministic subject phrases, opt a channel into Open Knowledge capture and bind
that channel to a Promethe project. Dynamic `DENY` rules override static access. Policy mutations are
classified as `CONFIG_CHANGE`, require human approval when requested conversationally and are blocked for
all channel, webhook, MCP, ACP, voice, scheduler and autonomous origins.

Effectful requests originating from Discord can be routed to the explicit
`DISCORD_APPROVER_USER_IDS` list as private messages. Arguments are redacted before delivery and the
clicking Discord user is checked against the live list. Persistent approval is offered only for exact
`CONFIG_CHANGE` fingerprints; process and local coding-agent approvals cannot be made persistent from
Discord. These grants store only fingerprints in SQLite, survive gateway restarts and remain revocable by
the local loopback owner.

## Public Surface

`GET /health` deliberately returns only `{"status":"ok"}`. Operational status and diagnostics are protected
endpoints. The former raw HTTP command endpoint and the legacy remote-owner setup route intentionally
return `404`.
