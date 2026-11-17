package com.hmdp.pharmacy.activity;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.pharmacy.seckill.enabled", havingValue = "true")
public class ReservationRecoveryJob {
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>();

    static {
        RELEASE_SCRIPT.setLocation(new ClassPathResource("pharmacy/release-reservation.lua"));
        RELEASE_SCRIPT.setResultType(Long.class);
    }

    private final PharmacyRequestService requests;
    private final StringRedisTemplate redis;

    public ReservationRecoveryJob(PharmacyRequestService requests, StringRedisTemplate redis) {
        this.requests = requests;
        this.redis = redis;
    }

    @Scheduled(fixedDelayString = "${app.pharmacy.seckill.reconciliation-delay-ms:5000}")
    public void reconcile() {
        final List<PharmacyRequestService.RequestCandidate> candidates;
        try {
            candidates = requests.findRecoveryCandidates(100);
        } catch (RuntimeException ex) {
            log.error("读取待恢复的药房预约请求失败", ex);
            return;
        }
        for (PharmacyRequestService.RequestCandidate candidate : candidates) {
            try {
                reconcile(candidate);
            } catch (RuntimeException ex) {
                log.error("恢复药房预约请求失败 requestId={}", candidate.requestId(), ex);
            }
        }
    }

    private void reconcile(PharmacyRequestService.RequestCandidate candidate) {
        if ("RELEASE_PENDING".equals(candidate.reservationState())) {
            release(candidate);
            return;
        }
        String redisState = (String) redis.opsForHash().get(
                "pharmacy:seckill:reservation:" + candidate.requestId(), "state");
        boolean redisReservationExists = "PROCESSING".equals(redisState);
        boolean expired = !candidate.expiresAt().isAfter(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")));

        if ("INITIALIZING".equals(candidate.status())) {
            if (redisReservationExists && !expired) {
                // Repairs a crash after Lua accepted stock but before the MySQL Outbox transaction.
                requests.recordAccepted(candidate.requestId());
                return;
            }
            if (expired && requests.expire(candidate.requestId(), "INITIALIZING", redisReservationExists)
                    && redisReservationExists) {
                release(candidate);
            }
            return;
        }
        if ("PROCESSING".equals(candidate.status()) && expired
                && requests.expire(candidate.requestId(), "PROCESSING", true)) {
            release(candidate);
        }
    }

    private void release(PharmacyRequestService.RequestCandidate candidate) {
        String activityId = Long.toString(candidate.activityId());
        String requestId = candidate.requestId();
        Long result = redis.execute(RELEASE_SCRIPT,
                List.of("pharmacy:seckill:stock:" + activityId,
                        "pharmacy:seckill:status:" + activityId,
                        "pharmacy:seckill:activity:" + activityId,
                        "pharmacy:seckill:user:" + activityId,
                        "pharmacy:seckill:reservation:" + requestId,
                        "pharmacy:seckill:pending:" + activityId),
                requestId, Long.toString(candidate.userId()));
        if (result != null && result == 1L) {
            requests.markReleased(requestId);
        } else {
            log.error("预约补偿未能安全返还 Redis 预占，保留 RELEASE_PENDING requestId={} code={}", requestId, result);
        }
    }
}
