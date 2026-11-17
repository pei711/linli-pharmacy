package com.hmdp.pharmacy.catalog;

import com.hmdp.dto.Result;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1")
public class PharmacyCatalogController {
    private final PharmacyCatalogService catalog;

    public PharmacyCatalogController(PharmacyCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/stores/nearby")
    public Result nearby(@RequestParam @DecimalMin("-180") @DecimalMax("180") double longitude,
                         @RequestParam @DecimalMin("-90") @DecimalMax("90") double latitude,
                         @RequestParam(defaultValue = "5") double radiusKm,
                         @RequestParam(defaultValue = "20") int limit) {
        return Result.ok(catalog.nearby(longitude, latitude, radiusKm, limit));
    }

    @GetMapping("/stores/{storeId}/medicines")
    public Result medicines(@PathVariable @Positive long storeId) {
        return Result.ok(catalog.medicines(storeId));
    }

    @GetMapping("/stores/{storeId}/activities")
    public Result activities(@PathVariable @Positive long storeId) {
        return Result.ok(catalog.activities(storeId));
    }

    @GetMapping("/activities/{activityId}")
    public Result activity(@PathVariable @Positive long activityId) {
        return Result.ok(catalog.activity(activityId));
    }
}
