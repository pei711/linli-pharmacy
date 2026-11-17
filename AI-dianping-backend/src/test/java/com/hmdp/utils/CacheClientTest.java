package com.hmdp.utils;

import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import com.google.common.hash.BloomFilter;
import com.hmdp.config.BloomFilter.BloomFilterState;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Answers.RETURNS_DEFAULTS;

class CacheClientTest {
    @Test
    @SuppressWarnings("unchecked")
    void setWithMissingRedisVersionIsImmediatelyReadableFromLocalCache() {
        AtomicReference<String> redisVersion = new AtomicReference<>();
        AtomicReference<String> redisCachedValue = new AtomicReference<>();
        StringRedisTemplate redis = mock(StringRedisTemplate.class, invocation -> {
            if (invocation.getMethod().getName().equals("execute")) {
                if (!(invocation.getArgument(0) instanceof RedisScript<?>)) {
                    throw new AssertionError("Redis execute must receive a script: " + invocation);
                }
                RedisScript<?> script = invocation.getArgument(0);
                if (!script.getScriptAsString().contains("PSETEX")) {
                    throw new AssertionError("Unexpected Redis script: " + script.getScriptAsString());
                }
                List<?> keys = invocation.getArgument(1);
                Object[] args = redisScriptArguments(invocation);
                assertEquals(List.of("cache:version:v1:cache:shop:13", "cache:shop:13"), keys);
                assertEquals(3, args.length);
                assertEquals("0", args[0], "a missing Redis version key must use the Lua version-0 default");
                assertEquals("{\"name\":\"test\"}", args[1]);
                assertEquals("60000", args[2]);
                String currentVersion = redisVersion.get() == null ? "0" : redisVersion.get();
                if (!currentVersion.equals(args[0])) {
                    return 0L;
                }
                redisCachedValue.set((String) args[1]);
                return 1L;
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(any(String.class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if ("cache:version:v1:cache:shop:13".equals(key)) {
                return redisVersion.get();
            }
            throw new AssertionError("set followed by a local hit must not read an unexpected Redis key: " + key);
        });
        CacheClient client = new CacheClient(redis);

        client.set("cache:shop:13", Map.of("name", "test"), 60L, TimeUnit.SECONDS);

        Cache<String, Long> localVersions = (Cache<String, Long>) ReflectionTestUtils.getField(client, "localVersions");
        assertEquals(Long.valueOf(0L), localVersions.getIfPresent("cache:shop:13"),
                "a missing Redis version key must leave a version-0 local watermark after the successful CAS");
        Cache<String, Object> localCache = (Cache<String, Object>) ReflectionTestUtils.getField(client, "localCache");
        Object localValue = localCache.getIfPresent("cache:shop:13");
        assertTrue(localValue != null, "a successful CAS must populate the local cache before get()");
        assertEquals("{\"name\":\"test\"}", ReflectionTestUtils.getField(localValue, "value"));
        assertEquals(0L, ((Number) ReflectionTestUtils.getField(localValue, "version")).longValue());
        assertEquals("{\"name\":\"test\"}", client.get("cache:shop:13", 60L, TimeUnit.SECONDS),
                "set() must populate a local value usable by the immediately following get()");
        verify(values, never()).get("cache:shop:13");
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisHitDoesNotCallDatabaseAndThenUsesLocalCache() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("cache:shop:8")).thenReturn("{\"id\":8}");
        BloomFilter<Long> bloom = mock(BloomFilter.class);
        BloomFilterState state = new BloomFilterState();
        CacheClient client = new CacheClient(redis);
        ReflectionTestUtils.setField(client, "bloomFilter", bloom);
        ReflectionTestUtils.setField(client, "bloomFilterState", state);

        Map result = client.queryWithPassThroughAndMutex("cache:shop:", "lock:shop:", 8L,
                Map.class, id -> { throw new AssertionError("Redis hit must not query MySQL"); },
                60L, TimeUnit.SECONDS, 5L, TimeUnit.SECONDS);
        assertEquals(8, ((Number) result.get("id")).intValue());
        verify(values).get("cache:shop:8");
        verifyNoInteractions(bloom);

        client.queryWithPassThroughAndMutex("cache:shop:", "lock:shop:", 8L,
                Map.class, id -> { throw new AssertionError("local hit must not query MySQL"); },
                60L, TimeUnit.SECONDS, 5L, TimeUnit.SECONDS);
        verify(values, org.mockito.Mockito.times(1)).get("cache:shop:8");
    }

    @Test
    @SuppressWarnings("unchecked")
    void bloomFilterAcceptsKnownExistingId() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        // 缓存未命中、互斥锁可获取：否则 tryLock 永远返回 false，生产代码的重试循环会无限自旋
        when(values.get("cache:shop:9")).thenReturn(null);
        when(values.setIfAbsent(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(TimeUnit.class))).thenReturn(true);
        BloomFilter<Long> bloom = mock(BloomFilter.class);
        when(bloom.mightContain(9L)).thenReturn(true);
        BloomFilterState state = new BloomFilterState();
        state.markReady();
        CacheClient client = new CacheClient(redis);
        ReflectionTestUtils.setField(client, "bloomFilter", bloom);
        ReflectionTestUtils.setField(client, "bloomFilterState", state);

        Map result = client.queryWithPassThroughAndMutex("cache:shop:", "lock:shop:", 9L,
                Map.class, id -> Map.of("id", id), 60L, TimeUnit.SECONDS, 5L, TimeUnit.SECONDS);

        assertTrue(state.isReady());
        assertEquals(9L, ((Number) result.get("id")).longValue());
        verify(bloom).mightContain(9L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void bloomFilterFailsOpenBeforeSnapshotIsReady() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(values.get("cache:version:v1:cache:shop:42")).thenReturn("0");
        when(values.get("cache:shop:42")).thenReturn(null);
        BloomFilter<Long> bloom = mock(BloomFilter.class);
        BloomFilterState state = new BloomFilterState();
        CacheClient client = new CacheClient(redis);
        ReflectionTestUtils.setField(client, "bloomFilter", bloom);
        ReflectionTestUtils.setField(client, "bloomFilterState", state);

        Map result = client.queryWithPassThroughAndMutex("cache:shop:", "lock:shop:", 42L,
                Map.class, id -> Map.of("id", id), 60L, TimeUnit.SECONDS, 5L, TimeUnit.SECONDS);

        assertEquals(42L, ((Number) result.get("id")).longValue());
        verifyNoInteractions(bloom);
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesBoundedLocalValueAndPublishesCrossInstanceInvalidation() {
        // Keep the Redis representation exact: a missing version key is null,
        // while the delete script creates the key with its incremented value.
        AtomicReference<String> redisVersion = new AtomicReference<>();
        AtomicInteger versionReadCalls = new AtomicInteger();
        AtomicReference<String> redisCachedValue = new AtomicReference<>();
        AtomicInteger writeScriptCalls = new AtomicInteger();
        AtomicInteger deleteScriptCalls = new AtomicInteger();
        // Handle script execution in the default answer. Do not inspect raw
        // varargs shape: Mockito versions may expose the final Object[] in
        // different ways, and an unmatched script must never silently return null.
        StringRedisTemplate redis = mock(StringRedisTemplate.class, invocation -> {
            if (invocation.getMethod().getName().equals("execute")) {
                if (!(invocation.getArgument(0) instanceof RedisScript<?>)) {
                    throw new AssertionError("Redis execute must receive a script: " + invocation);
                }
                RedisScript<?> script = invocation.getArgument(0);
                String lua = script.getScriptAsString();
                List<?> scriptKeys = invocation.getArgument(1);
                Object[] scriptArgs = redisScriptArguments(invocation);
                if (lua.contains("INCR")) {
                    assertEquals(List.of("cache:version:v1:cache:shop:7", "cache:shop:7"), scriptKeys);
                    assertEquals(1, scriptArgs.length);
                    deleteScriptCalls.incrementAndGet();
                    redisCachedValue.set(null);
                    long nextVersion = Long.parseLong(redisVersion.get() == null ? "0" : redisVersion.get()) + 1;
                    redisVersion.set(Long.toString(nextVersion));
                    return nextVersion;
                }
                if (lua.contains("PSETEX")) {
                    assertEquals(List.of("cache:version:v1:cache:shop:7", "cache:shop:7"), scriptKeys);
                    assertEquals(3, scriptArgs.length);
                    writeScriptCalls.incrementAndGet();
                    assertEquals("0", scriptArgs[0], "an absent Redis version key is version 0");
                    assertEquals("{\"name\":\"test\"}", scriptArgs[1]);
                    assertEquals("60000", scriptArgs[2]);
                    String currentVersion = redisVersion.get() == null ? "0" : redisVersion.get();
                    if (!currentVersion.equals(scriptArgs[0])) {
                        return 0L;
                    }
                    redisCachedValue.set((String) scriptArgs[1]);
                    return 1L;
                }
                throw new AssertionError("Unexpected Redis script: " + lua);
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        // Model an absent Redis version key as null until the delete script
        // creates it. CacheClient must interpret that initial state as 0,
        // matching the Lua write script's `GET(...) or '0'` behavior.
        when(values.get("cache:version:v1:cache:shop:7"))
                .thenAnswer(invocation -> {
                    versionReadCalls.incrementAndGet();
                    return redisVersion.get();
                });
        when(values.get("cache:shop:7")).thenAnswer(invocation -> redisCachedValue.get());
        CacheClient client = new CacheClient(redis);

        client.set("cache:shop:7", Map.of("name", "test"), 60L, TimeUnit.SECONDS);
        assertEquals(1, writeScriptCalls.get(), "the Redis write script must be stubbed and executed");
        assertEquals("{\"name\":\"test\"}", redisCachedValue.get(),
                "the Lua mock must persist JSON when the initially absent version key matches version 0");
        Cache<String, Long> localVersions = (Cache<String, Long>) ReflectionTestUtils.getField(client, "localVersions");
        assertEquals(Long.valueOf(0L), localVersions.getIfPresent("cache:shop:7"),
                "a successful Redis write must retain its version as the local cache watermark");
        Cache<String, Object> localCache = (Cache<String, Object>) ReflectionTestUtils.getField(client, "localCache");
        Object localValue = localCache.getIfPresent("cache:shop:7");
        assertTrue(localValue != null, "a successful Redis write must install a bounded local cache entry");
        assertEquals("{\"name\":\"test\"}", ReflectionTestUtils.getField(localValue, "value"),
                "the local cache entry must contain the JSON written by the Lua script");
        assertEquals(0L, ((Number) ReflectionTestUtils.getField(localValue, "version")).longValue(),
                "the local cache entry must carry the same version as its watermark");
        assertEquals(0, deleteScriptCalls.get());
        assertEquals(2, versionReadCalls.get(),
                "the successful Lua CAS validates before write and one post-fill read fences a racing delete");
        assertEquals("{\"name\":\"test\"}", client.get("cache:shop:7", 60L, TimeUnit.SECONDS));
        assertEquals(2, versionReadCalls.get(), "a local Caffeine hit must not read the Redis version key");
        verify(values, never()).get("cache:shop:7");

        client.delete("cache:shop:7");
        assertEquals(1, writeScriptCalls.get());
        assertEquals(1, deleteScriptCalls.get(), "delete must advance the Redis version exactly once");
        assertEquals("1", redisVersion.get(), "delete must persist the version created from an absent key");
        verify(redis).convertAndSend(CacheClient.LOCAL_CACHE_INVALIDATION_CHANNEL, "cache:shop:7\n1");
        redisCachedValue.set("{\"name\":\"updated\"}");
        assertEquals("{\"name\":\"updated\"}", client.get("cache:shop:7", 60L, TimeUnit.SECONDS));
        assertEquals(6, versionReadCalls.get(), "Redis refill must validate the version around the data read and local fill");
        verify(values, org.mockito.Mockito.atLeast(1)).get("cache:version:v1:cache:shop:7");
        verify(values).get("cache:shop:7");

        // A bounded local version cache can evict its watermark independently
        // from the value cache. Such a value must fail closed and refill from
        // Redis instead of being returned without a version check.
        localVersions.invalidate("cache:shop:7");
        assertEquals("{\"name\":\"updated\"}", client.get("cache:shop:7", 60L, TimeUnit.SECONDS));
        assertEquals(10, versionReadCalls.get(), "a missing local watermark must force a version-checked Redis refill");
        verify(values, org.mockito.Mockito.times(2)).get("cache:shop:7");
    }

    private static Object[] redisScriptArguments(InvocationOnMock invocation) {
        Object[] rawArguments = invocation.getRawArguments();
        if (rawArguments.length < 3) {
            throw new AssertionError("Redis script execute call is missing keys/arguments");
        }

        // Mockito may expose the Redis varargs as one array, expanded values,
        // or a partially expanded mix. Flatten every argument after the keys
        // so the fake Redis script sees the same ARGV sequence in each case.
        List<Object> flattened = new ArrayList<>();
        for (int i = 2; i < rawArguments.length; i++) {
            appendRedisScriptArgument(flattened, rawArguments[i]);
        }
        return flattened.toArray();
    }

    private static void appendRedisScriptArgument(List<Object> flattened, Object argument) {
        if (argument instanceof Object[] nested) {
            for (Object item : nested) {
                appendRedisScriptArgument(flattened, item);
            }
        } else {
            flattened.add(argument);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void versionedPubSubInvalidationEvictsAnotherInstancesLocalValue() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.<Long>execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);
        when(values.get("cache:version:v1:cache:shop:11")).thenReturn("0", "0", "1", "1");
        when(values.get("cache:shop:11")).thenReturn("{\"name\":\"old\"}", "{\"name\":\"new\"}");
        CacheClient otherInstance = new CacheClient(redis);

        assertEquals("{\"name\":\"old\"}", otherInstance.get("cache:shop:11", 60L, TimeUnit.SECONDS));
        otherInstance.invalidateLocal("cache:shop:11\n1");
        assertEquals("{\"name\":\"new\"}", otherInstance.get("cache:shop:11", 60L, TimeUnit.SECONDS));

        verify(values, org.mockito.Mockito.times(2)).get("cache:shop:11");
    }

    @Test
    void directReadDoesNotReturnRedisValueWhenInvalidationRacesTheRead() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("cache:version:v1:cache:shop:12")).thenReturn("0", "1");
        when(values.get("cache:shop:12")).thenReturn("{\"name\":\"stale\"}");
        CacheClient client = new CacheClient(redis);

        assertNull(client.get("cache:shop:12", 60L, TimeUnit.SECONDS));
        verify(values).get("cache:shop:12");
    }
}
