-- Promethe Schema V1
-- Sessions, Messages (with FTS5), Feedbacks

-- Sessions
CREATE TABLE IF NOT EXISTS sessions (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    created_at BIGINT NOT NULL,
    metadata TEXT
);

-- Messages
CREATE TABLE IF NOT EXISTS messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id VARCHAR(255) NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    role VARCHAR(50) NOT NULL,
    content TEXT NOT NULL,
    timestamp BIGINT NOT NULL
);

-- Feedbacks
CREATE TABLE IF NOT EXISTS feedbacks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id VARCHAR(255) NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    score DOUBLE NOT NULL,
    comment TEXT,
    timestamp BIGINT NOT NULL
);

-- FTS5 full-text search index on messages
CREATE VIRTUAL TABLE IF NOT EXISTS message_search_index
    USING fts5(content, content='messages', content_rowid='id');

-- Auto-sync triggers for FTS5
CREATE TRIGGER IF NOT EXISTS after_message_insert
    AFTER INSERT ON messages BEGIN
    INSERT INTO message_search_index(rowid, content) VALUES (new.id, new.content);
END;

CREATE TRIGGER IF NOT EXISTS after_message_delete
    AFTER DELETE ON messages BEGIN
    DELETE FROM message_search_index WHERE rowid = old.id;
END;

CREATE TRIGGER IF NOT EXISTS after_message_update
    AFTER UPDATE ON messages BEGIN
    DELETE FROM message_search_index WHERE rowid = old.id;
    INSERT INTO message_search_index(rowid, content) VALUES (new.id, new.content);
END;
