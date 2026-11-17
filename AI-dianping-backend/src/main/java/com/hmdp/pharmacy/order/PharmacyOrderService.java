package com.hmdp.pharmacy.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.pharmacy.inventory.FefoAllocator;
import com.hmdp.pharmacy.catalog.PharmacyCatalogService;
import com.hmdp.pharmacy.medicine.MedicineService;
import com.hmdp.utils.CacheClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "app.pharmacy.seckill.enabled", havingValue = "true")
public class PharmacyOrderService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final MedicineService medicines;
    private final ObjectMapper objectMapper;
    private final CacheClient cache;

    public PharmacyOrderService(JdbcTemplate jdbc, MedicineService medicines, ObjectMapper objectMapper, CacheClient cache) {
        this.jdbc = jdbc;
        this.medicines = medicines;
        this.objectMapper = objectMapper;
        this.cache = cache;
    }

    /** MySQL is the idempotency and inventory authority; this method is the Kafka consumer transaction. */
    @Transactional
    public OrderResult createFromRequest(String requestId) {
        Request request = jdbc.query("SELECT request_id,activity_id,user_id,quantity,status,expires_at,order_no " +
                        "FROM seckill_request WHERE request_id=? FOR UPDATE",
                (rs, rowNum) -> new Request(rs.getString("request_id"), rs.getLong("activity_id"),
                        rs.getLong("user_id"), rs.getInt("quantity"), rs.getString("status"),
                        rs.getObject("expires_at", LocalDateTime.class), rs.getString("order_no")), requestId)
                .stream().findFirst().orElse(null);
        if (request == null) {
            throw new IllegalArgumentException("预约请求不存在");
        }
        if ("SUCCESS".equals(request.status())) {
            return new OrderResult(requestId, request.activityId(), "SUCCESS", request.orderNo());
        }
        if (!"PROCESSING".equals(request.status())) {
            return new OrderResult(requestId, request.activityId(), request.status(), null);
        }
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        if (!request.expiresAt().isAfter(now)) {
            jdbc.update("UPDATE seckill_request SET status='EXPIRED',reservation_state='RELEASE_PENDING', " +
                            "failure_code='REQUEST_TIMEOUT' WHERE request_id=? AND status='PROCESSING'",
                    requestId);
            return new OrderResult(requestId, request.activityId(), "EXPIRED", null);
        }

        Activity activity = jdbc.query("SELECT store_id,medicine_id,end_at FROM seckill_activity WHERE id=?",
                        (rs, rowNum) -> new Activity(rs.getLong("store_id"), rs.getLong("medicine_id"),
                                rs.getObject("end_at", LocalDateTime.class)), request.activityId())
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("预约活动不存在"));
        LocalDate pickupCutoff = activity.endAt().plusDays(2).toLocalDate();
        List<FefoAllocator.Batch> reservedBatches = jdbc.query(
                "SELECT ab.batch_id,ib.expire_date,ab.remaining_qty,ib.quarantined_qty " +
                        "FROM seckill_activity_batch ab JOIN inventory_batch ib " +
                        "ON ib.id=ab.batch_id AND ib.store_id=ab.store_id AND ib.medicine_id=ab.medicine_id " +
                        "WHERE ab.activity_id=? AND ab.remaining_qty>0 AND ib.expire_date>=? " +
                        "ORDER BY ib.expire_date,ib.id FOR UPDATE",
                (rs, rowNum) -> new FefoAllocator.Batch(rs.getLong("batch_id"),
                        rs.getDate("expire_date").toLocalDate(), rs.getInt("remaining_qty"),
                        rs.getInt("quarantined_qty") > 0), request.activityId(), pickupCutoff);
        List<FefoAllocator.Allocation> allocations;
        try {
            allocations = FefoAllocator.plan(reservedBatches, request.quantity(), LocalDate.now(BUSINESS_ZONE));
        } catch (FefoAllocator.InsufficientStockException shortage) {
            jdbc.update("UPDATE seckill_request SET status='FAILED',reservation_state='RELEASE_PENDING', " +
                            "failure_code='BACKING_STOCK_MISMATCH' WHERE request_id=? AND status='PROCESSING'",
                    requestId);
            return new OrderResult(requestId, request.activityId(), "FAILED", null);
        }

        String orderNo = UUID.randomUUID().toString();
        LocalDateTime pickupExpiresAt = activity.endAt().plusDays(2);
        jdbc.update("INSERT INTO pharmacy_order(order_no,request_id,activity_id,store_id,medicine_id,user_id,quantity,status,pickup_expires_at) " +
                        "VALUES (?,?,?,?,?,?,?,'RESERVED',?)",
                orderNo, requestId, request.activityId(), activity.storeId(), activity.medicineId(),
                request.userId(), request.quantity(), pickupExpiresAt);

        for (FefoAllocator.Allocation allocation : allocations) {
            int activityBatchChanged = jdbc.update("UPDATE seckill_activity_batch SET remaining_qty=remaining_qty-? " +
                            "WHERE activity_id=? AND batch_id=? AND remaining_qty>=?",
                    allocation.quantity(), request.activityId(), allocation.batchId(), allocation.quantity());
            int inventoryChanged = jdbc.update("UPDATE inventory_batch SET activity_reserved_qty=activity_reserved_qty-?, " +
                            "order_reserved_qty=order_reserved_qty+?,version=version+1 " +
                            "WHERE id=? AND store_id=? AND medicine_id=? AND activity_reserved_qty>=? " +
                            "AND expire_date>=? AND quarantined_qty=0",
                    allocation.quantity(), allocation.quantity(), allocation.batchId(), activity.storeId(),
                    activity.medicineId(), allocation.quantity(), pickupCutoff);
            if (activityBatchChanged != 1 || inventoryChanged != 1) {
                throw new IllegalStateException("活动预留批次状态在建单期间发生变化");
            }
            jdbc.update("INSERT INTO order_batch_reservation(order_no,batch_id,quantity) VALUES (?,?,?)",
                    orderNo, allocation.batchId(), allocation.quantity());
            jdbc.update("INSERT INTO stock_movement(store_id,medicine_id,batch_id,movement_type,quantity,reference_id) " +
                            "VALUES (?,?,?,'ORDER_RESERVE',?,?)",
                    activity.storeId(), activity.medicineId(), allocation.batchId(), allocation.quantity(),
                    "ORDER:" + orderNo);
        }
        if (jdbc.update("UPDATE seckill_activity SET remaining_qty=remaining_qty-? " +
                        "WHERE id=? AND remaining_qty>=?", request.quantity(), request.activityId(), request.quantity()) != 1) {
            throw new IllegalStateException("活动剩余库存与订单不一致");
        }
        if (jdbc.update("UPDATE seckill_request SET status='SUCCESS',reservation_state='CONSUMED',order_no=? " +
                        "WHERE request_id=? AND status='PROCESSING' AND expires_at>?",
                orderNo, requestId, now) != 1) {
            throw new IllegalStateException("预约状态已变化，订单事务回滚");
        }
        invalidateCatalogAfterCommit(activity.storeId());
        return new OrderResult(requestId, request.activityId(), "SUCCESS", orderNo);
    }

    @Transactional(readOnly = true)
    public OrderView findOrder(String orderNo, long userId) {
        return jdbc.query("SELECT order_no,activity_id,store_id,medicine_id,quantity,status,pickup_expires_at " +
                        "FROM pharmacy_order WHERE order_no=? AND user_id=?",
                (rs, rowNum) -> new OrderView(rs.getString("order_no"), rs.getLong("activity_id"),
                        rs.getLong("store_id"), rs.getLong("medicine_id"), rs.getInt("quantity"),
                        rs.getString("status"), rs.getObject("pickup_expires_at", LocalDateTime.class)),
                orderNo, userId).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("订单不存在"));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findOrdersByUser(long userId) {
        return jdbc.queryForList("SELECT o.order_no,o.activity_id,o.store_id,s.name AS store_name, " +
                        "o.medicine_id,m.name AS medicine_name,o.quantity,o.status, " +
                        "DATE_FORMAT(o.pickup_expires_at,'%Y-%m-%dT%H:%i:%s') AS pickup_expires_at, " +
                        "DATE_FORMAT(o.created_at,'%Y-%m-%dT%H:%i:%s') AS created_at " +
                        "FROM pharmacy_order o JOIN tb_shop s ON s.id=o.store_id " +
                        "JOIN pharmacy_medicine m ON m.id=o.medicine_id AND m.store_id=o.store_id " +
                        "WHERE o.user_id=? ORDER BY o.created_at DESC LIMIT 30", userId);
    }

    @Transactional
    public OrderView cancel(String orderNo, long userId) {
        OrderLifecycle order = jdbc.query("SELECT o.order_no,o.request_id,o.activity_id,o.store_id,o.medicine_id, " +
                        "o.user_id,o.quantity,o.status,o.pickup_expires_at,a.status AS activity_status,a.end_at " +
                        "FROM pharmacy_order o JOIN seckill_activity a ON a.id=o.activity_id " +
                        "WHERE o.order_no=? AND o.user_id=? FOR UPDATE",
                (rs, rowNum) -> new OrderLifecycle(rs.getString("order_no"), rs.getString("request_id"),
                        rs.getLong("activity_id"), rs.getLong("store_id"), rs.getLong("medicine_id"),
                        rs.getLong("user_id"), rs.getInt("quantity"), rs.getString("status"),
                        rs.getObject("pickup_expires_at", LocalDateTime.class), rs.getString("activity_status"),
                        rs.getObject("end_at", LocalDateTime.class)), orderNo, userId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("订单不存在"));
        if ("CANCELLED".equals(order.status())) {
            return findOrder(orderNo, userId);
        }
        if (!"RESERVED".equals(order.status())) {
            throw new IllegalArgumentException("只有待到店订单可以取消");
        }
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        if (!order.pickupExpiresAt().isAfter(now)) {
            throw new IllegalArgumentException("订单已超时，等待系统释放");
        }
        boolean activityOpen = "PUBLISHED".equals(order.activityStatus()) && now.isBefore(order.activityEndAt());
        List<OrderBatch> batches = jdbc.query("SELECT obr.batch_id,obr.quantity,ib.expire_date,ib.quarantined_qty " +
                        "FROM order_batch_reservation obr JOIN inventory_batch ib ON ib.id=obr.batch_id " +
                        "WHERE obr.order_no=? ORDER BY ib.expire_date,ib.id FOR UPDATE",
                (rs, rowNum) -> new OrderBatch(rs.getLong("batch_id"), rs.getInt("quantity"),
                        rs.getDate("expire_date").toLocalDate(), rs.getInt("quarantined_qty")), orderNo);
        int returnedToActivity = 0;
        for (OrderBatch batch : batches) {
            boolean eligibleForActivity = activityOpen && batch.quarantinedQty() == 0
                    && !batch.expireDate().isBefore(order.activityEndAt().plusDays(2).toLocalDate());
            String inventoryUpdate = eligibleForActivity
                    ? "UPDATE inventory_batch SET order_reserved_qty=order_reserved_qty-?,activity_reserved_qty=activity_reserved_qty+?,version=version+1 " +
                    "WHERE id=? AND order_reserved_qty>=?"
                    : "UPDATE inventory_batch SET order_reserved_qty=order_reserved_qty-?," +
                    (batch.quarantinedQty() > 0 || batch.expireDate().isBefore(LocalDate.now(BUSINESS_ZONE))
                            ? "quarantined_qty=quarantined_qty+?," : "available_qty=available_qty+?,") +
                    "version=version+1 WHERE id=? AND order_reserved_qty>=?";
            int changed = jdbc.update(inventoryUpdate, batch.quantity(), batch.quantity(), batch.batchId(), batch.quantity());
            if (changed != 1) {
                throw new IllegalStateException("订单批次预留数量不一致，取消事务回滚");
            }
            if (eligibleForActivity) {
                jdbc.update("UPDATE seckill_activity_batch SET remaining_qty=remaining_qty+? " +
                                "WHERE activity_id=? AND batch_id=?",
                        batch.quantity(), order.activityId(), batch.batchId());
                returnedToActivity += batch.quantity();
            }
            jdbc.update("INSERT INTO stock_movement(store_id,medicine_id,batch_id,movement_type,quantity,reference_id) " +
                            "VALUES (?,?,?,'ORDER_RELEASE',?,?)",
                    order.storeId(), order.medicineId(), batch.batchId(), batch.quantity(), "ORDER:CANCEL:" + orderNo);
        }
        if (returnedToActivity == order.quantity()) {
            jdbc.update("UPDATE seckill_activity SET remaining_qty=remaining_qty+? WHERE id=?",
                    order.quantity(), order.activityId());
        }
        jdbc.update("UPDATE pharmacy_order SET status='CANCELLED' WHERE order_no=? AND status='RESERVED'", orderNo);
        jdbc.update("UPDATE seckill_request SET status='CANCELLED' WHERE request_id=? AND status='SUCCESS'",
                order.requestId());
        insertOutbox("pharmacy.order.cancelled.v1", "PHARMACY_ORDER", orderNo,
                "order-cancelled:" + orderNo, new OrderCancelledEvent(order.requestId(), order.activityId(),
                        orderNo, returnedToActivity, order.userId()));
        invalidateCatalogAfterCommit(order.storeId());
        return findOrder(orderNo, userId);
    }

    @Transactional
    public OrderView pickUp(String orderNo) {
        OrderLifecycle order = jdbc.query("SELECT o.order_no,o.request_id,o.activity_id,o.store_id,o.medicine_id, " +
                        "o.user_id,o.quantity,o.status,o.pickup_expires_at,a.end_at " +
                        "FROM pharmacy_order o JOIN seckill_activity a ON a.id=o.activity_id " +
                        "WHERE o.order_no=? FOR UPDATE",
                (rs, rowNum) -> new OrderLifecycle(rs.getString("order_no"), rs.getString("request_id"),
                        rs.getLong("activity_id"), rs.getLong("store_id"), rs.getLong("medicine_id"),
                        rs.getLong("user_id"), rs.getInt("quantity"), rs.getString("status"),
                        rs.getObject("pickup_expires_at", LocalDateTime.class), "", rs.getObject("end_at", LocalDateTime.class)), orderNo)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("订单不存在"));
        medicines.requireOwner(order.storeId());
        if ("COMPLETED".equals(order.status())) {
            return findOrder(orderNo, order.userId());
        }
        if (!"RESERVED".equals(order.status())) {
            throw new IllegalArgumentException("只有待到店订单可以核销");
        }
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        if (!order.pickupExpiresAt().isAfter(now)) {
            throw new IllegalArgumentException("订单已超时，等待系统释放");
        }
        List<OrderBatch> batches = jdbc.query("SELECT obr.batch_id,obr.quantity,ib.expire_date,ib.quarantined_qty " +
                        "FROM order_batch_reservation obr JOIN inventory_batch ib ON ib.id=obr.batch_id " +
                        "WHERE obr.order_no=? ORDER BY ib.expire_date,ib.id FOR UPDATE",
                (rs, rowNum) -> new OrderBatch(rs.getLong("batch_id"), rs.getInt("quantity"),
                        rs.getDate("expire_date").toLocalDate(), rs.getInt("quarantined_qty")), orderNo);
        for (OrderBatch batch : batches) {
            int changed = jdbc.update("UPDATE inventory_batch SET on_hand_qty=on_hand_qty-?, " +
                            "order_reserved_qty=order_reserved_qty-?,version=version+1 " +
                            "WHERE id=? AND on_hand_qty>=? AND order_reserved_qty>=? AND expire_date>=CURRENT_DATE AND quarantined_qty=0",
                    batch.quantity(), batch.quantity(), batch.batchId(), batch.quantity(), batch.quantity());
            if (changed != 1) {
                throw new IllegalStateException("核销批次不可售或库存不一致，事务回滚");
            }
            jdbc.update("INSERT INTO stock_movement(store_id,medicine_id,batch_id,movement_type,quantity,reference_id) " +
                            "VALUES (?,?,?,'SALE_OUT',?,?)",
                    order.storeId(), order.medicineId(), batch.batchId(), batch.quantity(), "ORDER:PICKUP:" + orderNo);
        }
        jdbc.update("UPDATE pharmacy_order SET status='COMPLETED' WHERE order_no=? AND status='RESERVED'", orderNo);
        invalidateCatalogAfterCommit(order.storeId());
        return findOrder(orderNo, order.userId());
    }

    private void invalidateCatalogAfterCommit(long storeId) {
        cache.deleteAfterCommit(PharmacyCatalogService.medicinesCacheKey(storeId));
        cache.deleteAfterCommit(PharmacyCatalogService.activitiesCacheKey(storeId));
    }

    private void insertOutbox(String eventType, String aggregateType, String aggregateId,
                              String dedupeKey, Object payload) {
        try {
            jdbc.update("INSERT INTO outbox_event(id,dedupe_key,aggregate_type,aggregate_id,event_type,payload) " +
                            "VALUES (?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), dedupeKey, aggregateType, aggregateId, eventType,
                    objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("订单事件序列化失败", ex);
        }
    }

    public record OrderResult(String requestId, long activityId, String status, String orderNo) { }
    public record OrderView(String orderNo, long activityId, long storeId, long medicineId, int quantity,
                            String status, LocalDateTime pickupExpiresAt) { }
    public record OrderCancelledEvent(String requestId, long activityId, String orderNo,
                                      int restoreStock, long userId) { }
    private record OrderLifecycle(String orderNo, String requestId, long activityId, long storeId,
                                  long medicineId, long userId, int quantity, String status,
                                  LocalDateTime pickupExpiresAt, String activityStatus, LocalDateTime activityEndAt) { }
    private record OrderBatch(long batchId, int quantity, LocalDate expireDate, int quarantinedQty) { }
    private record Activity(long storeId, long medicineId, LocalDateTime endAt) { }
    private record Request(String requestId, long activityId, long userId, int quantity, String status,
                           LocalDateTime expiresAt, String orderNo) { }
}
