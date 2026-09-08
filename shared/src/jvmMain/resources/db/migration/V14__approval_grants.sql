CREATE TABLE IF NOT EXISTS approval_grants (
    id TEXT PRIMARY KEY NOT NULL,
    fingerprint TEXT NOT NULL,
    allowed INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS approval_grants_fingerprint_idx ON approval_grants(fingerprint);
CREATE INDEX IF NOT EXISTS approval_grants_expires_idx ON approval_grants(expires_at);
