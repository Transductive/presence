package io.transductive.presence.flink;

import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Task-local storage. Only a copy of each group key and its Bloom are retained. */
final class HeapBloomIndex<K> implements BloomIndex<K> {
    private final Map<K, BloomGroup> filters = new HashMap<>();
    private final BloomConfiguration configuration;
    private final UnaryOperator<K> copyKey;
    private volatile int groupCount;

    HeapBloomIndex(BloomConfiguration configuration, UnaryOperator<K> copyKey) {
        this.configuration = configuration;
        this.copyKey = copyKey;
    }

    @Override
    public boolean observe(K group, String id) {
        BloomGroup state = filters.get(group);
        if (state == null) {
            state = BloomGroup.create(configuration);
            filters.put(copyKey.apply(group), state);
            groupCount = filters.size();
        }
        return state.observe(id);
    }

    @Override
    public long eventsSinceReset(K group) {
        BloomGroup state = filters.get(group);
        return state == null ? 0 : state.events();
    }

    @Override
    public boolean clear(K group) {
        boolean removed = filters.remove(group) != null;
        groupCount = filters.size();
        return removed;
    }

    @Override public int groupCount() { return groupCount; }

    @Override
    public void clear() {
        filters.clear();
        groupCount = 0;
    }

}
