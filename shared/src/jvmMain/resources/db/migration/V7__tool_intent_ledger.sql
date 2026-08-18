CREATE TABLE IF NOT EXISTS tool_intents (
    intent_id TEXT PRIMARY KEY NOT NULL,
    idempotency_key_hash TEXT NOT NULL UNIQUE,
    invocation_hash TEXT NOT NULL,
    run_id TEXT,
    step_id TEXT,
    session_id TEXT NOT NULL,
    tool_name TEXT NOT NULL,
    risk TEXT NOT NULL,
    status TEXT NOT NULL,
    result_hash TEXT,
    error_code TEXT,
    created_at INTEGER NOT NULL,
    started_at INTEGER,
    finished_at INTEGER,
    updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS tool_intents_run_idx ON tool_intents(run_id);
CREATE INDEX IF NOT EXISTS tool_intents_status_idx ON tool_intents(status);
