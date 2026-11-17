CREATE TABLE dead_letter_event (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id CHAR(36) NOT NULL,
    dedupe_key VARCHAR(160) NOT NULL,
    aggregate_type VARCHAR(48) NOT NULL,
    aggregate_id VARCHAR(80) NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    payload JSON NOT NULL,
    attempt_count INT NOT NULL,
    last_error VARCHAR(1000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    replayed_at DATETIME NULL,
    UNIQUE KEY uk_dead_letter_event (event_id),
    KEY idx_dead_letter_status_created (status, created_at),
    CONSTRAINT fk_dead_letter_outbox FOREIGN KEY (event_id) REFERENCES outbox_event(id),
    CONSTRAINT ck_dead_letter_status CHECK (status IN ('OPEN','REPLAYED','IGNORED')),
    CONSTRAINT ck_dead_letter_attempt CHECK (attempt_count >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
