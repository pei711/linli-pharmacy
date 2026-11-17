package com.hmdp.pharmacy.activity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "app.pharmacy.seckill.enabled", havingValue = "true")
public class PharmacyRequestService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PharmacyRequestService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Intent createIntent(String requestId, long activityId, long userId, int quantity, int ttlSeconds) {
        RequestRow existing = findById(requestId);
        if (existing != null) {
            ensureSameRequest(existing, activityId, userId, quantity);
            return new Intent(existing.requestId(), existing.status(), false);
        }
        ActivityRow activity = jdbc.query("SELECT status,per_user_limit,start_at,end_at FROM seckill_activity WHERE id=?",
                        (rs, rowNum) -> new ActivityRow(rs.getString("status"), rs.getInt("per_user_limit"),
                                rs.getObject("start_at", LocalDateTime.class), rs.getObject("end_at", LocalDateTime.class)),
                        activityId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("活动不存在"));
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        if (!"PUBLISHED".equals(activity.status()) || now.isBefore(activity.startAt()) || !now.isBefore(activity.endAt())) {
            throw new IllegalArgumentException("活动当前不可预约");
        }
        if (quantity <= 0 || quantity > activity.perUserLimit()) {
            throw new IllegalArgumentException("预约数量超过活动限购数量");
        }
        LocalDateTime expiresAt = now.plusSeconds(ttlSeconds);
        try {
            jdbc.update("INSERT INTO seckill_request(request_id,activity_id,user_id,quantity,status,reservation_state,expires_at) " +
                            "VALUES (?,?,?,?,'INITIALIZING','NONE',?)",
                    requestId, activityId, userId, quantity, expiresAt);
            return new Intent(requestId, "INITIALIZING", true);
        } catch (DuplicateKeyException duplicate) {
            RequestRow sameRequest = findById(requestId);
            if (sameRequest != null) {
                ensureSameRequest(sameRequest, activityId, userId, quantity);
                return new Intent(sameRequest.requestId(), sameRequest.status(), false);
            }
            throw new IllegalArgumentException("该用户已预约过此活动", duplicate);
        }
    }

    /** Changes the durable request state and emits its Kafka intent in the same MySQL transaction. */
    @Transactional
    public RequestView recordAccepted(String requestId) {
        RequestRow request = findByIdForUpdate(requestId);
        if (request == null) {
            throw new IllegalArgumentException("预约请求不存在");
        }
        if ("INITIALIZING".equals(request.status())) {
            int changed = jdbc.update("UPDATE seckill_request SET status='PROCESSING',reservation_state='RESERVED' " +
                            "WHERE request_id=? AND status='INITIALIZING'",
                    requestId);
            if (changed == 1) {
                Map<String, Object> payload = Map.of("requestId", request.requestId(),
                        "activityId", request.activityId(), "userId", request.userId(),
                        "quantity", request.quantity());
                try {
                    jdbc.update("INSERT INTO outbox_event(id,dedupe_key,aggregate_type,aggregate_id,event_type,payload) " +
                                    "VALUES (?,?,?,?,?,?)",
                            UUID.randomUUID().toString(), "seckill-requested:" + requestId,
                            "SECKILL_REQUEST", requestId, "pharmacy.seckill.requested.v1",
                            objectMapper.writeValueAsString(payload));
                } catch (JsonProcessingException ex) {
                    throw new IllegalStateException("预约事件序列化失败", ex);
                }
            }
        }
        return getRequest(requestId, request.userId());
    }

    @Transactional
    public RequestView rejectInitializing(String requestId, String failureCode) {
        jdbc.update("UPDATE seckill_request SET status='FAILED',failure_code=? " +
                        "WHERE request_id=? AND status='INITIALIZING'",
                failureCode, requestId);
        return getRequest(requestId, null);
    }

    @Transactional(readOnly = true)
    public RequestView getRequest(String requestId, Long userId) {
        List<RequestView> rows = jdbc.query("SELECT request_id,activity_id,user_id,quantity,status,order_no,failure_code,expires_at " +
                        "FROM seckill_request WHERE request_id=?" + (userId == null ? "" : " AND user_id=?"),
                (rs, rowNum) -> new RequestView(rs.getString("request_id"), rs.getLong("activity_id"),
                        rs.getLong("user_id"), rs.getInt("quantity"), rs.getString("status"),
                        rs.getString("order_no"), rs.getString("failure_code"),
                        rs.getObject("expires_at", LocalDateTime.class)),
                userId == null ? new Object[]{requestId} : new Object[]{requestId, userId});
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("预约请求不存在");
        }
        return rows.get(0);
    }

    @Transactional(readOnly = true)
    public List<RequestCandidate> findRecoveryCandidates(int limit) {
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        return jdbc.query("SELECT request_id,activity_id,user_id,quantity,status,reservation_state,expires_at " +
                        "FROM seckill_request WHERE status='INITIALIZING' OR " +
                        "(status='PROCESSING' AND expires_at<=?) OR reservation_state='RELEASE_PENDING' " +
                        "ORDER BY expires_at LIMIT ?",
                (rs, rowNum) -> new RequestCandidate(rs.getString("request_id"), rs.getLong("activity_id"),
                        rs.getLong("user_id"), rs.getInt("quantity"), rs.getString("status"),
                        rs.getString("reservation_state"), rs.getObject("expires_at", LocalDateTime.class)),
                now, limit);
    }

    @Transactional
    public boolean expire(String requestId, String expectedStatus, boolean redisReservationExists) {
        String state = redisReservationExists ? "RELEASE_PENDING" : "NONE";
        int changed = jdbc.update("UPDATE seckill_request SET status='EXPIRED',reservation_state=?,failure_code='REQUEST_TIMEOUT' " +
                        "WHERE request_id=? AND status=? AND expires_at<=?",
                state, requestId, expectedStatus, LocalDateTime.now(BUSINESS_ZONE));
        return changed == 1;
    }

    @Transactional
    public void markReleased(String requestId) {
        jdbc.update("UPDATE seckill_request SET reservation_state='RELEASED' " +
                        "WHERE request_id=? AND status IN ('EXPIRED','FAILED') AND reservation_state='RELEASE_PENDING'",
                requestId);
    }

    private RequestRow findById(String requestId) {
        return jdbc.query("SELECT request_id,activity_id,user_id,quantity,status FROM seckill_request WHERE request_id=?",
                        (rs, rowNum) -> new RequestRow(rs.getString("request_id"), rs.getLong("activity_id"),
                                rs.getLong("user_id"), rs.getInt("quantity"), rs.getString("status")), requestId)
                .stream().findFirst().orElse(null);
    }

    private RequestRow findByIdForUpdate(String requestId) {
        return jdbc.query("SELECT request_id,activity_id,user_id,quantity,status FROM seckill_request WHERE request_id=? FOR UPDATE",
                        (rs, rowNum) -> new RequestRow(rs.getString("request_id"), rs.getLong("activity_id"),
                                rs.getLong("user_id"), rs.getInt("quantity"), rs.getString("status")), requestId)
                .stream().findFirst().orElse(null);
    }

    private void ensureSameRequest(RequestRow request, long activityId, long userId, int quantity) {
        if (request.activityId() != activityId || request.userId() != userId || request.quantity() != quantity) {
            throw new IllegalArgumentException("Idempotency-Key 已用于其他请求");
        }
    }

    public record Intent(String requestId, String status, boolean created) { }
    public record RequestView(String requestId, long activityId, long userId, int quantity,
                              String status, String orderNo, String failureCode, LocalDateTime expiresAt) { }
    public record RequestCandidate(String requestId, long activityId, long userId, int quantity,
                                   String status, String reservationState, LocalDateTime expiresAt) { }
    private record ActivityRow(String status, int perUserLimit, LocalDateTime startAt, LocalDateTime endAt) { }
    private record RequestRow(String requestId, long activityId, long userId, int quantity, String status) { }
}
