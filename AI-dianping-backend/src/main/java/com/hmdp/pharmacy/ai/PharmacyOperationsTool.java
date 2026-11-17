package com.hmdp.pharmacy.ai;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import org.springframework.stereotype.Component;

@Component("pharmacyOperationsTool")
public class PharmacyOperationsTool {
    private final PharmacyOperationsService operations;

    public PharmacyOperationsTool(PharmacyOperationsService operations) {
        this.operations = operations;
    }

    @Tool("当用户询问药品库存、商品清单、没有销售记录的商品、批次、临期、低库存或库存风险时必须调用此只读工具。用户提及其他门店或要求导出数据时，也只查询当前登录账号已授权门店，再拒绝披露其他门店数据。查询当前已授权药房的批次库存、指定天数内临期风险和低库存风险；未指定临期范围时使用 30 天。此工具不计算补货量、不执行采购，也不提供诊断或用药建议。")
    public String queryInventoryRisks(@P("expiringWithinDays：要检查的临期天数，范围 1 到 365；用户未指定时传 30。不得根据用户提到的其他门店切换授权门店") int expiringWithinDays) {
        requireDays(expiringWithinDays, "expiringWithinDays");
        long storeId = PharmacyStoreContext.currentStoreId();
        PharmacyStoreContext.recordToolCall("queryInventoryRisks", java.util.Map.of("expiringWithinDays", expiringWithinDays));
        return operations.deterministicSummary(storeId, expiringWithinDays);
    }

    @Tool("当用户询问近期销量、补货商品或补货数量时必须调用此只读工具，包括用户要求给没有销售记录的商品编造补货数、把库存补到指定盒数，或同时要求直接下采购单的情况。只按当前已授权门店近 30 日的真实出库流水估算，不执行采购。targetDays 是预测覆盖天数，不是用户要求补到的盒数；用户没有明确要求未来 N 天或下月预测时传 21 天。销量记录不足时明确返回无法计算。")
    public String calculateReplenishment(@P("targetDays：预测覆盖天数，范围 1 到 180；明确要求未来 N 天时传 N，下月按 30；未指定预测周期时传 21。它不是用户要求补到的库存盒数") int targetDays) {
        if (targetDays < 1 || targetDays > 180) {
            throw new IllegalArgumentException("targetDays 必须在 1 到 180 之间");
        }
        long storeId = PharmacyStoreContext.currentStoreId();
        PharmacyStoreContext.recordToolCall("calculateReplenishment", java.util.Map.of("targetDays", targetDays));
        return operations.replenishmentSummary(storeId, targetDays);
    }

    private void requireDays(int days, String field) {
        if (days < 1 || days > 365) {
            throw new IllegalArgumentException(field + " 必须在 1 到 365 之间");
        }
    }
}
