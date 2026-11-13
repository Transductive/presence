package io.transductive.presence.flink;

import io.transductive.presence.bloom.BloomFilter;
import io.transductive.presence.bloom.ScalableBloomFilter;
import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Task-local storage. Only a copy of each group key and its Bloom are retained. */
final class HeapBloomIndex<K> {
    private final Map<K, Membership> filters = new HashMap<>();
    private final BloomConfiguration configuration;
    private final UnaryOperator<K> copyKey;
    private volatile int groupCount;

    HeapBloomIndex(BloomConfiguration configuration, UnaryOperator<K> copyKey) {
        this.configuration = configuration;
        this.copyKey = copyKey;
    }

    boolean observe(K group, String id) {
        Membership membership = filters.get(group);
        if (membership == null) {
            membership = createMembership();
            filters.put(copyKey.apply(group), membership);
            groupCount = filters.size();
        }
        if (membership.mightContain(id)) return true;
        membership.put(id);
        return false;
    }

    int groupCount() { return groupCount; }

    void clear() {
        filters.clear();
        groupCount = 0;
    }

    private Membership createMembership() {
        if (configuration.scalable()) {
            var filter = ScalableBloomFilter.forStrings(configuration.capacity(),
                    configuration.probability(), configuration.hashingStrategy());
            return new Membership() {
                @Override public boolean mightContain(String value) { return filter.mightContain(value); }
                @Override public void put(String value) { filter.put(value); }
            };
        }
        var filter = BloomFilter.forStrings(configuration.capacity(),
                configuration.probability(), configuration.hashingStrategy());
        return new Membership() {
            @Override public boolean mightContain(String value) { return filter.mightContain(value); }
            @Override public void put(String value) { filter.put(value); }
        };
    }

    private interface Membership {
        boolean mightContain(String value);
        void put(String value);
    }
}
