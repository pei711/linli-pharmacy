package com.hmdp.pharmacy.medicine;

import com.hmdp.pharmacy.catalog.PharmacyCatalogService;
import com.hmdp.mapper.ShopOwnerMapper;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.UserHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class MedicineService {
    private final JdbcTemplate jdbc;
    private final ShopOwnerMapper owners;
    private final CacheClient cache;

    public MedicineService(JdbcTemplate jdbc, ShopOwnerMapper owners, CacheClient cache) {
        this.jdbc = jdbc;
        this.owners = owners;
        this.cache = cache;
    }

    public void requireOwner(long storeId) {
        if (storeId <= 0 || UserHolder.getUser() == null || UserHolder.getUser().getId() == null
                || !Objects.equals(owners.findOwnerId(storeId), UserHolder.getUser().getId())) {
            throw new SecurityException("无权管理该门店");
        }
    }

    @Transactional
    public long create(long storeId, String name, String specification, String category) {
        requireOwner(storeId);
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO pharmacy_medicine(store_id,name,specification,sale_category) VALUES (?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, storeId);
            statement.setString(2, name.trim());
            statement.setString(3, specification.trim());
            statement.setString(4, category);
            return statement;
        }, key);
        long medicineId = Objects.requireNonNull(key.getKey(), "Missing medicine ID").longValue();
        cache.deleteAfterCommit(PharmacyCatalogService.medicinesCacheKey(storeId));
        return medicineId;
    }

    public List<Map<String, Object>> inventory(long storeId) {
        requireOwner(storeId);
        return jdbc.queryForList("SELECT b.id, b.medicine_id, m.name, m.specification, b.batch_no, " +
                        "b.production_date, b.expire_date, b.on_hand_qty, b.available_qty, b.activity_reserved_qty, " +
                        "b.order_reserved_qty, b.quarantined_qty, b.version FROM inventory_batch b " +
                        "JOIN pharmacy_medicine m ON m.id=b.medicine_id AND m.store_id=b.store_id " +
                        "WHERE b.store_id=? ORDER BY b.expire_date,b.id", storeId);
    }

    @Transactional
    public long receiveBatch(long storeId, long medicineId, String batchNo,
                            LocalDate productionDate, LocalDate expireDate, int quantity) {
        requireOwner(storeId);
        if (storeId <= 0 || medicineId <= 0 || quantity <= 0 || productionDate == null || expireDate == null
                || expireDate.isBefore(productionDate) || expireDate.isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai")))) {
            throw new IllegalArgumentException("批次日期或入库数量无效");
        }
        if (batchNo == null || batchNo.isBlank() || batchNo.length() > 100) {
            throw new IllegalArgumentException("批次号无效");
        }
        Integer medicineCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pharmacy_medicine WHERE id=? AND store_id=? AND enabled=TRUE FOR UPDATE",
                Integer.class, medicineId, storeId);
        if (medicineCount == null || medicineCount != 1) {
            throw new IllegalArgumentException("商品不存在或不属于该门店");
        }
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO inventory_batch(store_id,medicine_id,batch_no,production_date,expire_date,on_hand_qty,available_qty) VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, storeId);
            statement.setLong(2, medicineId);
            statement.setString(3, batchNo.trim());
            statement.setObject(4, productionDate);
            statement.setObject(5, expireDate);
            statement.setInt(6, quantity);
            statement.setInt(7, quantity);
            return statement;
        }, key);
        long batchId = Objects.requireNonNull(key.getKey(), "Missing batch ID").longValue();
        String reference = "INBOUND:" + UUID.randomUUID();
        jdbc.update("INSERT INTO stock_movement(store_id,medicine_id,batch_id,movement_type,quantity,reference_id) VALUES (?,?,?,'INBOUND',?,?)",
                storeId, medicineId, batchId, quantity, reference);
        cache.deleteAfterCommit(PharmacyCatalogService.medicinesCacheKey(storeId));
        return batchId;
    }

    public List<Map<String, Object>> fefo(long storeId, long medicineId) {
        requireOwner(storeId);
        return jdbc.queryForList("SELECT id AS batch_id,batch_no,expire_date,available_qty FROM inventory_batch " +
                        "WHERE store_id=? AND medicine_id=? AND available_qty>0 AND quarantined_qty=0 AND expire_date>=CURRENT_DATE " +
                        "ORDER BY expire_date,id", storeId, medicineId);
    }
}
