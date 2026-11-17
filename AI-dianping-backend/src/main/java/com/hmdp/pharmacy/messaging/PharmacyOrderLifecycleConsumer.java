package com.hmdp.pharmacy.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.pharmacy.activity.ActivityRedisService;
import com.hmdp.pharmacy.order.PharmacyOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.pharmacy.seckill.enabled", havingValue = "true")
public class PharmacyOrderLifecycleConsumer {
    private final ObjectMapper objectMapper;
    private final ActivityRedisService redis;

    public PharmacyOrderLifecycleConsumer(ObjectMapper objectMapper, ActivityRedisService redis) {
        this.objectMapper = objectMapper;
        this.redis = redis;
    }

    @KafkaListener(topics = "${app.pharmacy.seckill.order-topic:pharmacy-order-events}",
            groupId = "${app.pharmacy.seckill.order-lifecycle-group:pharmacy-order-lifecycle}",
            containerFactory = "pharmacyKafkaListenerContainerFactory")
    public void apply(String payload, Acknowledgment acknowledgment) throws Exception {
        PharmacyOrderService.OrderCancelledEvent event = objectMapper.readValue(
                payload, PharmacyOrderService.OrderCancelledEvent.class);
        Long changed = redis.cancelReservation(event.requestId(), event.activityId(), event.orderNo(),
                event.restoreStock(), event.userId());
        if (changed == null || changed < 0) {
            throw new IllegalStateException("Redis cancellation projection needs retry");
        }
        log.info("订单取消缓存投影完成 orderNo={} restored={}", event.orderNo(), event.restoreStock());
        acknowledgment.acknowledge();
    }
}
