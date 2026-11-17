package com.hmdp.pharmacy.ai.inbound;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.pharmacy.medicine.MedicineService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.client.MultipartBodyBuilder;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class InboundDocumentService {
    private static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
    private static final String SHANGHAI = "Asia/Shanghai";

    private final JdbcTemplate jdbc;
    private final MedicineService medicines;
    private final ObjectMapper mapper;
    private final RestClient ocrClient;

    public InboundDocumentService(JdbcTemplate jdbc, MedicineService medicines, ObjectMapper mapper,
                                  RestClient.Builder restClientBuilder,
                                  @Value("${app.pharmacy.ocr.service-url:http://localhost:8090}") String ocrServiceUrl) {
        this.jdbc = jdbc;
        this.medicines = medicines;
        this.mapper = mapper;
        this.ocrClient = restClientBuilder.baseUrl(ocrServiceUrl).build();
    }

    public Map<String, Object> recognize(long storeId, MultipartFile file) {
        medicines.requireOwner(storeId);
        if (file == null || file.isEmpty() || file.getSize() > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("请上传不超过 10 MiB 的单据图片或 PDF");
        }
        String contentType = file.getContentType();
        if (!List.of("image/jpeg", "image/png", "image/webp", "application/pdf").contains(contentType)) {
            throw new IllegalArgumentException("仅支持 JPEG、PNG、WebP 图片或 PDF");
        }
        final byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException failure) {
            throw new IllegalArgumentException("读取上传图片失败", failure);
        }
        String filename = safeFilename(file.getOriginalFilename());
        MultipartBodyBuilder multipart = new MultipartBodyBuilder();
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        };
        multipart.part("file", resource).contentType(MediaType.parseMediaType(contentType));
        final JsonNode ocrResult;
        try {
            ocrResult = ocrClient.post()
                    .uri("/v1/inbound-documents/recognize")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(multipart.build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException failure) {
            throw new OcrServiceUnavailableException("OCR 服务暂不可用，请稍后重试或手动登记批次；库存未变更", failure);
        }
        if (ocrResult == null || !ocrResult.path("candidate").isObject()) {
            throw new IllegalStateException("OCR 服务未返回有效候选数据");
        }
        String ocrText = readLines(ocrResult.path("lines"));
        JsonNode candidates = ocrResult.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            candidates = mapper.createArrayNode().add(ocrResult.path("candidate"));
        }
        List<JsonNode> enriched = new ArrayList<>();
        if (candidates.size() > 100) throw new IllegalStateException("OCR 候选商品超过 100 行，请拆分单据后重试");
        for (JsonNode item : candidates) {
            if (!item.isObject()) continue;
            com.fasterxml.jackson.databind.node.ObjectNode copy = item.deepCopy();
            copy.set("skuCandidates", mapper.valueToTree(findSkuCandidates(storeId,
                    item.path("medicine_name").asText(""))));
            enriched.add(copy);
        }
        if (enriched.isEmpty()) throw new IllegalStateException("OCR 服务未返回有效候选数据");
        JsonNode primary = enriched.get(0);
        long uploaderId = requireUserId();
        long documentId;
        try {
            String candidateJson = mapper.writeValueAsString(enriched);
            KeyHolder key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("INSERT INTO inbound_document " +
                                "(store_id,uploaded_by,source_filename,content_type,ocr_text,candidate_json,status) " +
                                "VALUES (?,?,?,?,?,CAST(? AS JSON),'PENDING_REVIEW')", Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, storeId);
                statement.setLong(2, uploaderId);
                statement.setString(3, filename);
                statement.setString(4, contentType);
                statement.setString(5, ocrText);
                statement.setString(6, candidateJson);
                return statement;
            }, key);
            documentId = Objects.requireNonNull(key.getKey(), "缺少入库单 ID").longValue();
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("无法保存 OCR 候选结果", failure);
        }
        return Map.of("documentId", documentId, "storeId", storeId, "candidate", primary,
                "candidates", enriched, "ocrText", ocrText,
                "skuCandidates", primary.path("skuCandidates"),
                "requiresManualReview", true, "status", "PENDING_REVIEW");
    }

    public Map<String, Object> getPending(long documentId) {
        InboundDocument document = loadDocument(documentId, false);
        medicines.requireOwner(document.storeId());
        if (!"PENDING_REVIEW".equals(document.status())) throw new IllegalStateException("该入库单已处理");
        try {
            JsonNode candidates = mapper.readTree(document.candidateJson());
            if (!candidates.isArray()) candidates = mapper.createArrayNode().add(candidates);
            return Map.of("documentId", document.id(), "storeId", document.storeId(),
                    "candidate", candidates.path(0), "candidates", candidates, "ocrText", document.ocrText(),
                    "status", document.status());
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("入库候选数据损坏", failure);
        }
    }

    public List<Map<String, Object>> findSkuCandidates(long storeId, String name) {
        medicines.requireOwner(storeId);
        String query = name == null ? "" : name.strip();
        if (query.isBlank()) return List.of();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,name,specification,sale_category FROM pharmacy_medicine " +
                "WHERE store_id=? AND enabled=TRUE ORDER BY id DESC LIMIT 500", storeId);
        String normalized = normalize(query);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String candidateName = Objects.toString(row.get("name"), "");
            String candidateSpec = Objects.toString(row.get("specification"), "");
            String target = normalize(candidateName);
            double score = similarity(normalized, target);
            if (target.equals(normalized)) score = 1.0;
            else if (!normalized.isBlank() && (target.contains(normalized) || normalized.contains(target))) {
                score = Math.max(score, 0.82);
            }
            result.add(Map.of("medicineId", row.get("id"), "name", candidateName,
                    "specification", candidateSpec, "saleCategory", Objects.toString(row.get("sale_category"), ""),
                    "matchScore", Math.round(score * 1000.0) / 1000.0));
        }
        result.sort(Comparator.comparingDouble(row -> -((Number) row.get("matchScore")).doubleValue()));
        return result.stream().limit(5).toList();
    }

    @Transactional
    public long confirm(long documentId, ReviewCommand command) {
        return confirmItems(documentId, List.of(command)).get(0);
    }

    @Transactional
    public List<Long> confirmItems(long documentId, List<ReviewCommand> commands) {
        InboundDocument document = loadDocument(documentId, true);
        medicines.requireOwner(document.storeId());
        if (!"PENDING_REVIEW".equals(document.status())) throw new IllegalStateException("该入库单已处理，不能重复入账");
        if (commands == null || commands.isEmpty() || commands.size() > 100) {
            throw new IllegalArgumentException("入库商品行数必须在 1 到 100 行之间");
        }
        long reviewerId = requireUserId();
        List<Long> batchIds = new ArrayList<>();
        for (ReviewCommand command : commands) {
            if (command == null) throw new IllegalArgumentException("入库商品行不能为空");
            validate(command);
            String medicineSpecification = medicineBelongsToStore(document.storeId(), command.medicineId());
            if (!normalize(command.specification()).equals(normalize(medicineSpecification))) {
                throw new IllegalArgumentException("入库规格与所选门店商品档案不一致，请选择正确 SKU 或修正商品档案");
            }
            long batchId = medicines.receiveBatch(document.storeId(), command.medicineId(), command.batchNo().strip(),
                    command.productionDate(), command.expireDate(), command.quantity());
            jdbc.update("INSERT INTO inbound_document_review " +
                            "(document_id,store_id,reviewer_id,medicine_id,batch_no,production_date,expire_date,quantity,purchase_unit_price,inventory_batch_id,correction_note) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    documentId, document.storeId(), reviewerId, command.medicineId(), command.batchNo().strip(),
                    command.productionDate(), command.expireDate(), command.quantity(), command.purchaseUnitPrice(), batchId,
                    command.correctionNote() == null ? "" : command.correctionNote().strip());
            batchIds.add(batchId);
        }
        int updated = jdbc.update("UPDATE inbound_document SET status='CONFIRMED',reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP,inventory_batch_id=? " +
                        "WHERE id=? AND store_id=? AND status='PENDING_REVIEW'",
                reviewerId, batchIds.get(0), documentId, document.storeId());
        if (updated != 1) throw new IllegalStateException("入库单状态已变化，请刷新后重试");
        return List.copyOf(batchIds);
    }

    @Transactional
    public void reject(long documentId, String reason) {
        InboundDocument document = loadDocument(documentId, true);
        medicines.requireOwner(document.storeId());
        if (!"PENDING_REVIEW".equals(document.status())) throw new IllegalStateException("该入库单已处理，不能重复审核");
        if (reason == null || reason.isBlank() || reason.strip().length() > 1000) {
            throw new IllegalArgumentException("请填写不超过 1,000 个字符的拒绝原因");
        }
        int updated = jdbc.update("UPDATE inbound_document SET status='REJECTED',reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP,review_note=? " +
                        "WHERE id=? AND store_id=? AND status='PENDING_REVIEW'",
                requireUserId(), reason.strip(), documentId, document.storeId());
        if (updated != 1) throw new IllegalStateException("入库单状态已变化，请刷新后重试");
    }

    private InboundDocument loadDocument(long id, boolean lock) {
        String sql = "SELECT id,store_id,status,candidate_json,ocr_text FROM inbound_document WHERE id=?" +
                (lock ? " FOR UPDATE" : "");
        List<InboundDocument> rows = jdbc.query(sql, DOCUMENT_MAPPER, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("入库单不存在");
        return rows.get(0);
    }

    private String medicineBelongsToStore(long storeId, long medicineId) {
        List<String> specifications = jdbc.query("SELECT specification FROM pharmacy_medicine " +
                        "WHERE id=? AND store_id=? AND enabled=TRUE FOR UPDATE",
                (rs, rowNum) -> rs.getString("specification"), medicineId, storeId);
        if (specifications.size() != 1) throw new IllegalArgumentException("药品不存在或不属于当前门店");
        return specifications.get(0);
    }

    static void validate(ReviewCommand command) {
        if (command.medicineId() <= 0 || command.quantity() <= 0 || command.specification() == null ||
                command.specification().isBlank() || command.specification().length() > 160 ||
                command.purchaseUnitPrice() == null || command.purchaseUnitPrice().signum() < 0 ||
                command.purchaseUnitPrice().scale() > 2 || command.purchaseUnitPrice().compareTo(new BigDecimal("100000000.00")) > 0 ||
                command.batchNo() == null ||
                command.batchNo().isBlank() || command.batchNo().length() > 100 ||
                command.productionDate() == null || command.expireDate() == null ||
                command.expireDate().isBefore(command.productionDate()) ||
                command.expireDate().isBefore(LocalDate.now(ZoneId.of(SHANGHAI))) ||
                (command.correctionNote() != null && command.correctionNote().length() > 1000)) {
            throw new IllegalArgumentException("复核字段无效，或有效期已过");
        }
    }

    private static long requireUserId() {
        if (UserHolder.getUser() == null || UserHolder.getUser().getId() == null) throw new SecurityException("请先登录");
        return UserHolder.getUser().getId();
    }

    private static String safeFilename(String value) {
        String filename = value == null ? "supplier-document" : value.replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        filename = filename.replaceAll("[^\\p{L}\\p{N}._ -]", "_").strip();
        return filename.isBlank() ? "supplier-document" : filename.substring(0, Math.min(filename.length(), 255));
    }

    private static String readLines(JsonNode lines) {
        StringBuilder result = new StringBuilder();
        if (lines.isArray()) {
            for (JsonNode line : lines) {
                String text = line.path("text").asText("");
                if (text.isBlank()) continue;
                if (result.length() + text.length() > 100_000) break;
                if (!result.isEmpty()) result.append('\n');
                result.append(text);
            }
        }
        return result.toString();
    }

    static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]", "");
    }

    private static double similarity(String left, String right) {
        if (left.isBlank() || right.isBlank()) return 0;
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1));
            }
            previous = current;
        }
        return 1.0 - (double) previous[right.length()] / Math.max(left.length(), right.length());
    }

    private static final RowMapper<InboundDocument> DOCUMENT_MAPPER = (rs, rowNum) ->
            new InboundDocument(rs.getLong("id"), rs.getLong("store_id"), rs.getString("status"),
                    rs.getString("candidate_json"), rs.getString("ocr_text"));

    public record ReviewCommand(long medicineId, String specification, String batchNo, LocalDate productionDate,
                                LocalDate expireDate, int quantity, BigDecimal purchaseUnitPrice,
                                String correctionNote) { }
    private record InboundDocument(long id, long storeId, String status, String candidateJson, String ocrText) { }
}
