package com.hmdp.pharmacy.inventory;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.hmdp.pharmacy.inventory.FefoAllocator.*;
import static org.junit.jupiter.api.Assertions.*;

class FefoAllocatorTest {
    private final LocalDate today = LocalDate.of(2026, 11, 16);

    @Test
    void allocatesEarliestEligibleBatchFirstAndUsesIdForTies() {
        List<Allocation> result = plan(List.of(
                new Batch(5, today.plusDays(2), 8, false),
                new Batch(3, today, 2, false),
                new Batch(2, today, 3, false),
                new Batch(1, today.minusDays(1), 100, false),
                new Batch(4, today, 100, true)), 6, today);
        assertEquals(List.of(new Allocation(2, 3), new Allocation(3, 2), new Allocation(5, 1)), result);
    }

    @Test
    void rejectsInsufficientEligibleStockAndDuplicateIds() {
        Batch batch = new Batch(1, today, 2, false);
        assertThrows(InsufficientStockException.class, () -> plan(List.of(batch), 3, today));
        assertThrows(IllegalArgumentException.class, () -> plan(List.of(batch, batch), 3, today));
        assertThrows(IllegalArgumentException.class, () -> plan(List.of(batch), 0, today));
    }
}
