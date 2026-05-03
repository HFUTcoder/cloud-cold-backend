CREATE TABLE IF NOT EXISTS user_long_term_memory (
    id BIGINT PRIMARY KEY,
    memoryId VARCHAR(128) NOT NULL,
    userId BIGINT NOT NULL,
    memoryType VARCHAR(64) NOT NULL,
    title VARCHAR(255) NULL,
    content TEXT NOT NULL,
    summary VARCHAR(255) NULL,
    confidence DOUBLE NULL,
    importance DOUBLE NULL,
    status VARCHAR(32) NOT NULL,
    version INT NOT NULL DEFAULT 1,
    lastRetrievedAt DATETIME NULL,
    lastReinforcedAt DATETIME NULL,
    createTime DATETIME NOT NULL,
    updateTime DATETIME NOT NULL,
    isDelete TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_user_long_term_memory_memory_id (memoryId),
    KEY idx_user_long_term_memory_user_status (userId, status, isDelete)
);

CREATE TABLE IF NOT EXISTS user_long_term_memory_source_relation (
    id BIGINT PRIMARY KEY,
    memoryId VARCHAR(128) NOT NULL,
    userId BIGINT NOT NULL,
    conversationId VARCHAR(128) NULL,
    historyId BIGINT NULL,
    createTime DATETIME NOT NULL,
    updateTime DATETIME NOT NULL,
    isDelete TINYINT NOT NULL DEFAULT 0,
    KEY idx_user_long_term_memory_source_memory (memoryId, isDelete),
    KEY idx_user_long_term_memory_source_user_history (userId, historyId, isDelete)
);
