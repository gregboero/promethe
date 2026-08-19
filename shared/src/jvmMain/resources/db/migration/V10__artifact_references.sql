-- Exposed adds the nullable artifact hash columns before Flyway runs. These
-- indexes keep intent replay and run audit lookups efficient on upgraded data.
CREATE INDEX IF NOT EXISTS tool_intents_artifact_hash_idx
    ON tool_intents(artifact_hash);

CREATE INDEX IF NOT EXISTS agent_run_events_artifact_hash_idx
    ON agent_run_events(artifact_hash);
