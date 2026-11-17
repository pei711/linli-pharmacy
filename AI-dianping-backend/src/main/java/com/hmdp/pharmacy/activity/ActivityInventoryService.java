package com.hmdp.pharmacy.activity;

import com.hmdp.pharmacy.inventory.FefoAllocator;
import com.hmdp.pharmacy.medicine.MedicineService;
import com.hmdp.pharmacy.catalog.PharmacyCatalogService;
import com.hmdp.utils.CacheClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

@Service
@ConditionalOnProperty(name = "app.pharmacy.seckill.enabled", havingValue = "true")
public class ActivityInventoryService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final MedicineService medicines;
    private final CacheClient cache;

    public ActivityInventoryService(JdbcTemplate jdbc, MedicineService medicines, CacheClient cache) {
        this.jdbc = jdbc;
        this.medicines = medicines;
        this.cache = cache;
    }

    @Transactional
    public long create(long storeId, long medicineId, String title, int quantity, int perUserLimit,
                       LocalDateTime startAt, LocalDateTime endAt) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO seckill_activity(store_id,medicine_id,title,total_qty,remaining_qty,per_user_limit,start_at,end_at) " +
                            "VALUES (?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, storeId);
            statement.setLong(2, medicineId);
            statement.setString(3, title);
            statement.setInt(4, quantity);
            statement.setInt(5, quantity);
            statement.setInt(6, perUserLimit);
            statement.setObject(7, startAt);
            statement.setObject(8, endAt);
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey(), "Missing activity ID").longValue();
    }

    /**
     * Atomically locks saleable batches in FEFO order and transfers the complete activity quota
     * from available to activity-reserved stock. Any shortage rolls back the whole transaction.
     */
    @Transactional
    public Publication preparePublication(long activityId) {
        Activity activity = jdbc.query("SELECT id,store_id,medicine_id,total_qty,per_user_limit,start_at,end_at,status " +
                        "FROM seckill_activity WHERE id=? FOR UPDATE",
                (rs, rowNum) -> new Activity(rs.getLong("id"), rs.getLong("store_id"),
                        rs.getLong("medicine_id"), rs.getInt("total_qty"), rs.getInt("per_user_limit"),
                        rs.getObject("start_at", LocalDateTime.class), rs.getObject("end_at", LocalDateTime.class),
                        rs.getString("status")), activityId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("活动不存在"));
        medicines.requireOwner(activity.storeId());
        if ("PUBLISHED".equals(activity.status())) {
            return publication(activity, false);
        }
        if ("PUBLISHING".equals(activity.status())) {
            return publication(activity, true);
        }
        if (!"DRAFT".equals(activity.status())) {
            throw new IllegalArgumentException("只有草稿或发布中的活动可以发布");
        }
        if (!activity.startAt().isAfter(LocalDateTime.now(BUSINESS_ZONE)) || !activity.endAt().isAfter(activity.startAt())) {
            throw new IllegalArgumentException("活动时间无效或已开始");
        }

        LocalDate pickupCutoff = activity.endAt().plusDays(2).toLocalDate();
        List<FefoAllocator.Batch> batches = jdbc.query(
                "SELECT id,expire_date,available_qty,quarantined_qty FROM inventory_batch " +
                        "WHERE store_id=? AND medicine_id=? AND available_qty>0 AND expire_date>=? " +
                        "ORDER BY expire_date,id FOR UPDATE",
                (rs, rowNum) -> new FefoAllocator.Batch(rs.getLong("id"),
                        rs.getDate("expire_date").toLocalDate(), rs.getInt("available_qty"),
                        rs.getInt("quarantined_qty") > 0), activity.storeId(), activity.medicineId(),
                pickupCutoff);
        List<FefoAllocator.Allocation> plan;
        try {
            plan = FefoAllocator.plan(batches, activity.quantity(),
                    LocalDate.now(BUSINESS_ZONE).isAfter(pickupCutoff)
                            ? LocalDate.now(BUSINESS_ZONE) : pickupCutoff);
        } catch (FefoAllocator.InsufficientStockException ex) {
            throw new IllegalArgumentException("合规且未过期的可用库存不足，活动未发布", ex);
        }

        for (FefoAllocator.Allocation allocation : plan) {
            int changed = jdbc.update("UPDATE inventory_batch SET available_qty=available_qty-?, " +
                            "activity_reserved_qty=activity_reserved_qty+?,version=version+1 " +
                            "WHERE id=? AND store_id=? AND medicine_id=? AND available_qty>=?",
                    allocation.quantity(), allocation.quantity(), allocation.batchId(), activity.storeId(),
                    activity.medicineId(), allocation.quantity());
            if (changed != 1) {
                throw new IllegalStateException("库存批次在活动发布期间发生变化，请重试");
            }
            jdbc.update("INSERT INTO seckill_activity_batch(activity_id,store_id,medicine_id,batch_id,reserved_qty,remaining_qty) " +
                            "VALUES (?,?,?,?,?,?)",
                    activity.id(), activity.storeId(), activity.medicineId(), allocation.batchId(),
                    allocation.quantity(), allocation.quantity());
            jdbc.update("INSERT INTO stock_movement(store_id,medicine_id,batch_id,movement_type,quantity,reference_id) " +
                            "VALUES (?,?,?,'ACTIVITY_RESERVE',?,?)",
                    activity.storeId(), activity.medicineId(), allocation.batchId(), allocation.quantity(),
                    "ACTIVITY:" + activity.id());
        }
        int changed = jdbc.update("UPDATE seckill_activity SET status='PUBLISHING' WHERE id=? AND status='DRAFT'",
                activity.id());
        if (changed != 1) {
            throw new IllegalStateException("活动状态已变化，请重试");
        }
        cache.deleteAfterCommit(PharmacyCatalogService.medicinesCacheKey(activity.storeId()));
        return publication(activity, true);
    }

    @Transactional
    public void markPublished(long activityId) {
        int changed = jdbc.update("UPDATE seckill_activity SET status='PUBLISHED' WHERE id=? AND status='PUBLISHING'",
                activityId);
        if (changed == 0) {
            String status = jdbc.query("SELECT status FROM seckill_activity WHERE id=?",
                            (rs, rowNum) -> rs.getString(1), activityId)
                    .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("活动不存在"));
            if (!"PUBLISHED".equals(status)) {
                throw new IllegalStateException("Redis 已初始化，但活动状态未能发布；请安全重试");
            }
        }
    }

    private Publication publication(Activity activity, boolean mayInitializeRedis) {
        return new Publication(activity.id(), activity.storeId(), activity.medicineId(), activity.quantity(),
                activity.perUserLimit(),
                activity.startAt(), activity.endAt(), mayInitializeRedis);
    }

    public record Publication(long id, long storeId, long medicineId, int quantity, int perUserLimit,
                              LocalDateTime startAt, LocalDateTime endAt, boolean mayInitializeRedis) { }

    private record Activity(long id, long storeId, long medicineId, int quantity, int perUserLimit,
                            LocalDateTime startAt, LocalDateTime endAt, String status) { }
}
