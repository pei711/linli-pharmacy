ALTER TABLE inventory_batch
    ADD UNIQUE KEY uk_batch_store_medicine_id (store_id, medicine_id, id);

CREATE TABLE seckill_activity (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT UNSIGNED NOT NULL,
    medicine_id BIGINT UNSIGNED NOT NULL,
    title VARCHAR(160) NOT NULL,
    total_qty INT NOT NULL,
    remaining_qty INT NOT NULL,
    per_user_limit INT NOT NULL DEFAULT 1,
    start_at DATETIME NOT NULL,
    end_at DATETIME NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_activity_store_id (store_id, id),
    KEY idx_activity_public (status, start_at, end_at),
    CONSTRAINT fk_activity_store FOREIGN KEY (store_id) REFERENCES tb_shop(id),
    CONSTRAINT fk_activity_medicine FOREIGN KEY (store_id, medicine_id) REFERENCES pharmacy_medicine(store_id, id),
    CONSTRAINT ck_activity_quantity CHECK (total_qty > 0 AND remaining_qty >= 0 AND remaining_qty <= total_qty AND per_user_limit > 0),
    CONSTRAINT ck_activity_status CHECK (status IN ('DRAFT', 'PUBLISHING', 'PUBLISHED', 'PAUSED', 'ENDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE seckill_activity_batch (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    activity_id BIGINT UNSIGNED NOT NULL,
    store_id BIGINT UNSIGNED NOT NULL,
    medicine_id BIGINT UNSIGNED NOT NULL,
    batch_id BIGINT UNSIGNED NOT NULL,
    reserved_qty INT NOT NULL,
    remaining_qty INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_activity_batch (activity_id, batch_id),
    KEY idx_activity_batch_fefo (activity_id, remaining_qty),
    CONSTRAINT fk_activity_batch_activity FOREIGN KEY (store_id, activity_id) REFERENCES seckill_activity(store_id, id),
    CONSTRAINT fk_activity_batch_inventory FOREIGN KEY (store_id, medicine_id, batch_id) REFERENCES inventory_batch(store_id, medicine_id, id),
    CONSTRAINT ck_activity_batch_quantity CHECK (reserved_qty > 0 AND remaining_qty >= 0 AND remaining_qty <= reserved_qty)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
