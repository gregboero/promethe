CREATE TABLE IF NOT EXISTS resource_governors (
    root_run_id VARCHAR(160) PRIMARY KEY NOT NULL,
    max_tokens BIGINT NOT NULL,
    max_cost_dollars DOUBLE NOT NULL,
    max_llm_calls INTEGER NOT NULL,
    max_tool_starts INTEGER NOT NULL,
    max_sub_agents INTEGER NOT NULL,
    max_duration_ms BIGINT NOT NULL,
    started_at BIGINT NOT NULL,
    tokens_used BIGINT NOT NULL DEFAULT 0,
    cost_dollars DOUBLE NOT NULL DEFAULT 0.0,
    llm_calls_started INTEGER NOT NULL DEFAULT 0,
    tools_started INTEGER NOT NULL DEFAULT 0,
    sub_agents_started INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS resource_governors_updated_at_idx
    ON resource_governors(updated_at);

CREATE TABLE IF NOT EXISTS resource_governor_bindings (
    session_id VARCHAR(255) PRIMARY KEY NOT NULL,
    root_run_id VARCHAR(160) NOT NULL,
    run_id VARCHAR(160),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS resource_governor_bindings_root_run_id_idx
    ON resource_governor_bindings(root_run_id);

CREATE UNIQUE INDEX IF NOT EXISTS resource_governor_bindings_run_id_idx
    ON resource_governor_bindings(run_id);
