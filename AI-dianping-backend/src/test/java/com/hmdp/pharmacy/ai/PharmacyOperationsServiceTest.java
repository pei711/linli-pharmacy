package com.hmdp.pharmacy.ai;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class PharmacyOperationsServiceTest {
    @Test
    void inventoryRisksBindsExpiryWindowBeforeStoreId() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doAnswer(invocation -> {
            // JdbcTemplate has fixed-arity overloads for one and two bind
            // parameters. The compiler selects query(sql, mapper, arg1,
            // arg2), so Mockito exposes the binds as separate invocation
            // arguments rather than a nested Object[] varargs value.
            assertEquals(4, invocation.getArguments().length);
            assertEquals(30, invocation.<Integer>getArgument(2).intValue());
            assertEquals(990010L, invocation.<Long>getArgument(3).longValue());
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(), any());

        new PharmacyOperationsService(jdbc).risks(990010L, 30);
    }

    @Test
    void replenishmentUsesRequestedTargetDaysAndNeverSuggestsNegativeQuantity() {
        assertEquals(13, PharmacyOperationsService.calculateReplenishment(1.0, 21, 8));
        assertEquals(22, PharmacyOperationsService.calculateReplenishment(1.0, 30, 8));
        assertEquals(0, PharmacyOperationsService.calculateReplenishment(0.2, 7, 8));
    }
}
