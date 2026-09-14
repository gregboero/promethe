-- Exposed adds the columns before Flyway runs. This index keeps run-scoped
-- recovery queries efficient on persistent and upgraded SQLite databases.
CREATE INDEX IF NOT EXISTS messages_source_run_id_idx
    ON messages(source_run_id);
