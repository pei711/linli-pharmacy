package com.hmdp.pharmacy.medicine;

import com.hmdp.dto.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.FutureOrPresent;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

@RestController
@Validated
@RequestMapping("/api/v1/admin")
public class MedicineController {
    private final MedicineService service;

    public MedicineController(MedicineService service) { this.service = service; }

    public record CreateMedicine(@NotNull @Positive Long storeId,
                                 @NotBlank @Size(max = 160) String name,
                                 @NotBlank @Size(max = 160) String specification,
                                 @NotBlank @Pattern(regexp = "OTC|MEDICAL_SUPPLY|PRESCRIPTION") String saleCategory) { }

    public record ReceiveBatch(@NotNull @Positive Long storeId,
                              @NotNull @Positive Long medicineId,
                              @NotBlank @Size(max = 100) String batchNo,
                              @NotNull LocalDate productionDate,
                              @NotNull @FutureOrPresent LocalDate expireDate,
                              @NotNull @Positive Integer quantity) { }

    @PostMapping("/medicines")
    public Result create(@Valid @RequestBody CreateMedicine request) {
        return Result.ok(service.create(request.storeId(), request.name(), request.specification(), request.saleCategory()));
    }

    @GetMapping("/inventory")
    public Result inventory(@RequestParam @Positive long storeId) {
        return Result.ok(service.inventory(storeId));
    }

    @PostMapping("/inventory/batches")
    public Result receiveBatch(@Valid @RequestBody ReceiveBatch request) {
        return Result.ok(service.receiveBatch(request.storeId(), request.medicineId(), request.batchNo(),
                request.productionDate(), request.expireDate(), request.quantity()));
    }

    @GetMapping("/inventory/fefo")
    public Result fefo(@RequestParam @Positive long storeId, @RequestParam @Positive long medicineId) {
        return Result.ok(service.fefo(storeId, medicineId));
    }
}
