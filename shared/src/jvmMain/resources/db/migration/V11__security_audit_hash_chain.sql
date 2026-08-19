-- Exposed creates the hash columns before Flyway runs. The triggers make the
-- security journal append-only for persistent SQLite databases.
CREATE UNIQUE INDEX IF NOT EXISTS security_audit_logs_entry_hash_idx
    ON security_audit_logs(entry_hash)
    WHERE entry_hash <> '';

CREATE TRIGGER IF NOT EXISTS security_audit_logs_no_update
BEFORE UPDATE ON security_audit_logs
BEGIN
    SELECT RAISE(ABORT, 'security_audit_logs is append-only');
END;

CREATE TRIGGER IF NOT EXISTS security_audit_logs_no_delete
BEFORE DELETE ON security_audit_logs
BEGIN
    SELECT RAISE(ABORT, 'security_audit_logs is append-only');
END;
