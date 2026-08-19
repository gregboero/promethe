-- Exposed adds the nullable fingerprint columns before Flyway runs. The index
-- makes request-bound recovery lookups auditable while keeping this migration
-- idempotent for both fresh and upgraded databases.
CREATE INDEX IF NOT EXISTS agent_runs_request_fingerprint_idx
    ON agent_runs(request_fingerprint);
