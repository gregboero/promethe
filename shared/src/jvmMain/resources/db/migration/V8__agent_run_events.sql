CREATE TABLE IF NOT EXISTS agent_run_events (
    event_id TEXT PRIMARY KEY NOT NULL,
    run_id TEXT NOT NULL,
    sequence INTEGER NOT NULL,
    event_type TEXT NOT NULL,
    parent_run_id TEXT,
    session_id TEXT,
    origin TEXT,
    project_id TEXT,
    step_id TEXT,
    step_count INTEGER,
    intent_id TEXT,
    idempotency_key_hash TEXT,
    invocation_hash TEXT,
    tool_name TEXT,
    risk TEXT,
    run_status TEXT,
    intent_status TEXT,
    result_hash TEXT,
    error_code TEXT,
    approval_id TEXT,
    approval_allowed INTEGER,
    approval_scope TEXT,
    created_at INTEGER NOT NULL,
    event_version INTEGER NOT NULL DEFAULT 1,
    UNIQUE(run_id, sequence)
);

CREATE INDEX IF NOT EXISTS agent_run_events_run_idx ON agent_run_events(run_id, sequence);
CREATE INDEX IF NOT EXISTS agent_run_events_intent_idx ON agent_run_events(intent_id);
CREATE INDEX IF NOT EXISTS agent_run_events_type_idx ON agent_run_events(event_type);
