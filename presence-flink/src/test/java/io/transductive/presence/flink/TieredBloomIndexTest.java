package io.transductive.presence.flink;

import static org.junit.jupiter.api.Assertions.*;

import io.transductive.presence.bloom.HashingStrategy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.flink.metrics.groups.UnregisteredMetricsGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TieredBloomIndexTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void coalescesWritesAndKeepsEveryWarmLookupOffTheStateBackend(boolean scalable) throws Exception {
        var store = new Store();
        var index = index(scalable, store, false);
        for (int i = 0; i < 500; i++) index.observe("a", "member-" + i);
        assertEquals(0, store.reads);
        assertEquals(0, store.writes.size());
        index.flush();
        int written = store.writes.size();
        assertEquals(store.layers.get("a").size(), written);
        index.flush();
        assertEquals(written, store.writes.size(), "unchanged checkpoint writes no layer bytes");
        index.observe("a", "member-1");
        index.flush();
        assertEquals(written, store.writes.size(), "duplicates only update the small manifest/counter");

        var restored = index(scalable, store, true);
        restored.prepare("a");
        assertEquals(501, restored.eventsSinceReset("a"));
        int reads = store.reads;
        assertEquals(1 + store.layers.get("a").size(), reads);
        for (int i = 0; i < 500; i++) assertTrue(restored.observe("a", "member-" + i));
        restored.flush();
        assertEquals(reads, store.reads, "warm queries and flushing never read state");
        assertEquals(written, store.writes.size());
    }

    @Test
    void newInsertWritesOnlyItsChangedLayerWithoutRewritingSealedLayers() throws Exception {
        var store = new Store();
        var index = index(true, store, false);
        for (int i = 0; i < 2_000; i++) index.observe("a", "member-" + i);
        index.flush();
        var before = new HashMap<>(store.layers.get("a"));
        assertTrue(before.size() > 2);
        store.writes.clear();
        int candidate = 0;
        while (index.observe("a", "fresh-" + candidate++)) { /* Skip possible false positives. */ }
        index.flush();
        assertEquals(1, store.writes.size());
        int changed = store.writes.getFirst();
        assertTrue(changed >= before.size() - 1);
        before.forEach((layer, bytes) -> {
            if (layer != changed) assertSame(bytes, store.layers.get("a").get(layer));
        });
        assertEquals(0, store.reads);
    }

    @Test
    void resetDeletesPersistedOldLayersAndDoesNotResetOtherKeys() throws Exception {
        var store = new Store();
        var index = index(true, store, false);
        for (int i = 0; i < 1_000; i++) index.observe("a", "old-" + i);
        index.observe("b", "keep");
        index.flush();
        assertTrue(store.layers.get("a").size() > 1);
        assertTrue(index.clear("a"));
        assertFalse(index.clear("a"));
        assertEquals(0, index.eventsSinceReset("a"));
        index.observe("a", "marker");
        index.flush();
        assertEquals(1, store.layers.get("a").size());
        var restored = index(true, store, true);
        assertTrue(restored.observe("a", "marker"));
        assertFalse(restored.observe("a", "old-0"));
        assertTrue(restored.observe("b", "keep"));
    }

    @Test
    void retriesAnIncompleteFlushIncludingLayerDeletionAndMetadata() throws Exception {
        var store = new Store();
        var index = index(true, store, false);
        for (int i = 0; i < 1_000; i++) index.observe("a", "old-" + i);
        index.flush();
        index.clear("a");
        index.observe("a", "new");
        store.failMetadata = true;
        assertThrows(IOException.class, index::flush);
        index.flush();
        assertEquals(1, store.layers.get("a").size());
        var restored = index(true, store, true);
        restored.prepare("a");
        assertEquals(1, restored.eventsSinceReset("a"));
        assertTrue(restored.observe("a", "new"));
    }

    @Test
    void rejectsChangedConfigurationAndMissingLayersRatherThanSilentlyForgettingMembership() throws Exception {
        var store = new Store();
        var index = index(true, store, false);
        index.observe("a", "member");
        index.flush();
        var changed = new BloomConfiguration(true, 2, 0.01, HashingStrategy.MURMUR3_128, BloomBackend.MANAGED_STATE);
        var incompatible = new TieredBloomIndex<>(changed, (String key) -> key, store, true, new UnregisteredMetricsGroup());
        assertThrows(IOException.class, () -> incompatible.prepare("a"));
        store.layers.get("a").clear();
        assertThrows(IOException.class, () -> index(true, store, true).prepare("a"));
    }

    private static TieredBloomIndex<String> index(boolean scalable, Store store, boolean restored) {
        return new TieredBloomIndex<>(new BloomConfiguration(scalable, 1, 0.01,
                HashingStrategy.MURMUR3_128, BloomBackend.MANAGED_STATE), key -> key, store,
                restored, new UnregisteredMetricsGroup());
    }

    private static final class Store implements TieredBloomIndex.Store<String> {
        private final Map<String, byte[]> metadata = new HashMap<>();
        private final Map<String, Map<Integer, byte[]>> layers = new HashMap<>();
        private final List<Integer> writes = new ArrayList<>();
        private String key;
        private int reads;
        private boolean failMetadata;

        @Override public void select(String value) { key = value; }
        @Override public byte[] metadata() { reads++; return metadata.get(key); }
        @Override public byte[] layer(int index) { reads++; return layers.get(key).get(index); }
        @Override public void metadata(byte[] bytes) throws IOException {
            if (failMetadata) { failMetadata = false; throw new IOException("injected write failure"); }
            metadata.put(key, bytes);
        }
        @Override public void layer(int index, byte[] bytes) {
            layers.computeIfAbsent(key, ignored -> new HashMap<>()).put(index, bytes);
            writes.add(index);
        }
        @Override public void clearLayers() { layers.remove(key); }
    }
}
