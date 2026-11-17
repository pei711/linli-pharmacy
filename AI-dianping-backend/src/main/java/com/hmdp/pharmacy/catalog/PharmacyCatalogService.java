package com.hmdp.pharmacy.catalog;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.lang.TypeReference;
import cn.hutool.json.JSONUtil;
import com.hmdp.utils.CacheClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Service
public class PharmacyCatalogService implements ApplicationRunner {
    private static final String STORE_GEO_KEY = "pharmacy:store:geo:v1";
    private static final String MEDICINE_CACHE_PREFIX = "pharmacy:catalog:medicines:v1:";
    private static final String ACTIVITY_CACHE_PREFIX = "pharmacy:catalog:activities:v1:";
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final CacheClient cache;

    public PharmacyCatalogService(JdbcTemplate jdbc, StringRedisTemplate redis, CacheClient cache) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.cache = cache;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<Map<String, Object>> stores = jdbc.queryForList(
                    "SELECT s.id,s.x,s.y FROM tb_shop s JOIN tb_shop_owner o ON o.shop_id=s.id " +
                            "WHERE s.x IS NOT NULL AND s.y IS NOT NULL");
            for (Map<String, Object> store : stores) {
                redis.opsForGeo().add(STORE_GEO_KEY,
                        new org.springframework.data.geo.Point(number(store.get("x")), number(store.get("y"))),
                        store.get("id").toString());
            }
            log.info("药房 GEO 索引已初始化，门店数={}", stores.size());
        } catch (RuntimeException failure) {
            log.warn("药房 GEO 索引初始化失败，附近门店查询暂不可用", failure);
        }
        try {
            List<Long> hotStoreIds = jdbc.queryForList(
                    "SELECT store_id FROM seckill_activity WHERE status='PUBLISHED' " +
                            "AND end_at>CURRENT_TIMESTAMP GROUP BY store_id ORDER BY COUNT(*) DESC LIMIT 50",
                    Long.class);
            for (Long storeId : hotStoreIds) {
                medicines(storeId);
                activities(storeId);
            }
            log.info("药房热门目录缓存预热完成，门店数={}", hotStoreIds.size());
        } catch (RuntimeException failure) {
            log.warn("药房热门目录缓存预热失败，将按需从数据库加载", failure);
        }
    }

    public List<Map<String, Object>> nearby(double longitude, double latitude, double radiusKm, int limit) {
        if (longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90
                || radiusKm <= 0 || radiusKm > 50 || limit <= 0 || limit > 100) {
            throw new IllegalArgumentException("经纬度、半径或返回数量无效");
        }
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redis.opsForGeo().search(
                STORE_GEO_KEY, GeoReference.fromCoordinate(longitude, latitude),
                new Distance(radiusKm, Metrics.KILOMETERS),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(limit));
        if (results == null || results.getContent().isEmpty()) {
            return List.of();
        }
        Map<Long, Double> distances = new LinkedHashMap<>();
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : results) {
            distances.put(Long.parseLong(result.getContent().getName()), result.getDistance().getValue());
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(distances.size(), "?"));
        List<Object> parameters = new ArrayList<>(distances.keySet());
        List<Map<String, Object>> stores = jdbc.queryForList(
                "SELECT s.id,s.name,s.address,s.x,s.y,s.type_id FROM tb_shop s " +
                        "JOIN tb_shop_owner o ON o.shop_id=s.id WHERE s.id IN (" + placeholders + ")", parameters.toArray());
        stores.forEach(store -> store.put("distanceKm", distances.get(((Number) store.get("id")).longValue())));
        stores.sort((left, right) -> Double.compare(number(left.get("distanceKm")), number(right.get("distanceKm"))));
        return stores;
    }

    public List<Map<String, Object>> medicines(long storeId) {
        return cachedRows(medicinesCacheKey(storeId), () -> jdbc.queryForList("SELECT m.id,m.store_id,m.name,m.specification,m.sale_category, " +
                        "COALESCE(SUM(CASE WHEN b.expire_date>=CURRENT_DATE AND b.quarantined_qty=0 THEN b.available_qty ELSE 0 END),0) AS available_qty " +
                        "FROM pharmacy_medicine m LEFT JOIN inventory_batch b ON b.medicine_id=m.id AND b.store_id=m.store_id " +
                        "WHERE m.store_id=? AND m.enabled=TRUE AND m.sale_category IN ('OTC','MEDICAL_SUPPLY') " +
                        "GROUP BY m.id,m.store_id,m.name,m.specification,m.sale_category ORDER BY m.name", storeId));
    }

    public List<Map<String, Object>> activities(long storeId) {
        return cachedRows(activitiesCacheKey(storeId), () -> jdbc.queryForList("SELECT a.id,a.store_id,a.medicine_id,m.name,m.specification,a.title, " +
                        "a.total_qty,a.remaining_qty,a.per_user_limit,DATE_FORMAT(a.start_at,'%Y-%m-%dT%H:%i:%s') AS start_at," +
                        "DATE_FORMAT(a.end_at,'%Y-%m-%dT%H:%i:%s') AS end_at,a.status " +
                        "FROM seckill_activity a JOIN pharmacy_medicine m ON m.id=a.medicine_id AND m.store_id=a.store_id " +
                        "WHERE a.store_id=? AND a.status='PUBLISHED' AND m.enabled=TRUE " +
                        "AND m.sale_category IN ('OTC','MEDICAL_SUPPLY') AND a.end_at>CURRENT_TIMESTAMP " +
                        "ORDER BY a.start_at,a.id", storeId));
    }

    public Map<String, Object> activity(long activityId) {
        List<Map<String, Object>> activities = jdbc.queryForList(
                "SELECT a.id,a.store_id,s.name AS store_name,s.address,a.medicine_id,m.name,m.specification, " +
                        "a.title,a.total_qty,a.remaining_qty,a.per_user_limit," +
                        "DATE_FORMAT(a.start_at,'%Y-%m-%dT%H:%i:%s') AS start_at," +
                        "DATE_FORMAT(a.end_at,'%Y-%m-%dT%H:%i:%s') AS end_at,a.status " +
                        "FROM seckill_activity a JOIN pharmacy_medicine m ON m.id=a.medicine_id AND m.store_id=a.store_id " +
                        "JOIN tb_shop s ON s.id=a.store_id JOIN tb_shop_owner o ON o.shop_id=s.id " +
                        "WHERE a.id=? AND a.status='PUBLISHED' AND m.enabled=TRUE " +
                        "AND m.sale_category IN ('OTC','MEDICAL_SUPPLY')", activityId);
        if (activities.isEmpty()) {
            throw new IllegalArgumentException("预约活动不存在或当前不可见");
        }
        Map<String, Object> activity = activities.get(0);
        return activity;
    }

    private double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : Double.parseDouble(Objects.toString(value));
    }

    public static String medicinesCacheKey(long storeId) {
        return MEDICINE_CACHE_PREFIX + storeId;
    }

    public static String activitiesCacheKey(long storeId) {
        return ACTIVITY_CACHE_PREFIX + storeId;
    }

    private List<Map<String, Object>> cachedRows(String key, Supplier<List<Map<String, Object>>> loader) {
        String json = cache.get(key, 15L, TimeUnit.SECONDS);
        if (json != null) {
            return JSONUtil.toBean(json, new TypeReference<List<Map<String, Object>>>() {}, false);
        }
        List<Map<String, Object>> rows = loader.get();
        cache.set(key, rows, (long) RandomUtil.randomInt(15, 31), TimeUnit.SECONDS);
        return rows;
    }
}
