package com.hmdp.pharmacy.order;

import com.hmdp.pharmacy.catalog.PharmacyCatalogService;
import com.hmdp.utils.CacheClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@ConditionalOnProperty(name = "app.pharmacy.seckill.enabled", havingValue = "true")
public class PharmacyLifecycleService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final CacheClient cache;

    public PharmacyLifecycleService(JdbcTemplate jdbc, CacheClient cache) {
        this.jdbc = jdbc;
        this.cache = cache;
    }

    @Transactional
    public int releaseEndedActivities() {
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        List<Long> ids = jdbc.query("SELECT id FROM seckill_activity WHERE status IN ('PUBLISHED','PUBLISHING','PAUSED') " +
                        "AND end_at<=? ORDER BY end_at LIMIT 20 FOR UPDATE SKIP LOCKED",
                (rs, rowNum) -> rs.getLong(1), now);
        for (Long activityId : ids) {
            Activity activity = jdbc.query("SELECT store_id,medicine_id FROM seckill_activity WHERE id=?",
                            (rs, rowNum) -> new Activity(rs.getLong(1), rs.getLong(2)), activityId)
                    .stream().findFirst().orElseThrow();
            List<ReservedBatch> batches = jdbc.query("SELECT ab.batch_id,ab.remaining_qty,ib.expire_date,ib.quarantined_qty " +
                            "FROM seckill_activity_batch ab JOIN inventory_batch ib ON ib.id=ab.batch_id " +
                            "WHERE ab.activity_id=? AND ab.remaining_qty>0 ORDER BY ib.expire_date,ib.id FOR UPDATE",
                    (rs, rowNum) -> new ReservedBatch(rs.getLong(1), rs.getInt(2),
                            rs.getDate(3).toLocalDate(), rs.getInt(4)), activityId);
            for (ReservedBatch batch : batches) {
                String destination = batch.quarantinedQty() > 0
                        || batch.expireDate().isBefore(LocalDate.now(BUSINESS_ZONE))
                        ? "quarantined_qty" : "available_qty";
                int changed = jdbc.update("UPDATE inventory_batch SET activity_reserved_qty=activity_reserved_qty-?, " +
                                destination + "=" + destination + "+?,version=version+1 " +
                                "WHERE id=? AND activity_reserved_qty>=?",
                        batch.quantity(), batch.quantity(), batch.batchId(), batch.quantity());
                if (changed != 1) {
                    throw new IllegalStateException("活动结束释放批次时库存或隔离状态不一致");
                }
                jdbc.update("UPDATE seckill_activity_batch SET remaining_qty=0 WHERE activity_id=? AND batch_id=?",
                        activityId, batch.batchId());
                jdbc.update("INSERT INTO stock_movement(store_id,medicine_id,batch_id,movement_type,quantity,reference_id) " +
                                "VALUES (?,?,?,'ACTIVITY_RELEASE',?,?)",
                        activity.storeId(), activity.medicineId(), batch.batchId(), batch.quantity(),
                        "ACTIVITY_END:" + activityId);
            }
            jdbc.update("UPDATE seckill_activity SET remaining_qty=0,status='ENDED' WHERE id=?",
                    activityId);
            invalidateCatalogAfterCommit(activity.storeId());
        }
        return ids.size();
    }

    @Transactional
    public int expirePickupOrders() {
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        List<ExpiredOrder> orders = jdbc.query("SELECT order_no,request_id,store_id,medicine_id FROM pharmacy_order " +
                        "WHERE status='RESERVED' AND pickup_expires_at<=? ORDER BY pickup_expires_at " +
                        "LIMIT 100 FOR UPDATE SKIP LOCKED",
                (rs, rowNum) -> new ExpiredOrder(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)), now);
        for (ExpiredOrder order : orders) {
            List<OrderBatch> batches = jdbc.query("SELECT obr.batch_id,obr.quantity FROM order_batch_reservation obr " +
                            "WHERE obr.order_no=? ORDER BY obr.batch_id FOR UPDATE",
                    (rs, rowNum) -> new OrderBatch(rs.getLong(1), rs.getInt(2)), order.orderNo());
            for (OrderBatch batch : batches) {
                BatchState state = jdbc.query("SELECT expire_date,quarantined_qty FROM inventory_batch WHERE id=? FOR UPDATE",
                                (rs, rowNum) -> new BatchState(rs.getDate(1).toLocalDate(), rs.getInt(2)), batch.batchId())
                        .stream().findFirst().orElseThrow();
                String quantityColumn = state.quarantinedQty() > 0
                        || state.expireDate().isBefore(LocalDate.now(BUSINESS_ZONE))
                        ? "quarantined_qty" : "available_qty";
                int changed = jdbc.update("UPDATE inventory_batch SET order_reserved_qty=order_reserved_qty-?, " +
                                quantityColumn + "=" + quantityColumn + "+?,version=version+1 " +
                                "WHERE id=? AND order_reserved_qty>=?",
                        batch.quantity(), batch.quantity(), batch.batchId(), batch.quantity());
                if (changed != 1) {
                    throw new IllegalStateException("订单超时释放批次时预留数量不一致");
                }
                jdbc.update("INSERT INTO stock_movement(store_id,medicine_id,batch_id,movement_type,quantity,reference_id) " +
                                "VALUES (?,?,?,'ORDER_RELEASE',?,?)",
                        order.storeId(), order.medicineId(), batch.batchId(), batch.quantity(),
                        "ORDER:EXPIRE:" + order.orderNo());
            }
            jdbc.update("UPDATE pharmacy_order SET status='EXPIRED' WHERE order_no=? AND status='RESERVED'",
                    order.orderNo());
            jdbc.update("UPDATE seckill_request SET status='EXPIRED' WHERE request_id=? AND status='SUCCESS'",
                    order.requestId());
            invalidateCatalogAfterCommit(order.storeId());
        }
        return orders.size();
    }

    private void invalidateCatalogAfterCommit(long storeId) {
        cache.deleteAfterCommit(PharmacyCatalogService.medicinesCacheKey(storeId));
        cache.deleteAfterCommit(PharmacyCatalogService.activitiesCacheKey(storeId));
    }

    private record Activity(long storeId, long medicineId) { }
    private record ReservedBatch(long batchId, int quantity, LocalDate expireDate, int quarantinedQty) { }
    private record OrderBatch(long batchId, int quantity) { }
    private record BatchState(LocalDate expireDate, int quarantinedQty) { }
    private record ExpiredOrder(String orderNo, String requestId, long storeId, long medicineId) { }
}
