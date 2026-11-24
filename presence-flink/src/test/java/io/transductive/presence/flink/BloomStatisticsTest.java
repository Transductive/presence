package io.transductive.presence.flink;

import static org.junit.jupiter.api.Assertions.*;

import io.transductive.presence.bloom.BloomFilter;
import io.transductive.presence.bloom.HashingStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BloomStatisticsTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void addsPerKeyMembershipsAndBytesButUsesMaximumRiskRatherThanSummingProbabilities(boolean scalable) {
        var index = index(scalable, 1_000, 0.01);
        assertEquals(BloomStatistics.EMPTY, index.statistics());
        for (int i = 0; i < 2_000; i++) index.observe("a", "id-" + i);
        var single = index.statistics();
        for (int i = 0; i < 2_000; i++) index.observe("b", "id-" + i);
        var both = index.statistics();
        assertEquals(2, both.groups());
        assertEquals(single.layers() * 2, both.layers());
        assertEquals(single.bitArrayBytes() * 2, both.bitArrayBytes());
        assertEquals(single.estimatedElements() * 2, both.estimatedElements());
        assertEquals(single.maximumActiveLayerFillRatio(), both.maximumActiveLayerFillRatio());
        assertEquals(single.maximumEstimatedFalsePositiveProbability(), both.maximumEstimatedFalsePositiveProbability());
        if (scalable) assertTrue(single.layers() > 1);
        for (int i = 0; i < 2_000; i++) assertTrue(index.observe("a", "id-" + i));
        assertEquals(both, index.statistics(), "sampling and duplicates do not mutate membership");
        index.clear("a");
        assertEquals(single, index.statistics());
        index.clear();
        assertEquals(BloomStatistics.EMPTY, index.statistics());
    }

    @Test
    void fixedByteCountsIncludeStorageWordRounding() {
        var index = index(false, 1_000, 0.01);
        index.observe("a", "id");
        var core = BloomFilter.forStrings(1_000, 0.01);
        assertEquals(((long) core.bitSize() + 63) / 64 * 8, index.statistics().bitArrayBytes());
    }

    @Test
    void aSaturatedFixedFilterReportsUnboundedEstimateWithoutLosingOtherStatistics() {
        var index = index(false, 1, 0.99);
        index.observe("a", "id");
        var sample = index.statistics();
        assertEquals(Double.POSITIVE_INFINITY, sample.estimatedElements());
        assertEquals(1, sample.maximumActiveLayerFillRatio());
        assertEquals(1, sample.maximumEstimatedFalsePositiveProbability());
        assertEquals(8, sample.bitArrayBytes());
    }

    private static HeapBloomIndex<String> index(boolean scalable, long capacity, double probability) {
        return new HeapBloomIndex<>(new BloomConfiguration(scalable, capacity, probability,
                HashingStrategy.MURMUR3_128, BloomBackend.HEAP), key -> key);
    }
}
