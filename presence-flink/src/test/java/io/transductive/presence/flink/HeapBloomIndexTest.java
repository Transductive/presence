package io.transductive.presence.flink;

import static org.junit.jupiter.api.Assertions.*;

import io.transductive.presence.bloom.HashingStrategy;
import org.apache.flink.api.java.tuple.Tuple1;
import org.junit.jupiter.api.Test;

class HeapBloomIndexTest {
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
