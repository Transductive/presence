package io.transductive.presence.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LargeAccuracyStatisticsTest {
    @Test
    void wilsonBoundsHandleZeroAllAndObservedOnePercent() {
        var zero = LargeAccuracyStatistics.wilson(0, 2_000_000);
        assertEquals(0, zero.lower(), 1e-15);
        assertTrue(zero.upper() > 0 && zero.upper() < 0.00001);
        var all = LargeAccuracyStatistics.wilson(2_000_000, 2_000_000);
        assertEquals(1, all.upper(), 1e-15);
        assertTrue(all.lower() < 1 && all.lower() > 0.99999);
        var onePercent = LargeAccuracyStatistics.wilson(20_000, 2_000_000);
        assertTrue(onePercent.lower() < 0.01 && onePercent.upper() > 0.01);
    }

    @Test
    void equivalenceRequiresTheWholeDifferenceIntervalInsideTheMargin() {
        assertTrue(LargeAccuracyStatistics.difference(20_000, 20_000, 2_000_000).equivalent());
        assertFalse(LargeAccuracyStatistics.difference(20_000, 21_000, 2_000_000).equivalent());
        assertFalse(LargeAccuracyStatistics.difference(20_000, 19_000, 2_000_000).equivalent());
        assertFalse(LargeAccuracyStatistics.difference(10, 10, 1_000).equivalent());
        var difference = LargeAccuracyStatistics.difference(20_000, 20_100, 2_000_000);
        assertEquals(0.00005, difference.estimate());
        assertTrue(difference.lower() < difference.estimate() && difference.upper() > difference.estimate());
    }

    @Test
    void rejectsInvalidBinomialCounts() {
        assertThrows(IllegalArgumentException.class, () -> LargeAccuracyStatistics.wilson(0, 0));
        assertThrows(IllegalArgumentException.class, () -> LargeAccuracyStatistics.wilson(-1, 100));
        assertThrows(IllegalArgumentException.class, () -> LargeAccuracyStatistics.wilson(101, 100));
    }
}
