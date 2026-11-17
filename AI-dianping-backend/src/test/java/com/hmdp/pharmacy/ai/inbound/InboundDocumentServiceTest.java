package com.hmdp.pharmacy.ai.inbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.pharmacy.medicine.MedicineService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InboundDocumentServiceTest {

    @Test
    void validatesPurchasePriceAndSkuSpecificationBeforeInboundConfirmation() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        InboundDocumentService.ReviewCommand valid = new InboundDocumentService.ReviewCommand(
                31L, "100mg × 60片", "BATCH2601", today.minusDays(1), today.plusDays(365), 10,
                new BigDecimal("12.50"), "");
        InboundDocumentService.validate(valid);
        assertEquals(InboundDocumentService.normalize("100mg × 60片"),
                InboundDocumentService.normalize("100mg 60片"));

        assertThrows(IllegalArgumentException.class, () -> InboundDocumentService.validate(
                new InboundDocumentService.ReviewCommand(31L, "100mg × 60片", "BATCH2601",
                        today.minusDays(1), today.plusDays(365), 10, new BigDecimal("-0.01"), "")));
        assertThrows(IllegalArgumentException.class, () -> InboundDocumentService.validate(
                new InboundDocumentService.ReviewCommand(31L, "", "BATCH2601",
                        today.minusDays(1), today.plusDays(365), 10, new BigDecimal("12.50"), "")));
    }

    @Test
    void proposesExactSkuBeforeFuzzyMatchesWithoutChoosingForTheReviewer() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        MedicineService medicines = mock(MedicineService.class);
        when(jdbc.queryForList(anyString(), eq(900010L))).thenReturn(List.of(
                Map.of("id", 31L, "name", "维生素 C 咀嚼片", "specification", "100mg×60片", "sale_category", "OTC"),
                Map.of("id", 32L, "name", "维生素C片", "specification", "100mg×60片", "sale_category", "OTC")
        ));
        InboundDocumentService service = new InboundDocumentService(jdbc, medicines, new ObjectMapper(),
                RestClient.builder(), "http://localhost:8090");

        List<Map<String, Object>> result = service.findSkuCandidates(900010L, "维生素C片");

        assertEquals(32L, ((Number) result.get(0).get("medicineId")).longValue());
        assertEquals(1.0, ((Number) result.get(0).get("matchScore")).doubleValue());
        assertEquals(2, result.size());
        verify(medicines).requireOwner(900010L);
    }
}
