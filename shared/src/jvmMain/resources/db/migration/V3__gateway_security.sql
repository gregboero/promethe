-- Gateway remote owner, opaque sessions, OAuth state/tokens and encrypted MCP configuration.
-- Runtime schema creation remains backward compatible through Exposed; this migration documents
-- the persistent schema for deployments managed by Flyway.

CREATE TABLE IF NOT EXISTS remote_owners (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS auth_sessions (
    id VARCHAR(128) NOT NULL PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL REFERENCES remote_owners(id),
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    last_seen_at BIGINT NOT NULL,
    revoked_at BIGINT
);

CREATE TABLE IF NOT EXISTS oauth_authorizations (
    state_hash VARCHAR(128) NOT NULL PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL REFERENCES remote_owners(id),
    provider VARCHAR(64) NOT NULL,
    redirect_uri TEXT NOT NULL,
    encrypted_verifier TEXT NOT NULL,
    expires_at BIGINT NOT NULL,
    consumed_at BIGINT
);

CREATE TABLE IF NOT EXISTS oauth_connections (
    owner_id VARCHAR(64) NOT NULL REFERENCES remote_owners(id),
    provider VARCHAR(64) NOT NULL,
    encrypted_tokens TEXT NOT NULL,
    expires_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (owner_id, provider)
);

CREATE TABLE IF NOT EXISTS mcp_server_configs (
    id VARCHAR(128) NOT NULL PRIMARY KEY,
    config_json TEXT NOT NULL,
    encrypted_secrets TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS security_audit_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_type VARCHAR(128) NOT NULL,
    actor VARCHAR(255) NOT NULL DEFAULT '',
    remote_address VARCHAR(255) NOT NULL DEFAULT '',
    detail TEXT NOT NULL DEFAULT '',
    created_at BIGINT NOT NULL
);
