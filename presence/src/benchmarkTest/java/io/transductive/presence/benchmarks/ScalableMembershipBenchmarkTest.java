package io.transductive.presence.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.transductive.presence.bloom.HashingStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScalableMembershipBenchmarkTest {
    @ParameterizedTest
    @ValueSource(strings = {"CACHED", "FRESH"})
    void bothStructuresFindEveryHitAcrossGrowingLayers(String queryMode) {
        var benchmark = new ScalableMembershipBenchmark();
        benchmark.N = 10_000;
        benchmark.hitPercent = 100;
        benchmark.queryMode = queryMode;
        benchmark.setup();
        for (int i = 0; i < BenchmarkData.queryCount(benchmark.N, benchmark.workingSet); i++) {
            assertTrue(benchmark.fixedMurmur());
            assertTrue(benchmark.fixedRapidhash());
            assertTrue(benchmark.scalableMurmur());
            assertTrue(benchmark.scalableRapidhash());
        }
    }

    @Test
    void fixedSizingTracksFinalNWhileScalableStartsAtTheSameInitialEstimate() {
        for (var strategy : new HashingStrategy[]{HashingStrategy.MURMUR3_128, HashingStrategy.RAPIDHASH_V3}) {
            var baseline = ScalableMembershipBenchmark.filters(BenchmarkData.keys(1_000), strategy);
            var grown = ScalableMembershipBenchmark.filters(BenchmarkData.keys(10_000), strategy);
            assertEquals(1, baseline.scalable().layerCount());
            assertTrue(grown.scalable().layerCount() > 1);
            assertTrue(grown.fixed().bitSize() > baseline.fixed().bitSize());
            assertEquals(0.01, grown.scalable().targetFalsePositiveProbability());
            assertEquals(strategy, grown.fixed().hashingStrategy());
            assertEquals(strategy, grown.scalable().hashingStrategy());
        }
    }
}
