package com.hmdp.pharmacy.activity;

import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.List;

@Service
@ConditionalOnProperty(name = "app.pharmacy.seckill.enabled", havingValue = "true")
public class ActivityRedisService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DefaultRedisScript<Long> INIT_SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> COMPLETE_SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> CANCEL_SCRIPT = new DefaultRedisScript<>();

    static {
        INIT_SCRIPT.setLocation(new ClassPathResource("pharmacy/activity-init.lua"));
        INIT_SCRIPT.setResultType(Long.class);
        COMPLETE_SCRIPT.setLocation(new ClassPathResource("pharmacy/complete-reservation.lua"));
        COMPLETE_SCRIPT.setResultType(Long.class);
        CANCEL_SCRIPT.setLocation(new ClassPathResource("pharmacy/cancel-order-redis.lua"));
        CANCEL_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redis;

    public ActivityRedisService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Long initialize(ActivityInventoryService.Publication activity, long ttlSeconds) {
        String id = Long.toString(activity.id());
        long startsAt = activity.startAt().atZone(BUSINESS_ZONE).toEpochSecond();
        long endsAt = activity.endAt().atZone(BUSINESS_ZONE).toEpochSecond();
        return redis.execute(INIT_SCRIPT,
                List.of("pharmacy:seckill:stock:" + id, "pharmacy:seckill:status:" + id, "pharmacy:seckill:activity:" + id),
                Integer.toString(activity.quantity()), Long.toString(startsAt), Long.toString(endsAt),
                Long.toString(activity.medicineId()), Integer.toString(activity.mayInitializeRedis() ? 1 : 0),
                Long.toString(ttlSeconds), Integer.toString(activity.perUserLimit()));
    }

    public Long completeReservation(String requestId, long activityId, String orderNo) {
        return redis.execute(COMPLETE_SCRIPT,
                List.of("pharmacy:seckill:reservation:" + requestId, "pharmacy:seckill:pending:" + activityId),
                requestId, orderNo);
    }

    public Long cancelReservation(String requestId, long activityId, String orderNo,
                                  int restoreStock, long userId) {
        return redis.execute(CANCEL_SCRIPT,
                List.of("pharmacy:seckill:stock:" + activityId, "pharmacy:seckill:user:" + activityId,
                        "pharmacy:seckill:reservation:" + requestId, "pharmacy:seckill:pending:" + activityId),
                requestId, orderNo, Integer.toString(restoreStock), Long.toString(userId));
    }
}
