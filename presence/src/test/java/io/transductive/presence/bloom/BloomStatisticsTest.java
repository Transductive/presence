package io.transductive.presence.bloom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BloomStatisticsTest {
    @ParameterizedTest
    @EnumSource(HashingStrategy.class)
    void fixedEstimatesReflectOccupancyAndIgnoreRepeatedPuts(HashingStrategy strategy) {
        var filter = BloomFilter.forStrings(1_000, 0.01, strategy);
        assertEquals(0, filter.estimatedElementCount());
        assertEquals(0, filter.fillRatio());
        assertEquals(0, filter.estimatedFalsePositiveProbability());
        for (int i = 0; i < 1_000; i++) filter.put("member-" + i);
        double estimate = filter.estimatedElementCount();
        assertEquals(1_000, estimate, 100, "Uniform-hash occupancy estimate should stay within 10% for this fixture");
        assertTrue(filter.estimatedFalsePositiveProbability() > 0.005);
        assertTrue(filter.estimatedFalsePositiveProbability() < 0.02);
        for (int i = 0; i < 1_000; i++) filter.put("member-" + i);
        assertEquals(estimate, filter.estimatedElementCount());
        var restored = BloomFilter.restoreStrings(filter.snapshot());
        assertEquals(estimate, restored.estimatedElementCount());
        assertEquals(filter.fillRatio(), restored.fillRatio());
    }

    @Test
    void fullySaturatedFixedFilterHasNoFiniteCardinalityEstimate() {
        var filter = new BloomFilter<String>(1, 1, String::getBytes);
        filter.put("one");
        assertEquals(1, filter.fillRatio());
        assertEquals(1, filter.estimatedFalsePositiveProbability());
        assertEquals(Double.POSITIVE_INFINITY, filter.estimatedElementCount());
    }

    @ParameterizedTest
    @EnumSource(HashingStrategy.class)
    void scalableEstimatesFollowGrowthWithoutCountingDuplicates(HashingStrategy strategy) {
        var filter = ScalableBloomFilter.forStrings(1_000, 0.01, strategy);
        assertEquals(0, filter.estimatedElementCount());
        assertEquals(0, filter.activeLayerFillRatio());
        for (int i = 0; i < 10_000; i++) filter.put("member-" + i);
        double estimate = filter.estimatedElementCount();
        assertTrue(filter.layerCount() > 1);
        assertEquals(10_000, estimate, 1_000, "Allowance covers occupancy estimation and skipped possible matches");
        assertTrue(filter.activeLayerFillRatio() > 0);
        assertTrue(filter.activeLayerFillRatio() <= 0.5);
        for (var layer : filter.layers()) assertTrue(layer.maximumSliceFillRatio() <= 0.5);
        for (int i = 0; i < 10_000; i++) filter.put("member-" + i);
        assertEquals(estimate, filter.estimatedElementCount());
        var restored = ScalableBloomFilter.restoreStrings(filter.snapshotMetadata(),
                filter.layers().stream().map(ScalableBloomFilter.Layer::snapshot).toList());
        assertEquals(estimate, restored.estimatedElementCount());
        assertEquals(filter.activeLayerFillRatio(), restored.activeLayerFillRatio());
    }
}
