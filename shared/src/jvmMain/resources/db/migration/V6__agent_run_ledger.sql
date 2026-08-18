CREATE TABLE IF NOT EXISTS agent_runs (
    run_id VARCHAR(160) PRIMARY KEY NOT NULL,
    parent_run_id VARCHAR(160),
    session_id VARCHAR(255) NOT NULL,
    origin VARCHAR(64) NOT NULL,
    project_id VARCHAR(64),
    status VARCHAR(32) NOT NULL,
    step_count INTEGER NOT NULL DEFAULT 0,
    last_step_id VARCHAR(200),
    error_code VARCHAR(128),
    created_at BIGINT NOT NULL,
    started_at BIGINT,
    finished_at BIGINT,
    updated_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS agent_runs_session_idx ON agent_runs(session_id);
CREATE INDEX IF NOT EXISTS agent_runs_status_idx ON agent_runs(status);
CREATE INDEX IF NOT EXISTS agent_runs_parent_idx ON agent_runs(parent_run_id);
