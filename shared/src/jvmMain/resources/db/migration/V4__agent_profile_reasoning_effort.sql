-- Exposed creates missing tables and columns before Flyway is invoked. Keeping
-- this migration idempotent lets Flyway record the schema version for both new
-- installations and databases upgraded through Exposed's compatibility path.
CREATE TABLE IF NOT EXISTS agent_profiles (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    name VARCHAR(500) NOT NULL,
    provider VARCHAR(100) NOT NULL DEFAULT 'openai',
    model VARCHAR(500) NOT NULL DEFAULT 'gpt-4o-mini',
    system_prompt TEXT NOT NULL DEFAULT '',
    tools TEXT NOT NULL DEFAULT '[]',
    skills TEXT NOT NULL DEFAULT '[]',
    max_iterations INTEGER NOT NULL DEFAULT 10,
    temperature DOUBLE NOT NULL DEFAULT 0.2,
    reasoning_effort VARCHAR(50) NOT NULL DEFAULT 'AUTO',
    is_system BOOLEAN NOT NULL DEFAULT 0,
    ephemeral BOOLEAN NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);
