package com.hmdp.pharmacy.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.pharmacy.activity.ActivityRedisService;
import com.hmdp.pharmacy.order.PharmacyOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.pharmacy.seckill.enabled", havingValue = "true")
public class PharmacyOrderConsumer {
    private final ObjectMapper objectMapper;
    private final PharmacyOrderService orders;
    private final ActivityRedisService redis;

    /** Test-only crash window; defaults to disabled in every normal environment. */
    @Value("${app.pharmacy.seckill.test-pause-before-ack-ms:0}")
    private long testPauseBeforeAckMs;

    public PharmacyOrderConsumer(ObjectMapper objectMapper, PharmacyOrderService orders, ActivityRedisService redis) {
        this.objectMapper = objectMapper;
        this.orders = orders;
        this.redis = redis;
    }

    @KafkaListener(topics = "${app.pharmacy.seckill.requested-topic:pharmacy-seckill-requested}",
            groupId = "${app.pharmacy.seckill.consumer-group:pharmacy-order-writer}",
            containerFactory = "pharmacyKafkaListenerContainerFactory")
    public void createOrder(String payload, Acknowledgment acknowledgment) throws Exception {
        Event event = objectMapper.readValue(payload, Event.class);
        if (event.requestId() == null || event.requestId().isBlank()) {
            throw new IllegalArgumentException("Kafka reservation event has no requestId");
        }
        PharmacyOrderService.OrderResult result = orders.createFromRequest(event.requestId());
        if (testPauseBeforeAckMs > 0) {
            // createFromRequest returns after its MySQL transaction commits. A test stack can
            // SIGKILL this process here to exercise redelivery before the manual Kafka ACK.
            Thread.sleep(testPauseBeforeAckMs);
        }
        if ("SUCCESS".equals(result.status())) {
            Long redisUpdated = redis.completeReservation(result.requestId(), result.activityId(), result.orderNo());
            if (redisUpdated == null || redisUpdated < 0) {
                // MySQL already committed. Retrying is safe: the consumer loads the existing order by requestId.
                throw new IllegalStateException("MySQL order committed but Redis status update needs retry");
            }
            log.info("药房预约订单创建完成 requestId={} orderNo={}", result.requestId(), result.orderNo());
        }
        acknowledgment.acknowledge();
    }

    private record Event(String requestId) { }
}
