-- Promethe Schema V2 — FTS5 index on user_facts for semantic recall

-- FTS5 full-text search index on user_facts
CREATE VIRTUAL TABLE IF NOT EXISTS user_facts_search
    USING fts5(fact, content='user_facts', content_rowid='id');

-- Auto-sync triggers for FTS5
CREATE TRIGGER IF NOT EXISTS after_user_fact_insert
    AFTER INSERT ON user_facts BEGIN
    INSERT INTO user_facts_search(rowid, fact) VALUES (new.id, new.fact);
END;

CREATE TRIGGER IF NOT EXISTS after_user_fact_delete
    AFTER DELETE ON user_facts BEGIN
    DELETE FROM user_facts_search WHERE rowid = old.id;
END;

CREATE TRIGGER IF NOT EXISTS after_user_fact_update
    AFTER UPDATE ON user_facts BEGIN
    DELETE FROM user_facts_search WHERE rowid = old.id;
    INSERT INTO user_facts_search(rowid, fact) VALUES (new.id, new.fact);
END;
