package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.hash.BloomFilter;
import com.hmdp.config.BloomFilter.BloomFilterState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.annotation.Resource;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * <p>
 * Redis工具类
 * </p>
 *
 * @author 超大王
 * @since 2025-09-19
 */
@Slf4j
@Component
public class CacheClient {

    private static final long LOCAL_CACHE_MAX_SIZE = 10_000L;
    private static final long LOCAL_CACHE_TTL_SECONDS = 30L;
    public static final String LOCAL_CACHE_INVALIDATION_CHANNEL = "cache:local:invalidate:v1";
    private static final String CACHE_VERSION_PREFIX = "cache:version:v1:";
    private static final String INVALIDATION_SEPARATOR = "\n";
    private static final DefaultRedisScript<Long> VERSIONED_CACHE_WRITE = new DefaultRedisScript<>(
            "local version=redis.call('GET',KEYS[1]) or '0'; " +
                    "if version ~= ARGV[1] then return 0 end; " +
                    "redis.call('PSETEX',KEYS[2],ARGV[3],ARGV[2]); return 1", Long.class);
    private static final DefaultRedisScript<Long> VERSIONED_CACHE_DELETE = new DefaultRedisScript<>(
            "local version=redis.call('INCR',KEYS[1]); " +
                    "redis.call('PEXPIRE',KEYS[1],ARGV[1]); " +
                    "redis.call('DEL',KEYS[2]); return version", Long.class);

    // 布隆过滤器
    @Resource
    private BloomFilter<Long> bloomFilter;

    @Resource
    private BloomFilterState bloomFilterState;

