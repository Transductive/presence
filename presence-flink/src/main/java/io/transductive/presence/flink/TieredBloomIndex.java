package io.transductive.presence.flink;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.apache.flink.metrics.Counter;
import org.apache.flink.metrics.MetricGroup;

/** Heap primary with checkpoint-coordinated, coalesced writes of changed layers. */
final class TieredBloomIndex<K> implements BloomIndex<K> {
    private final Map<K, Entry<K>> groups = new HashMap<>();
    private final Set<K> dirtyKeys = new HashSet<>();
    private final BloomConfiguration configuration;
    private final UnaryOperator<K> copyKey;
    private final Store<K> store;
    private final boolean restored;
    private final Counter layerReads;
    private final Counter layerWrites;
    private final Counter bytesWritten;
    private volatile int groupCount;

    TieredBloomIndex(BloomConfiguration configuration, UnaryOperator<K> copyKey, Store<K> store,
            boolean restored, MetricGroup metrics) {
        this.configuration = configuration;
        this.copyKey = copyKey;
        this.store = store;
        this.restored = restored;
        layerReads = metrics.counter("stateLayerReads");
        layerWrites = metrics.counter("stateLayerWrites");
        bytesWritten = metrics.counter("stateLayerBytesWritten");
    }

    @Override
    public void prepare(K key) throws Exception {
        if (groups.containsKey(key)) return;
        byte[] bytes = null;
        if (restored) {
            store.select(key);
            bytes = store.metadata();
        }
        BloomGroup group;
        if (bytes == null) {
            group = BloomGroup.create(configuration);
        } else {
            var metadata = BloomGroup.readMetadata(bytes, configuration);
            var layers = new ArrayList<byte[]>(metadata.layerCount());
            for (int i = 0; i < metadata.layerCount(); i++) {
                byte[] layer = store.layer(i);
                layerReads.inc();
                if (layer == null) throw new IOException("Missing Presence Bloom layer " + i);
                layers.add(layer);
            }
            group = BloomGroup.restore(configuration, metadata, layers);
        }
        K retainedKey = copyKey.apply(key);
        var entry = new Entry<>(retainedKey, group);
        if (bytes == null) entry.dirtyLayers.set(0);
        groups.put(retainedKey, entry);
        groupCount = groups.size();
    }

    @Override
    public boolean observe(K key, String id) throws Exception {
        prepare(key);
        var entry = groups.get(key);
        boolean present = entry.group.observe(id);
        if (!present) entry.dirtyLayers.set(entry.group.layerCount() - 1);
        // Even a duplicate changes the per-key event counter used by reset policies.
        dirtyKeys.add(entry.key);
        return present;
    }

    @Override
    public long eventsSinceReset(K key) {
        var entry = groups.get(key);
        return entry == null ? 0 : entry.group.events();
    }

    @Override
    public boolean clear(K key) {
        var entry = groups.get(key);
        if (entry == null || entry.group.events() == 0) return false;
        entry.group = BloomGroup.create(configuration);
        entry.clearStoredLayers = true;
        entry.dirtyLayers.clear();
        entry.dirtyLayers.set(0);
        dirtyKeys.add(entry.key);
        return true;
    }

    /** Must run on the operator thread before the backend captures its checkpoint. */
    void flush() throws Exception {
        var iterator = dirtyKeys.iterator();
        while (iterator.hasNext()) {
            var entry = groups.get(iterator.next());
            store.select(entry.key);
            if (entry.clearStoredLayers) store.clearLayers();
            for (int i = entry.dirtyLayers.nextSetBit(0); i >= 0; i = entry.dirtyLayers.nextSetBit(i + 1)) {
                byte[] snapshot = entry.group.snapshotLayer(i);
                store.layer(i, snapshot);
                layerWrites.inc();
                bytesWritten.inc(snapshot.length);
            }
            store.metadata(entry.group.snapshotMetadata(configuration));
            // Failed writes retain the dirty set for a retry. Successful backend writes remain
            // available to later checkpoints even if this checkpoint is subsequently aborted.
            entry.dirtyLayers.clear();
            entry.clearStoredLayers = false;
            iterator.remove();
        }
    }

    @Override public int groupCount() { return groupCount; }

    @Override
    public BloomStatistics statistics() {
        // Untouched restored keys remain in managed state. Observability must not
        // materialize them, select another state key, or disturb the dirty set.
        var accumulator = new BloomStatistics.Accumulator();
        for (Entry<K> entry : groups.values()) accumulator.add(entry.group);
        return accumulator.snapshot();
    }

    @Override
    public void clear() {
        groups.clear();
        dirtyKeys.clear();
        groupCount = 0;
    }

    interface Store<K> {
        void select(K key);
        byte[] metadata() throws Exception;
        byte[] layer(int index) throws Exception;
        void metadata(byte[] bytes) throws Exception;
        void layer(int index, byte[] bytes) throws Exception;
        void clearLayers() throws Exception;
    }

    private static final class Entry<K> {
        private final K key;
        private BloomGroup group;
        private final BitSet dirtyLayers = new BitSet();
        private boolean clearStoredLayers;

        private Entry(K key, BloomGroup group) {
            this.key = key;
            this.group = group;
        }
    }
}
