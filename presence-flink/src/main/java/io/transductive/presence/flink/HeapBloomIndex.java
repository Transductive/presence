package io.transductive.presence.flink;

import io.transductive.presence.bloom.BloomFilter;
import io.transductive.presence.bloom.ScalableBloomFilter;
import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Task-local storage. Only a copy of each group key and its Bloom are retained. */
final class HeapBloomIndex<K> {
    private final Map<K, Group> filters = new HashMap<>();
    private final BloomConfiguration configuration;
    private final UnaryOperator<K> copyKey;
    private volatile int groupCount;

    HeapBloomIndex(BloomConfiguration configuration, UnaryOperator<K> copyKey) {
        this.configuration = configuration;
        this.copyKey = copyKey;
    }

    boolean observe(K group, String id) {
        Group state = filters.get(group);
        if (state == null) {
            state = new Group(createMembership());
            filters.put(copyKey.apply(group), state);
            groupCount = filters.size();
        }
        boolean maybePresent = state.membership.mightContain(id);
        if (!maybePresent) state.membership.put(id);
        if (state.events != Long.MAX_VALUE) state.events++;
        return maybePresent;
    }

    long eventsSinceReset(K group) {
        Group state = filters.get(group);
        return state == null ? 0 : state.events;
    }

    boolean clear(K group) {
        boolean removed = filters.remove(group) != null;
        groupCount = filters.size();
        return removed;
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

    private static final class Group {
        private final Membership membership;
        private long events;

        private Group(Membership membership) { this.membership = membership; }
    }
}