    private final Cache<String, LocalCacheValue> localCache = Caffeine.newBuilder()
            .maximumSize(LOCAL_CACHE_MAX_SIZE)
            .expireAfterWrite(Duration.ofSeconds(LOCAL_CACHE_TTL_SECONDS))
            .build();
    private final Cache<String, Long> localVersions = Caffeine.newBuilder()
            .maximumSize(LOCAL_CACHE_MAX_SIZE)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
    public void set(String key, Object value, Long time, TimeUnit unit){
        setJson(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public String get(String key, Long time, TimeUnit unit) {
        // Keep the direct read path consistent with the pass-through path: an
        // invalidation that races the Redis GET must not return the old value.
        return getFromLocalOrRedis(key, time, unit, time, unit);
    }

    public void delete(String key) {
        Long version = stringRedisTemplate.execute(VERSIONED_CACHE_DELETE,
                java.util.List.of(CACHE_VERSION_PREFIX + key, key), "172800000");
        long invalidationVersion = version == null ? readVersion(key) + 1 : version;
        rememberVersion(key, invalidationVersion);
        localCache.invalidate(key);
        stringRedisTemplate.convertAndSend(LOCAL_CACHE_INVALIDATION_CHANNEL,
                key + INVALIDATION_SEPARATOR + invalidationVersion);
    }

    /** Invalidate only after the surrounding database transaction commits. */
    public void deleteAfterCommit(String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            delete(key);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                delete(key);
            }
        });
    }

    public void invalidateLocal(String key) {
        if (key != null && !key.isBlank()) {
            int separator = key.lastIndexOf(INVALIDATION_SEPARATOR);
            if (separator > 0) {
                String cacheKey = key.substring(0, separator);
                try {
                    long version = Long.parseLong(key.substring(separator + INVALIDATION_SEPARATOR.length()));
                    rememberVersion(cacheKey, version);
                    localCache.invalidate(cacheKey);
                    return;
                } catch (NumberFormatException ignored) {
                    // Accept legacy key-only invalidation messages during rolling upgrades.
                }
            }
            localCache.invalidate(key);
        }
    }

    //根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题,利用互斥锁解决缓存击穿问题
    public <R, ID> R queryWithPassThroughAndMutex(
           final String keyPrefix, final String lockKeyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
            Long time, TimeUnit unit, Long nullValueTTL, TimeUnit nullValueUnit){
        if (id == null) {
            return null;
        }
        // 1.布隆过滤器判断id是否存在
        if (id instanceof Long && !bloomFilterState.mayContain(bloomFilter, (Long) id)) {
            //不存在，直接返回错误
            log.info("布隆过滤器拦截，id:{}不存在", id);
            return null;
        }
        String key = keyPrefix + id;
        String lockKey = lockKeyPrefix + id;
        // 互斥锁重试有界：超过 100 次（约 5 秒）仍抢不到锁直接返回，避免无限自旋挂死
        int lockRetryCount = 0;
        while (true) {
            // 2.先查本地缓存，再查Redis缓存
            String json = getFromLocalOrRedis(key, time, unit, nullValueTTL, nullValueUnit);
            // 3.判断是否存在
            if (StrUtil.isNotBlank(json)) {
                // 存在，直接返回
                R result = JSONUtil.toBean(json, type);
                return result;
            }

            // 4.判断是否位空值（缓存空值）
            if (json != null) {
                // 是空值，返回不存在
                return null;
            }
            // 5.不存在，去数据库查找
            R result = null;
            boolean isLock = false;
            try {
                isLock = tryLock(lockKey);
                //判断是否获取锁成功
                if (!isLock) {
                    //失败，休眠并重试（有界，见上）
                    if (++lockRetryCount > 100) {
                        log.warn("获取互斥锁超时，key:{}，已重试{}次", lockKey, lockRetryCount);
                        return null;
                    }
                    Thread.sleep(50);
                    continue;
                } else {
                    // 再次检查缓存（Double Check）, 避免重复查询数据库
                    json = getFromLocalOrRedis(key, time, unit, nullValueTTL, nullValueUnit);
                    if (StrUtil.isNotBlank(json)) {
                        R cached = JSONUtil.toBean(json, type);
                        return cached;
                    }
                    if (json != null) {
                        return null;
                    }
                }
                //成功，根据id查询数据库
                result = dbFallback.apply(id);
                // 6.数据库不存在，返回错误信息
                if (result == null) {
                    //将空值写入redis（设置较短的过期时间）
                    setJson(key, "", nullValueTTL, nullValueUnit);
                    return null;
                }
                // 7.存在，写入redis
                // 生成TTL随机数,防止缓存雪崩
                Long random = (long) RandomUtil.randomInt(3, 10);
                set(key, result, time + random, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } finally {
                //释放锁
                if (isLock) {
                    unlock(lockKey);
                }
            }
            // 8.返回
            return result;
        }
    }

    //获取互斥锁
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    //释放锁
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    private String getFromLocalOrRedis(String key, Long time, TimeUnit unit, Long nullValueTTL, TimeUnit nullValueUnit) {
        LocalCacheValue localValue = getLocalValue(key);
        if (localValue != null) {
            return localValue.value;
        }
        long version = readVersion(key);
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null) {
            return null;
        }
        if (readVersion(key) != version) {
            return null;
        }
        if (StrUtil.isNotBlank(json)) {
            cacheIfUnchanged(key, json, time, unit, version);
        } else {
            cacheIfUnchanged(key, json, nullValueTTL, nullValueUnit, version);
        }
        return json;
    }

    private void setJson(String key, String json, Long time, TimeUnit unit) {
        if (time == null || unit == null || time <= 0) {
            delete(key);
            return;
        }
        long version = readVersion(key);
        long ttlMillis = Math.max(1L, unit.toMillis(time));
        Long written = stringRedisTemplate.execute(VERSIONED_CACHE_WRITE,
                java.util.List.of(CACHE_VERSION_PREFIX + key, key), Long.toString(version), json,
                Long.toString(ttlMillis));
        if (written != null && written == 1L) {
            // Keep the instance's version watermark aligned with the version
            // used by the successful Redis CAS before publishing a local value.
            // rememberVersion is monotonic, so a concurrent invalidation that
            // has already advanced this instance cannot be overwritten here.
            rememberVersion(key, version);
            // The Lua CAS already proved the version before the Redis write.
            // Avoid a redundant pre-fill GET; one post-fill check is enough to
            // discard the local entry if a delete raced after the CAS.
            cacheAfterSuccessfulWrite(key, json, time, unit, version);
        } else {
            localCache.invalidate(key);
        }
    }

    private LocalCacheValue getLocalValue(String key) {
        LocalCacheValue value = localCache.getIfPresent(key);
        if (value == null) {
            return null;
        }
        if (value.isExpired()) {
            localCache.invalidate(key);
            return null;
        }
        Long version = localVersions.getIfPresent(key);
        // Local values are usable only while their watermark is present and
        // matches. A missing watermark can happen after bounded-cache
        // eviction; fail closed and refill from Redis instead of serving a
        // value whose invalidation generation can no longer be checked.
        if (version == null || version.longValue() != value.version) {
            localCache.invalidate(key);
            return null;
        }
        return value;
    }

    private void cacheIfUnchanged(String key, String value, Long time, TimeUnit unit, long expectedVersion) {
        if (readVersion(key) != expectedVersion) {
            localCache.invalidate(key);
            return;
        }
        if (!putLocalValueIfVersionMatches(key, value, time, unit, expectedVersion)) {
            return;
        }
        if (readVersion(key) != expectedVersion) {
            localCache.invalidate(key);
        }
    }

    private void cacheAfterSuccessfulWrite(String key, String value, Long time, TimeUnit unit,
                                           long expectedVersion) {
        if (!putLocalValueIfVersionMatches(key, value, time, unit, expectedVersion)) {
            return;
        }
        if (readVersion(key) != expectedVersion) {
            localCache.invalidate(key);
        }
    }

    private boolean putLocalValueIfVersionMatches(String key, String value, Long time, TimeUnit unit,
                                                  long expectedVersion) {
        Long knownVersion = localVersions.getIfPresent(key);
        if (knownVersion != null && knownVersion.longValue() != expectedVersion) {
            localCache.invalidate(key);
            return false;
        }
        if (time == null || unit == null || time <= 0) {
            localCache.invalidate(key);
            return false;
        }
        long ttlNanos = Math.min(unit.toNanos(time), TimeUnit.SECONDS.toNanos(LOCAL_CACHE_TTL_SECONDS));
        rememberVersion(key, expectedVersion);
        localCache.put(key, new LocalCacheValue(value, System.nanoTime() + ttlNanos, expectedVersion));
        return true;
    }

    private long readVersion(String key) {
        String version = stringRedisTemplate.opsForValue().get(CACHE_VERSION_PREFIX + key);
        if (version == null || version.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(version);
        } catch (NumberFormatException malformed) {
            log.warn("缓存版本值无效，按 0 处理 key={}", CACHE_VERSION_PREFIX + key);
            return 0L;
        }
    }

    private void rememberVersion(String key, long version) {
        localVersions.asMap().compute(key, (ignored, current) ->
                current == null ? version : Math.max(current, version));
    }

    private static class LocalCacheValue {
        private final String value;
        private final long expireAtNanos;
        private final long version;

        private LocalCacheValue(String value, long expireAtNanos, long version) {
            this.value = value;
            this.expireAtNanos = expireAtNanos;
            this.version = version;
        }

        private boolean isExpired() {
            return System.nanoTime() - expireAtNanos >= 0;
        }
    }

}
