CREATE TABLE IF NOT EXISTS mcp_tasks (
    task_id TEXT PRIMARY KEY NOT NULL,
    owner_session_id TEXT NOT NULL,
    method TEXT NOT NULL,
    resource_name TEXT NOT NULL,
    run_id TEXT,
    status TEXT NOT NULL,
    status_message TEXT,
    result_json TEXT,
    error_json TEXT,
    input_requests_json TEXT,
    created_at INTEGER NOT NULL,
    last_updated_at INTEGER NOT NULL,
    ttl_ms INTEGER,
    poll_interval_ms INTEGER
);

CREATE INDEX IF NOT EXISTS mcp_tasks_owner_idx ON mcp_tasks(owner_session_id);
CREATE INDEX IF NOT EXISTS mcp_tasks_status_idx ON mcp_tasks(status);
CREATE INDEX IF NOT EXISTS mcp_tasks_updated_idx ON mcp_tasks(last_updated_at);
CREATE INDEX IF NOT EXISTS mcp_tasks_run_idx ON mcp_tasks(run_id);
