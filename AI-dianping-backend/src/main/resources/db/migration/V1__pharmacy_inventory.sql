-- Additive only: legacy tb_shop and tb_user remain untouched.
CREATE TABLE pharmacy_medicine (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT UNSIGNED NOT NULL,
    name VARCHAR(160) NOT NULL,
    specification VARCHAR(160) NOT NULL,
    sale_category VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_medicine_store FOREIGN KEY (store_id) REFERENCES tb_shop(id),
    CONSTRAINT ck_medicine_sale_category CHECK (sale_category IN ('OTC', 'MEDICAL_SUPPLY', 'PRESCRIPTION')),
    INDEX idx_medicine_store (store_id, enabled),
    UNIQUE KEY uk_medicine_store_id (store_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE inventory_batch (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT UNSIGNED NOT NULL,
    medicine_id BIGINT UNSIGNED NOT NULL,
    batch_no VARCHAR(100) NOT NULL,
    production_date DATE NOT NULL,
    expire_date DATE NOT NULL,
    on_hand_qty INT NOT NULL DEFAULT 0,
    available_qty INT NOT NULL DEFAULT 0,
    activity_reserved_qty INT NOT NULL DEFAULT 0,
    order_reserved_qty INT NOT NULL DEFAULT 0,
    quarantined_qty INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_batch_store_medicine_no (store_id, medicine_id, batch_no),
    KEY idx_batch_fefo (store_id, medicine_id, expire_date, id),
    CONSTRAINT fk_batch_store FOREIGN KEY (store_id) REFERENCES tb_shop(id),
    CONSTRAINT fk_batch_medicine FOREIGN KEY (store_id, medicine_id) REFERENCES pharmacy_medicine(store_id, id),
    CONSTRAINT ck_batch_dates CHECK (expire_date >= production_date),
    CONSTRAINT ck_batch_nonnegative CHECK (on_hand_qty >= 0 AND available_qty >= 0 AND activity_reserved_qty >= 0 AND order_reserved_qty >= 0 AND quarantined_qty >= 0),
    CONSTRAINT ck_batch_balance CHECK (on_hand_qty = available_qty + activity_reserved_qty + order_reserved_qty + quarantined_qty)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE stock_movement (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT UNSIGNED NOT NULL,
    medicine_id BIGINT UNSIGNED NOT NULL,
    batch_id BIGINT UNSIGNED NOT NULL,
    movement_type VARCHAR(32) NOT NULL,
    quantity INT NOT NULL,
    reference_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_movement_reference (movement_type, reference_id, batch_id),
    KEY idx_movement_store_created (store_id, created_at),
    CONSTRAINT fk_movement_batch FOREIGN KEY (batch_id) REFERENCES inventory_batch(id),
    CONSTRAINT ck_movement_quantity CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
