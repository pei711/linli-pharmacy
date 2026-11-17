package com.hmdp.config.BloomFilter;

import com.google.common.hash.BloomFilter;
import org.springframework.stereotype.Component;

/** Keeps Bloom-filter misses fail-open until a complete database snapshot has loaded. */
@Component
public class BloomFilterState {
    private volatile boolean ready;

    public boolean mayContain(BloomFilter<Long> filter, Long id) {
        return !ready || filter.mightContain(id);
    }

    public void markReady() {
        ready = true;
    }

    public boolean isReady() {
        return ready;
    }
}
