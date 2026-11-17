package com.hmdp.pharmacy.activity;

import com.hmdp.dto.Result;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@ConditionalOnProperty(name = "app.pharmacy.seckill.enabled", havingValue = "true")
@RequestMapping("/api/v1")
public class PharmacySeckillReservationController {
    private final ActivityReservationService reservations;

    public PharmacySeckillReservationController(ActivityReservationService reservations) {
        this.reservations = reservations;
    }

    public record ReserveRequest(@Positive Integer quantity) { }

    @PostMapping("/activities/{activityId}/reserve")
    public Result reserve(@PathVariable @Positive long activityId,
                          @Valid @RequestBody(required = false) ReserveRequest body,
                          @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                          HttpServletRequest request) {
        String ip = request.getHeader("X-Real-IP");
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        Integer quantity = body == null ? null : body.quantity();
        return Result.ok(reservations.reserve(activityId, quantity, idempotencyKey, ip));
    }

    @GetMapping("/seckill/requests/{requestId}")
    public Result status(@PathVariable String requestId) {
        return Result.ok(reservations.getRequest(requestId));
    }
}
