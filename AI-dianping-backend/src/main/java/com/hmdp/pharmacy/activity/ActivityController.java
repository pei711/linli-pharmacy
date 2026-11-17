package com.hmdp.pharmacy.activity;

import com.hmdp.dto.Result;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@Validated
@ConditionalOnProperty(name = "app.pharmacy.seckill.enabled", havingValue = "true")
@RequestMapping("/api/v1/admin/activities")
public class ActivityController {
    private final ActivityService service;

    public ActivityController(ActivityService service) {
        this.service = service;
    }

    public record CreateActivity(@NotNull @Positive Long storeId,
                                 @NotNull @Positive Long medicineId,
                                 @NotBlank @Size(max = 160) String title,
                                 @NotNull @Positive Integer quantity,
                                 @NotNull @Positive Integer perUserLimit,
                                 @NotNull @Future LocalDateTime startAt,
                                 @NotNull LocalDateTime endAt) { }

    @PostMapping
    public Result create(@Valid @RequestBody CreateActivity request) {
        return Result.ok(service.create(request.storeId(), request.medicineId(), request.title(),
                request.quantity(), request.perUserLimit(), request.startAt(), request.endAt()));
    }

    @PostMapping("/{activityId}/publish")
    public Result publish(@PathVariable @Positive long activityId) {
        service.publish(activityId);
        return Result.ok("活动已发布，库存已按 FEFO 预留");
    }
}
