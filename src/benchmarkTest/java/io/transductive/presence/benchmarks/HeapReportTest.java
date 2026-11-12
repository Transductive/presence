package io.transductive.presence.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HeapReportTest {
    @Test
    void footprintsSeparateSharedKeysAndDeduplicateCombinedGraphs() {
        assertTrue(HeapReport.sizingMethod().contains("Instrumentation.getObjectSize"));
        var rows = HeapReport.measure(1_000, "ASCII");
        var bloom = rows.get(0);
        var set = rows.get(1);
        var map = rows.get(2);
        var combined = rows.get(3);
        assertTrue(bloom.incrementalBytes() > 0);
        assertTrue(bloom.incrementalBytes() < map.incrementalBytes());
        assertEquals(bloom.reachableBytes(), bloom.incrementalBytes());
        assertEquals(map.reachableBytes(), map.incrementalBytes() + map.keyPayloadBytes());
        assertEquals(set.reachableBytes(), set.incrementalBytes() + set.keyPayloadBytes());
        assertEquals(map.keyPayloadBytes(), bloom.keyPayloadBytes());
        assertTrue(combined.reachableBytes() <= bloom.reachableBytes() + map.reachableBytes());
        assertTrue(combined.incrementalBytes() > map.incrementalBytes());
        assertEquals(combined.reachableBytes(), combined.incrementalBytes() + combined.keyPayloadBytes());
    }
}
