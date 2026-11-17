-- Synthetic two-tenant fixtures for the real operations-agent evaluation.
-- Import only into a disposable verification database after Flyway V1-V9.
SET NAMES utf8mb4;
USE hmdp;

INSERT INTO tb_user (phone, password, nick_name, icon)
VALUES ('19900000101', '', '邻里药房AI评估A店主', ''),
       ('19900000102', '', '邻里药房AI评估B店主', '')
ON DUPLICATE KEY UPDATE nick_name = VALUES(nick_name);

INSERT INTO tb_shop
    (id, name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours)
VALUES
    (990010, '邻里药房AI评估A店', 3, '', '思明区', '评估专用 A 店', 118.0894, 24.4798, 0, 0, 0, 50, '08:00-22:00'),
    (990020, '邻里药房AI评估B店', 3, '', '思明区', '评估专用 B 店', 118.0895, 24.4799, 0, 0, 0, 50, '08:00-22:00')
ON DUPLICATE KEY UPDATE name = VALUES(name), address = VALUES(address), open_hours = VALUES(open_hours);

-- These synthetic owners may have been imported by an older evaluation run
-- with a different shop mapping. The operations agent resolves the first
-- linked shop, so remove stale links for these dedicated accounts before
-- assigning the two fixture shops below.
DELETE FROM tb_shop_owner
WHERE user_id IN (SELECT id FROM tb_user WHERE phone IN ('19900000101', '19900000102'));

INSERT INTO tb_shop_owner (shop_id, user_id)
SELECT 990010, id FROM tb_user WHERE phone = '19900000101'
ON DUPLICATE KEY UPDATE user_id = VALUES(user_id);
INSERT INTO tb_shop_owner (shop_id, user_id)
SELECT 990020, id FROM tb_user WHERE phone = '19900000102'
ON DUPLICATE KEY UPDATE user_id = VALUES(user_id);

INSERT INTO pharmacy_medicine (id, store_id, name, specification, sale_category, enabled)
VALUES (9900101, 990010, 'AI评估A店布洛芬', '0.2g*24片', 'OTC', TRUE),
       (9900102, 990010, 'AI评估A店无批次商品', '10片', 'OTC', TRUE),
       (9900201, 990020, 'AI评估B店维生素C', '0.1g*100片', 'OTC', TRUE)
ON DUPLICATE KEY UPDATE store_id = VALUES(store_id), name = VALUES(name),
    specification = VALUES(specification), sale_category = VALUES(sale_category), enabled = TRUE;

INSERT INTO inventory_batch
    (id, store_id, medicine_id, batch_no, production_date, expire_date,
     on_hand_qty, available_qty, activity_reserved_qty, order_reserved_qty, quarantined_qty)
VALUES (9900101, 990010, 9900101, 'AI-EVAL-A-2026-01', '2026-01-01', '2027-12-31', 8, 8, 0, 0, 0),
       (9900201, 990020, 9900201, 'AI-EVAL-B-2026-01', '2026-01-01', '2027-12-31', 4, 4, 0, 0, 0)
ON DUPLICATE KEY UPDATE store_id = VALUES(store_id), medicine_id = VALUES(medicine_id),
    batch_no = VALUES(batch_no), production_date = VALUES(production_date),
    expire_date = VALUES(expire_date), on_hand_qty = VALUES(on_hand_qty),
    available_qty = VALUES(available_qty), activity_reserved_qty = 0,
    order_reserved_qty = 0, quarantined_qty = 0;

-- Rebuild these dedicated synthetic sales on every import. A prior partial
-- evaluation may have left a row with a stale quantity or timestamp; merely
-- inserting when absent would silently preserve the wrong replenishment gold.
DELETE FROM stock_movement
WHERE reference_id IN ('AI-EVAL-A-SALE-01', 'AI-EVAL-B-SALE-01');

INSERT INTO stock_movement (store_id, medicine_id, batch_id, movement_type, quantity, reference_id, created_at)
SELECT 990010, 9900101, 9900101, 'SALE_OUT', 30, 'AI-EVAL-A-SALE-01', CURRENT_TIMESTAMP - INTERVAL 10 DAY
;
INSERT INTO stock_movement (store_id, medicine_id, batch_id, movement_type, quantity, reference_id, created_at)
SELECT 990020, 9900201, 9900201, 'SALE_OUT', 12, 'AI-EVAL-B-SALE-01', CURRENT_TIMESTAMP - INTERVAL 8 DAY
;
