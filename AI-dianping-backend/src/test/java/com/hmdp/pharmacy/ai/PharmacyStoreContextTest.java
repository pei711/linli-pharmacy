package com.hmdp.pharmacy.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PharmacyStoreContextTest {

    @Test
    void requiresTrustedStoreContext() {
        assertThrows(SecurityException.class, PharmacyStoreContext::currentStoreId);
    }

    @Test
    void restoresNestedStoreContextAndClearsItAfterUse() {
        try (PharmacyStoreContext.Scope outer = PharmacyStoreContext.bind(10L)) {
            assertEquals(10L, PharmacyStoreContext.currentStoreId());
            try (PharmacyStoreContext.Scope inner = PharmacyStoreContext.bind(20L)) {
                assertEquals(20L, PharmacyStoreContext.currentStoreId());
            }
            assertEquals(10L, PharmacyStoreContext.currentStoreId());
        }
        assertThrows(SecurityException.class, PharmacyStoreContext::currentStoreId);
    }

    @Test
    void recordsOnlyToolCallsWithinTheBoundRequestScope() {
        assertTrue(PharmacyStoreContext.currentToolCalls().isEmpty());
        try (PharmacyStoreContext.Scope ignored = PharmacyStoreContext.bind(42L)) {
            PharmacyStoreContext.recordToolCall("queryInventoryRisks", java.util.Map.of());
            assertEquals(java.util.List.of("queryInventoryRisks"), PharmacyStoreContext.currentToolCalls());
            assertEquals("queryInventoryRisks", PharmacyStoreContext.currentToolCallDetails().get(0).name());
            assertEquals(java.util.Map.of(), PharmacyStoreContext.currentToolCallDetails().get(0).parameters());
        }
        assertTrue(PharmacyStoreContext.currentToolCalls().isEmpty());
        assertTrue(PharmacyStoreContext.currentToolCallDetails().isEmpty());
    }
}
