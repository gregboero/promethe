-- Exposed adds missing columns before Flyway runs. This idempotent migration
-- records the durable project/workspace contract for managed databases.
CREATE TABLE IF NOT EXISTS projects (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    instructions TEXT NOT NULL DEFAULT '',
    workspace_path VARCHAR(500) NOT NULL UNIQUE,
    memory_namespace VARCHAR(255) NOT NULL UNIQUE,
    archived BOOLEAN NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);
