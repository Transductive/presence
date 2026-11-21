package io.transductive.presence.flink;

import static org.junit.jupiter.api.Assertions.*;

import io.transductive.presence.bloom.HashingStrategy;
import org.apache.flink.api.java.tuple.Tuple1;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HeapBloomIndexTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void removesOneGroupsFilterAndCounterWithoutForgettingOtherGroups(boolean scalable) {
        var index = new HeapBloomIndex<String>(
                new BloomConfiguration(scalable, 1, 0.01, HashingStrategy.MURMUR3_128, BloomBackend.HEAP),
                key -> key);
        index.observe("keep", "id");
        for (int i = 0; i < 5_000; i++) index.observe("clear", "id-" + i);
        assertEquals(5_000, index.eventsSinceReset("clear"));
        assertEquals(2, index.groupCount());
        assertTrue(index.clear("clear"));
        assertEquals(1, index.groupCount(), "the entire group entry is removed, not just its bits zeroed");
        assertEquals(0, index.eventsSinceReset("clear"));
        assertFalse(index.clear("clear"), "clearing an absent group is harmless");
        assertTrue(index.observe("keep", "id"));
        assertEquals(2, index.eventsSinceReset("keep"), "duplicate events count too");
        assertFalse(index.observe("clear", "id-0"), "the old filter's history is gone");
        assertEquals(1, index.eventsSinceReset("clear"));
        index.clear();
        assertEquals(0, index.eventsSinceReset("keep"));
        assertEquals(0, index.groupCount());
    }

    @Test
    void copiesMutableGroupKeysAndReleasesGroupsOnClear() {
        var index = new HeapBloomIndex<Tuple1<String>>(
                new BloomConfiguration(true, 1_000, 0.01, HashingStrategy.RAPIDHASH_V3, BloomBackend.HEAP),
                key -> Tuple1.of(key.f0));
        var reusedKey = Tuple1.of("a");
        assertFalse(index.observe(reusedKey, "event"));
        reusedKey.f0 = "b";
        assertFalse(index.observe(reusedKey, "event"));
        assertTrue(index.observe(Tuple1.of("a"), "event"));
        assertEquals(2, index.groupCount());
        index.clear();
        assertEquals(0, index.groupCount());
        assertFalse(index.observe(Tuple1.of("a"), "event"));
    }
}
