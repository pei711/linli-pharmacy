package com.hmdp.pharmacy.messaging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.pharmacy.seckill.enabled", havingValue = "true")
public class OutboxRelay {
    private final OutboxStore store;
    private final KafkaTemplate<String, String> kafka;
    private final String owner = "outbox-" + UUID.randomUUID();
    private final String requestTopic;
    private final String orderTopic;

    public OutboxRelay(OutboxStore store, KafkaTemplate<String, String> kafka,
                      @Value("${app.pharmacy.seckill.requested-topic:pharmacy-seckill-requested}") String requestTopic,
                      @Value("${app.pharmacy.seckill.order-topic:pharmacy-order-events}") String orderTopic) {
        this.store = store;
        this.kafka = kafka;
        this.requestTopic = requestTopic;
        this.orderTopic = orderTopic;
    }

    @Scheduled(fixedDelayString = "${app.pharmacy.seckill.outbox-relay-delay-ms:1000}")
    public void relay() {
        final List<OutboxStore.OutboxEvent> events;
        try {
            events = store.claim(20, owner);
        } catch (RuntimeException ex) {
            log.error("领取药房 Outbox 事件失败", ex);
            return;
        }
        for (OutboxStore.OutboxEvent event : events) {
            try {
                String topic = switch (event.eventType()) {
                    case "pharmacy.seckill.requested.v1" -> requestTopic;
                    case "pharmacy.order.cancelled.v1" -> orderTopic;
                    default -> throw new IllegalArgumentException("Unsupported Outbox event type: " + event.eventType());
                };
                kafka.executeInTransaction(operations -> {
                    try {
                        operations.send(topic, event.aggregateId(), event.payload()).get(10, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while publishing Outbox event", interrupted);
                    } catch (Exception sendFailure) {
                        throw new IllegalStateException("Failed to publish Outbox event", sendFailure);
                    }
                    return null;
                });
                store.markSent(event.id(), owner);
            } catch (Exception ex) {
                log.error("Outbox 发送失败 eventId={} attempt={}", event.id(), event.attemptCount() + 1, ex);
                store.markFailed(event, owner, ex);
            }
        }
    }
}
