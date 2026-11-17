package com.hmdp.pharmacy.inventory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Computes a reservation plan; the caller must lock and update batches in one transaction. */
public final class FefoAllocator {
    private FefoAllocator() { }

    public record Batch(long id, LocalDate expiry, int available, boolean quarantined) {
        public Batch {
            if (id <= 0 || expiry == null || available < 0) {
                throw new IllegalArgumentException("Invalid inventory batch");
            }
        }
    }

    public record Allocation(long batchId, int quantity) { }

    /** Expiry date is inclusive; a batch expired before today is not saleable. */
    public static List<Allocation> plan(List<Batch> lockedBatches, int quantity, LocalDate today) {
        Objects.requireNonNull(lockedBatches, "lockedBatches");
        Objects.requireNonNull(today, "today");
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        if (lockedBatches.stream().map(Batch::id).distinct().count() != lockedBatches.size()) {
            throw new IllegalArgumentException("Duplicate batch ID");
        }
        List<Batch> eligible = lockedBatches.stream()
                .filter(batch -> !batch.quarantined() && !batch.expiry().isBefore(today) && batch.available() > 0)
                .sorted(Comparator.comparing(Batch::expiry).thenComparingLong(Batch::id))
                .toList();
        List<Allocation> allocations = new ArrayList<>();
        int remaining = quantity;
        for (Batch batch : eligible) {
            int selected = Math.min(remaining, batch.available());
            allocations.add(new Allocation(batch.id(), selected));
            remaining -= selected;
            if (remaining == 0) {
                return List.copyOf(allocations);
            }
        }
        throw new InsufficientStockException();
    }

    public static final class InsufficientStockException extends RuntimeException {
        public InsufficientStockException() {
            super("Insufficient saleable inventory");
        }
    }
}
