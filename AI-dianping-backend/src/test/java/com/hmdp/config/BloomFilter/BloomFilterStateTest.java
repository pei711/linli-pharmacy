package com.hmdp.config.BloomFilter;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BloomFilterStateTest {
    @Test
    void bypassesNegativeBloomResultsUntilDatabaseSnapshotIsReady() {
        BloomFilter<Long> filter = BloomFilter.create(Funnels.longFunnel(), 100, 0.001);
        BloomFilterState state = new BloomFilterState();

        assertTrue(state.mayContain(filter, 42L));
        state.markReady();
        assertFalse(state.mayContain(filter, 42L));
    }
}
