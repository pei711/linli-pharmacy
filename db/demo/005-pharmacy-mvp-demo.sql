-- Synthetic demo identities and shop; do not use as real pharmacy records.
SET NAMES utf8mb4;
USE hmdp;

INSERT INTO tb_user (phone, password, nick_name, icon)
VALUES ('19900000001', '', '邻药云演示店主', '')
ON DUPLICATE KEY UPDATE nick_name = VALUES(nick_name);

INSERT INTO tb_shop
    (id, name, type_id, images, area, address, x, y, avg_price, sold, comments, score, open_hours)
VALUES
    (900010, '邻药云社区药房（演示门店）', 3, '', '思明区', '厦门市思明区演示路 10 号', 118.0894, 24.4798, 0, 0, 0, 50, '08:00-22:00')
ON DUPLICATE KEY UPDATE name = VALUES(name), address = VALUES(address), open_hours = VALUES(open_hours);

INSERT INTO tb_shop_owner (shop_id, user_id)
SELECT 900010, id FROM tb_user WHERE phone = '19900000001'
ON DUPLICATE KEY UPDATE user_id = VALUES(user_id);
