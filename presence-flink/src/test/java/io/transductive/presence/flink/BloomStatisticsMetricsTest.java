package io.transductive.presence.flink;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.apache.flink.metrics.Gauge;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.metrics.groups.UnregisteredMetricsGroup;
import org.junit.jupiter.api.Test;

class BloomStatisticsMetricsTest {
    @Test
    void recordsSamplingCostAndSchedulesFromCompletionAcrossNanoTimeWraparound() {
        var clock = new Clock();
        clock.nanos = Long.MAX_VALUE - 50;
        var metrics = new Capture();
        var sampler = new BloomStatisticsMetrics(metrics, () -> {
            clock.nanos += 7;
            return new BloomStatistics(1, 2, 256, 100, 0.25, 0.001);
        }, 100, clock);
        clock.nanos += 99;
        sampler.afterRecord();
        assertEquals(0, metrics.value("refreshCount"));
        clock.nanos++;
        sampler.afterRecord();
        assertEquals(1, metrics.value("refreshCount"));
        assertEquals(7, metrics.value("lastRefreshDurationNanos"));
        assertEquals(256, metrics.value("bitArrayBytes"));
        clock.nanos += 99;
        sampler.afterRecord();
        assertEquals(1, metrics.value("refreshCount"));
        clock.nanos++;
        sampler.afterRecord();
        assertEquals(2, metrics.value("refreshCount"));
        sampler.close();
        assertEquals(0, metrics.value("bitArrayBytes"));
    }

    @Test
    void failedSamplingKeepsThePreviousSampleAndTheDueDeadline() {
        var clock = new Clock();
        var fail = new AtomicBoolean();
        var metrics = new Capture();
        var sampler = new BloomStatisticsMetrics(metrics, () -> {
            if (fail.get()) throw new IllegalStateException("injected sample failure");
            return new BloomStatistics(1, 1, 1200, 100, 0.1, 0.001);
        }, 100, clock);
        sampler.refresh();
        fail.set(true);
        clock.nanos = 100;
        assertThrows(IllegalStateException.class, sampler::afterRecord);
        assertEquals(1, metrics.value("refreshCount"));
        assertEquals(100, metrics.value("estimatedElements"));
        fail.set(false);
        sampler.afterRecord();
        assertEquals(2, metrics.value("refreshCount"));
    }

    private static final class Clock implements LongSupplier {
        private long nanos;
        @Override public long getAsLong() { return nanos; }
    }

    private static final class Capture extends UnregisteredMetricsGroup {
        private final Map<String, Gauge<?>> gauges = new HashMap<>();
        @Override public MetricGroup addGroup(String name) { assertEquals("statistics", name); return this; }
        @Override public <T, G extends Gauge<T>> G gauge(String name, G gauge) { gauges.put(name, gauge); return gauge; }
        private double value(String name) { return ((Number) gauges.get(name).getValue()).doubleValue(); }
    }
}
