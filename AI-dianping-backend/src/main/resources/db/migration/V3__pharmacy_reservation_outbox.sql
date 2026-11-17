CREATE TABLE seckill_request (
    request_id CHAR(36) NOT NULL PRIMARY KEY,
    activity_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    quantity INT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'INITIALIZING',
    reservation_state VARCHAR(24) NOT NULL DEFAULT 'NONE',
    order_no VARCHAR(40) NULL,
    expires_at DATETIME NOT NULL,
    failure_code VARCHAR(48) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    active_user_id BIGINT UNSIGNED GENERATED ALWAYS AS
        (CASE WHEN status IN ('INITIALIZING','PROCESSING','SUCCESS') THEN user_id ELSE NULL END) STORED,
    UNIQUE KEY uk_request_activity_user (activity_id, active_user_id),
    KEY idx_request_reconcile (status, expires_at),
    KEY idx_request_user (user_id, created_at),
    CONSTRAINT fk_request_activity FOREIGN KEY (activity_id) REFERENCES seckill_activity(id),
    CONSTRAINT ck_request_quantity CHECK (quantity > 0),
    CONSTRAINT ck_request_status CHECK (status IN ('INITIALIZING','PROCESSING','SUCCESS','FAILED','EXPIRED')),
    CONSTRAINT ck_request_reservation CHECK (reservation_state IN ('NONE','RESERVED','CONSUMED','RELEASE_PENDING','RELEASED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE pharmacy_order (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(40) NOT NULL,
    request_id CHAR(36) NOT NULL,
    activity_id BIGINT UNSIGNED NOT NULL,
    store_id BIGINT UNSIGNED NOT NULL,
    medicine_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    quantity INT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'RESERVED',
    pickup_expires_at DATETIME NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_pharmacy_order_no (order_no),
    UNIQUE KEY uk_pharmacy_order_request (request_id),
    KEY idx_order_user_created (user_id, created_at),
    KEY idx_order_expiration (status, pickup_expires_at),
    CONSTRAINT fk_order_request FOREIGN KEY (request_id) REFERENCES seckill_request(request_id),
    CONSTRAINT fk_order_activity FOREIGN KEY (activity_id) REFERENCES seckill_activity(id),
    CONSTRAINT fk_order_store_medicine FOREIGN KEY (store_id, medicine_id) REFERENCES pharmacy_medicine(store_id, id),
    CONSTRAINT ck_order_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_status CHECK (status IN ('RESERVED','COMPLETED','CANCELLED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE order_batch_reservation (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(40) NOT NULL,
    batch_id BIGINT UNSIGNED NOT NULL,
    quantity INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_order_batch_reservation (order_no, batch_id),
    CONSTRAINT fk_order_batch_order FOREIGN KEY (order_no) REFERENCES pharmacy_order(order_no),
    CONSTRAINT fk_order_batch_inventory FOREIGN KEY (batch_id) REFERENCES inventory_batch(id),
    CONSTRAINT ck_order_batch_quantity CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE outbox_event (
    id CHAR(36) NOT NULL PRIMARY KEY,
    dedupe_key VARCHAR(160) NOT NULL,
    aggregate_type VARCHAR(48) NOT NULL,
    aggregate_id VARCHAR(80) NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    available_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_by VARCHAR(80) NULL,
    locked_at DATETIME NULL,
    last_error VARCHAR(1000) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at DATETIME NULL,
    UNIQUE KEY uk_outbox_dedupe (dedupe_key),
    KEY idx_outbox_claim (status, available_at, created_at),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING','CLAIMED','SENT','DEAD')),
    CONSTRAINT ck_outbox_attempt CHECK (attempt_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
