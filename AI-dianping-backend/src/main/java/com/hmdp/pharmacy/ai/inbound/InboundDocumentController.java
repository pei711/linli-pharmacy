package com.hmdp.pharmacy.ai.inbound;

import com.hmdp.dto.Result;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestBody;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;

@RestController
@Validated
@RequestMapping("/api/v1/admin/inbound-documents")
public class InboundDocumentController {
    private final InboundDocumentService service;

    public InboundDocumentController(InboundDocumentService service) {
        this.service = service;
    }

    public record ConfirmRequest(@NotNull @Positive Long medicineId,
                                 @NotBlank @Size(max = 160) String specification,
                                 @NotBlank @Size(max = 100) String batchNo,
                                 @NotNull LocalDate productionDate,
                                 @NotNull LocalDate expireDate,
                                 @NotNull @Positive Integer quantity,
                                 @NotNull @DecimalMin("0.00") BigDecimal purchaseUnitPrice,
                                 @Size(max = 1000) String correctionNote) { }

    public record RejectRequest(@NotBlank @Size(max = 1000) String reason) { }
    public record ConfirmItemsRequest(@NotEmpty @Size(max = 100) List<@NotNull @Valid ConfirmRequest> items) { }

    @PostMapping("/recognize")
    public Result recognize(@RequestParam @Positive long storeId, @RequestPart("file") MultipartFile file) {
        return Result.ok(service.recognize(storeId, file));
    }

    @GetMapping("/{documentId}")
    public Result detail(@PathVariable @Positive long documentId) {
        return Result.ok(service.getPending(documentId));
    }

    @PostMapping("/{documentId}/confirm")
    public Result confirm(@PathVariable @Positive long documentId, @Valid @RequestBody ConfirmRequest request) {
        long batchId = service.confirm(documentId, new InboundDocumentService.ReviewCommand(
                request.medicineId(), request.specification(), request.batchNo(), request.productionDate(),
                request.expireDate(), request.quantity(), request.purchaseUnitPrice(), request.correctionNote()));
        return Result.ok(batchId);
    }

    @PostMapping("/{documentId}/confirm-items")
    public Result confirmItems(@PathVariable @Positive long documentId,
                               @Valid @RequestBody ConfirmItemsRequest request) {
        List<Long> batchIds = service.confirmItems(documentId, request.items().stream()
                .map(item -> new InboundDocumentService.ReviewCommand(item.medicineId(), item.specification(),
                        item.batchNo(), item.productionDate(), item.expireDate(), item.quantity(),
                        item.purchaseUnitPrice(), item.correctionNote()))
                .toList());
        return Result.ok(batchIds);
    }

    @PostMapping("/{documentId}/reject")
    public Result reject(@PathVariable @Positive long documentId, @Valid @RequestBody RejectRequest request) {
        service.reject(documentId, request.reason());
        return Result.ok("识别草稿已拒绝，未修改库存");
    }
}
