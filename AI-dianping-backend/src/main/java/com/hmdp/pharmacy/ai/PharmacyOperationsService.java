package com.hmdp.pharmacy.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class PharmacyOperationsService {
    private final JdbcTemplate jdbc;

    public PharmacyOperationsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long resolveStoreForUser(long userId) {
        List<Long> stores = jdbc.query("SELECT shop_id FROM tb_shop_owner WHERE user_id=? ORDER BY shop_id LIMIT 1",
                (rs, rowNum) -> rs.getLong(1), userId);
        if (stores.isEmpty()) throw new SecurityException("当前账号未绑定药房门店");
        return stores.get(0);
    }

    public List<MedicineRisk> risks(long storeId, int expiringWithinDays) {
        return jdbc.query("SELECT m.id,m.name,m.specification,m.sale_category, " +
                        "COALESCE(SUM(CASE WHEN b.expire_date>=CURRENT_DATE AND b.quarantined_qty=0 THEN b.available_qty ELSE 0 END),0) AS available_qty, " +
                        "COALESCE(SUM(CASE WHEN b.expire_date BETWEEN CURRENT_DATE AND DATE_ADD(CURRENT_DATE,INTERVAL ? DAY) " +
                        "AND b.quarantined_qty=0 THEN b.available_qty ELSE 0 END),0) AS near_expiry_qty, " +
                        "MIN(CASE WHEN b.available_qty>0 AND b.quarantined_qty=0 AND b.expire_date>=CURRENT_DATE THEN b.expire_date END) AS next_expiry, " +
                        "COALESCE((SELECT SUM(sm.quantity)/30.0 FROM stock_movement sm WHERE sm.store_id=m.store_id " +
                        "AND sm.medicine_id=m.id AND sm.movement_type='SALE_OUT' " +
                        "AND sm.created_at>=CURRENT_TIMESTAMP-INTERVAL 30 DAY),0) AS avg_daily_sales " +
                        "FROM pharmacy_medicine m LEFT JOIN inventory_batch b ON b.store_id=m.store_id AND b.medicine_id=m.id " +
                        "WHERE m.store_id=? AND m.enabled=TRUE GROUP BY m.id,m.name,m.specification,m.sale_category ORDER BY m.name",
                (rs, rowNum) -> {
                    int available = rs.getInt("available_qty");
                    int nearExpiry = rs.getInt("near_expiry_qty");
                    double averageDailySales = rs.getDouble("avg_daily_sales");
                    java.sql.Date expiry = rs.getDate("next_expiry");
                    LocalDate nextExpiry = expiry == null ? null : expiry.toLocalDate();
                    int days = nextExpiry == null ? -1 : (int) ChronoUnit.DAYS.between(
                            LocalDate.now(ZoneId.of("Asia/Shanghai")), nextExpiry);
                    int suggested = averageDailySales <= 0 ? 0
                            : Math.max(0, (int) Math.ceil(averageDailySales * 21) - available);
                    boolean lowStock = averageDailySales > 0 && available < Math.ceil(averageDailySales * 7);
                    return new MedicineRisk(rs.getLong("id"), rs.getString("name"), rs.getString("specification"),
                            rs.getString("sale_category"), available, averageDailySales, nearExpiry,
                            nextExpiry, days, lowStock, suggested);
                // Bind parameters in SQL placeholder order: expiry window first,
                // then the authorized store ID.
                }, expiringWithinDays, storeId);
    }

    public String deterministicSummary(long storeId) {
        return deterministicSummary(storeId, 30);
    }

    public String deterministicSummary(long storeId, int expiringWithinDays) {
        List<MedicineRisk> risks = risks(storeId, expiringWithinDays);
        if (risks.isEmpty()) return "当前门店还没有启用的药品档案。请先在门店工作台维护商品和批次。";
        StringBuilder summary = new StringBuilder("门店库存经营摘要（规则计算，不构成诊断或用药建议）：\n");
        for (MedicineRisk risk : risks) {
            summary.append("- ").append(risk.name()).append("：可用 ").append(risk.availableQty()).append(" 件");
            if (risk.nearExpiryQty() > 0) summary.append("；").append(expiringWithinDays).append(" 日内到期 ").append(risk.nearExpiryQty()).append(" 件");
            if (risk.lowStock()) summary.append("；低库存风险，按近 30 日销量建议补 ").append(risk.suggestedReplenishment()).append(" 件");
            else if (risk.averageDailySales() <= 0) summary.append("；暂无足够销售记录计算补货量");
            summary.append('\n');
        }
        return summary.toString().trim();
    }

    public String replenishmentSummary(long storeId, int targetDays) {
        List<MedicineRisk> risks = risks(storeId, 30);
        StringBuilder summary = new StringBuilder("补货估算（按近 30 日日均销量 × ")
                .append(targetDays).append(" 天目标库存计算，需店主审核）：\n");
        boolean found = false;
        for (MedicineRisk risk : risks) {
            found = true;
            if (risk.averageDailySales() <= 0) {
                summary.append("- ").append(risk.name())
                        .append("：暂无近 30 日销售记录，无法计算补货量。\n");
                continue;
            }
            int suggestedReplenishment = calculateReplenishment(risk.averageDailySales(), targetDays,
                    risk.availableQty());
            summary.append("- ").append(risk.name()).append("：建议补 ")
                    .append(suggestedReplenishment).append(" 件；当前可用 ")
                    .append(risk.availableQty()).append(" 件，近 30 日日均销量 ")
                    .append(String.format(java.util.Locale.ROOT, "%.2f", risk.averageDailySales()))
                    .append(" 件。\n");
        }
        if (!found) return "当前门店还没有启用的药品档案，暂不能计算补货数量。";
        return summary.toString().trim();
    }

    static int calculateReplenishment(double averageDailySales, int targetDays, int availableQty) {
        return Math.max(0, (int) Math.ceil(averageDailySales * targetDays) - availableQty);
    }

    public record MedicineRisk(long medicineId, String name, String specification, String category,
                               int availableQty, double averageDailySales, int nearExpiryQty,
                               LocalDate nextExpiry, int daysToExpiry, boolean lowStock,
                               int suggestedReplenishment) { }
}
