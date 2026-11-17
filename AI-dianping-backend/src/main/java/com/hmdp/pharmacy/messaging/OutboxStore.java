package com.hmdp.pharmacy.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "app.pharmacy.seckill.enabled", havingValue = "true")
public class OutboxStore {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;

    public OutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public List<OutboxEvent> claim(int limit, String owner) {
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        LocalDateTime leaseBefore = now.minusSeconds(30);
        List<OutboxEvent> events = jdbc.query("SELECT id,dedupe_key,aggregate_type,aggregate_id,event_type,payload,attempt_count " +
                        "FROM outbox_event WHERE (status='PENDING' AND available_at<=?) OR " +
                        "(status='CLAIMED' AND locked_at<=?) ORDER BY created_at LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, rowNum) -> new OutboxEvent(rs.getString("id"), rs.getString("dedupe_key"),
                        rs.getString("aggregate_type"), rs.getString("aggregate_id"), rs.getString("event_type"),
                        rs.getString("payload"), rs.getInt("attempt_count")), now, leaseBefore, limit);
        for (OutboxEvent event : events) {
            jdbc.update("UPDATE outbox_event SET status='CLAIMED',locked_by=?,locked_at=? WHERE id=?",
                    owner, now, event.id());
        }
        return events;
    }

    @Transactional
    public void markSent(String eventId, String owner) {
        jdbc.update("UPDATE outbox_event SET status='SENT',sent_at=?,locked_by=NULL,locked_at=NULL,last_error=NULL " +
                        "WHERE id=? AND status='CLAIMED' AND locked_by=?",
                LocalDateTime.now(BUSINESS_ZONE), eventId, owner);
    }

    @Transactional
    public void markFailed(OutboxEvent event, String owner, Throwable failure) {
        int attempts = event.attemptCount() + 1;
        String message = failure == null || failure.getMessage() == null
                ? "Kafka send failed" : failure.getMessage();
        if (message.length() > 1000) {
            message = message.substring(0, 1000);
        }
        if (attempts >= 3) {
            int changed = jdbc.update("UPDATE outbox_event SET status='DEAD',attempt_count=?,last_error=?,locked_by=NULL,locked_at=NULL " +
                            "WHERE id=? AND status='CLAIMED' AND locked_by=?",
                    attempts, message, event.id(), owner);
            if (changed == 1) {
                jdbc.update("INSERT INTO dead_letter_event(event_id,dedupe_key,aggregate_type,aggregate_id,event_type,payload,attempt_count,last_error) " +
                                "VALUES (?,?,?,?,?,?,?,?)",
                        event.id(), event.dedupeKey(), event.aggregateType(), event.aggregateId(),
                        event.eventType(), event.payload(), attempts, message);
            }
            return;
        }
        long delaySeconds = 1L << attempts;
        jdbc.update("UPDATE outbox_event SET status='PENDING',attempt_count=?,available_at=?,last_error=?,locked_by=NULL,locked_at=NULL " +
                        "WHERE id=? AND status='CLAIMED' AND locked_by=?",
                attempts, LocalDateTime.now(BUSINESS_ZONE).plusSeconds(delaySeconds), message, event.id(), owner);
    }

    public record OutboxEvent(String id, String dedupeKey, String aggregateType, String aggregateId,
                              String eventType, String payload, int attemptCount) { }
}
