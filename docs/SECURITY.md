# Security

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
or token values.

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
project root. Its execution backend is a Docker sandbox with no network, reduced capabilities, a read-only
root filesystem, resource limits, and a workspace-only mount.

Mandatory approval also covers destructive file operations, Docker, process termination, Git writes,
external sending tools, browser evaluation, and configuration changes. `APPROVAL_MODE=auto` does not
bypass this mandatory set.

## Public Surface

`GET /health` deliberately returns only `{"status":"ok"}`. Operational status and diagnostics are protected
endpoints. The former raw HTTP command endpoint and the legacy remote-owner setup route intentionally
return `404`.
