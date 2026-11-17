package com.hmdp.pharmacy.activity;

import com.hmdp.pharmacy.medicine.MedicineService;
import com.hmdp.pharmacy.catalog.PharmacyCatalogService;
import com.hmdp.utils.CacheClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "app.pharmacy.seckill.enabled", havingValue = "true")
public class ActivityService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final MedicineService medicines;
    private final ActivityInventoryService inventory;
    private final ActivityRedisService redis;
    private final CacheClient cache;

    public ActivityService(JdbcTemplate jdbc, MedicineService medicines,
                           ActivityInventoryService inventory, ActivityRedisService redis, CacheClient cache) {
        this.jdbc = jdbc;
        this.medicines = medicines;
        this.inventory = inventory;
        this.redis = redis;
        this.cache = cache;
    }

    @Transactional
    public long create(long storeId, long medicineId, String title, int quantity, int perUserLimit,
                       LocalDateTime startAt, LocalDateTime endAt) {
        medicines.requireOwner(storeId);
        if (storeId <= 0 || medicineId <= 0 || quantity <= 0 || perUserLimit <= 0
                || title == null || title.isBlank() || title.length() > 160
                || startAt == null || endAt == null || !startAt.isAfter(LocalDateTime.now(BUSINESS_ZONE))
                || !endAt.isAfter(startAt)) {
            throw new IllegalArgumentException("活动信息无效，活动开始时间须晚于当前时间");
        }
        List<Map<String, Object>> medicinesForSale = jdbc.queryForList(
                "SELECT id FROM pharmacy_medicine WHERE id=? AND store_id=? AND enabled=TRUE " +
                        "AND sale_category IN ('OTC','MEDICAL_SUPPLY')",
                medicineId, storeId);
        if (medicinesForSale.isEmpty()) {
            throw new IllegalArgumentException("仅启用的 OTC 商品和医疗用品可以发布预约活动");
        }
        long activityId = inventory.create(storeId, medicineId, title.trim(), quantity, perUserLimit, startAt, endAt);
        cache.deleteAfterCommit(PharmacyCatalogService.activitiesCacheKey(storeId));
        return activityId;
    }

    public void publish(long activityId) {
        ActivityInventoryService.Publication publication = inventory.preparePublication(activityId);
        long ttlSeconds = Math.max(60, publication.endAt().plusDays(1).atZone(BUSINESS_ZONE).toEpochSecond()
                - LocalDateTime.now(BUSINESS_ZONE).atZone(BUSINESS_ZONE).toEpochSecond());
        Long initialized = redis.initialize(publication, ttlSeconds);
        if (initialized == null || initialized < 0) {
            throw new IllegalStateException("Redis 活动库存初始化状态不一致；活动保留在 PUBLISHING，需排查后重试");
        }
        inventory.markPublished(activityId);
        cache.delete(PharmacyCatalogService.activitiesCacheKey(publication.storeId()));
    }
}
