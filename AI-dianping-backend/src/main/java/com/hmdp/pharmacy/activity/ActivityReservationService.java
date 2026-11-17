package com.hmdp.pharmacy.activity;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.pharmacy.seckill.enabled", havingValue = "true")
public class ActivityReservationService {
    private static final DefaultRedisScript<Long> RESERVE_SCRIPT = new DefaultRedisScript<>();
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    static {
        RESERVE_SCRIPT.setLocation(new ClassPathResource("pharmacy/reserve.lua"));
        RESERVE_SCRIPT.setResultType(Long.class);
    }

    private final PharmacyRequestService requests;
    private final StringRedisTemplate redis;
    private final int requestTtlSeconds;
    private final int testPauseAfterRedisMs;
    private final int activityRateLimit;
    private final int ipRateLimit;
    private final int userRateLimit;

    public ActivityReservationService(PharmacyRequestService requests,
                                      StringRedisTemplate redis,
                                      @Value("${app.pharmacy.seckill.request-ttl-seconds:120}") int requestTtlSeconds,
                                      @Value("${app.pharmacy.seckill.test-pause-after-redis-ms:0}") int testPauseAfterRedisMs,
                                      @Value("${app.pharmacy.seckill.activity-rate-per-second:500}") int activityRateLimit,
                                      @Value("${app.pharmacy.seckill.ip-rate-per-second:10}") int ipRateLimit,
                                      @Value("${app.pharmacy.seckill.user-rate-per-second:5}") int userRateLimit) {
        this.requests = requests;
        this.redis = redis;
        this.requestTtlSeconds = requestTtlSeconds;
        this.testPauseAfterRedisMs = Math.max(0, testPauseAfterRedisMs);
        this.activityRateLimit = activityRateLimit;
        this.ipRateLimit = ipRateLimit;
        this.userRateLimit = userRateLimit;
    }

    public PharmacyRequestService.RequestView reserve(long activityId, Integer requestedQuantity,
                                                       String idempotencyKey, String clientIp) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            throw new SecurityException("请先登录");
        }
        if (activityId <= 0) {
            throw new IllegalArgumentException("活动编号无效");
        }
        int quantity = requestedQuantity == null ? 1 : requestedQuantity;
        String requestId = normalizeRequestId(idempotencyKey);
        PharmacyRequestService.Intent intent = requests.createIntent(requestId, activityId,
                user.getId(), quantity, requestTtlSeconds);
        if (!"INITIALIZING".equals(intent.status())) {
            return requests.getRequest(requestId, user.getId());
        }

        long now = LocalDateTime.now(BUSINESS_ZONE).atZone(BUSINESS_ZONE).toEpochSecond();
        long expiresAt = now + requestTtlSeconds;
        String id = Long.toString(activityId);
        String window = Long.toString(now);
        Long code;
        try {
            code = redis.execute(RESERVE_SCRIPT,
                    List.of("pharmacy:seckill:stock:" + id,
                            "pharmacy:seckill:status:" + id,
                            "pharmacy:seckill:activity:" + id,
                            "pharmacy:seckill:user:" + id,
                            "pharmacy:seckill:reservation:" + requestId,
                            "pharmacy:seckill:pending:" + id,
                            "pharmacy:seckill:rate:activity:" + id + ":" + window,
                            "pharmacy:seckill:rate:ip:" + sha256(clientIp) + ":" + window,
                            "pharmacy:seckill:rate:user:" + id + ":" + user.getId() + ":" + window),
                    requestId, user.getId().toString(), Integer.toString(quantity), Long.toString(now),
                    Long.toString(expiresAt), id, "2", Integer.toString(activityRateLimit),
                    Integer.toString(ipRateLimit), Integer.toString(userRateLimit), "604800");
        } catch (RuntimeException ex) {
            // The durable INITIALIZING row lets a reconciler distinguish an unknown Redis outcome.
            log.warn("预约 Redis 结果未知，保留请求供恢复流程核对 requestId={}", requestId, ex);
            return requests.getRequest(requestId, user.getId());
        }

        if (code == null) {
            log.warn("预约 Lua 未返回状态，保留请求供恢复流程核对 requestId={}", requestId);
            return requests.getRequest(requestId, user.getId());
        }
        if (code == 0 || code == 1) {
            if (testPauseAfterRedisMs > 0) {
                try {
                    Thread.sleep(testPauseAfterRedisMs);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("预约故障注入暂停被中断", ex);
                }
            }
            return requests.recordAccepted(requestId);
        }

        String failure = switch (code.intValue()) {
            case 2 -> "DUPLICATE_USER";
            case 3 -> "SOLD_OUT";
            case 4 -> "ACTIVITY_UNAVAILABLE";
            case 5 -> "NOT_STARTED";
            case 6 -> "ENDED";
            case 7 -> "RATE_LIMITED";
            default -> "INVALID_OR_INCONSISTENT";
        };
        return requests.rejectInitializing(requestId, failure);
    }

    public PharmacyRequestService.RequestView getRequest(String requestId) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            throw new SecurityException("请先登录");
        }
        return requests.getRequest(normalizeRequestId(requestId), user.getId());
    }

    private String normalizeRequestId(String raw) {
        if (raw == null || raw.isBlank()) {
            return UUID.randomUUID().toString();
        }
        try {
            return UUID.fromString(raw.trim()).toString();
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Idempotency-Key 必须是 UUID", ex);
        }
    }

    private String sha256(String value) {
        String normalized = value == null || value.isBlank() ? "unknown" : value.trim();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }
}
