package io.transductive.presence.flink;

import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.apache.flink.metrics.MetricGroup;

/** Owner-thread sampling; reporter threads see only a volatile immutable sample. */
final class BloomStatisticsMetrics {
    private final Supplier<BloomStatistics> sample;
    private final LongSupplier nanoTime;
    private final long intervalNanos;
    private final Published published = new Published();
    private long lastRefreshNanos;

    BloomStatisticsMetrics(MetricGroup metrics, Supplier<BloomStatistics> sample,
            long intervalNanos, LongSupplier nanoTime) {
        if (intervalNanos <= 0) throw new IllegalArgumentException("metrics interval must be positive");
        this.sample = sample;
        this.intervalNanos = intervalNanos;
        this.nanoTime = nanoTime;
        lastRefreshNanos = nanoTime.getAsLong();
        var group = metrics.addGroup("statistics");
        // Capture only the publication holder, never this sampler or its live index.
        var values = published;
        group.gauge("bloomGroups", () -> values.current.statistics().groups());
        group.gauge("layers", () -> values.current.statistics().layers());
        group.gauge("bitArrayBytes", () -> values.current.statistics().bitArrayBytes());
        group.gauge("estimatedElements", () -> values.current.statistics().estimatedElements());
        group.gauge("maximumActiveLayerFillRatio", () -> values.current.statistics().maximumActiveLayerFillRatio());
        group.gauge("maximumEstimatedFalsePositiveProbability",
                () -> values.current.statistics().maximumEstimatedFalsePositiveProbability());
        group.gauge("refreshCount", () -> values.current.refreshCount());
        group.gauge("lastRefreshDurationNanos", () -> values.current.durationNanos());
    }

    void afterRecord() {
        if (nanoTime.getAsLong() - lastRefreshNanos >= intervalNanos) refresh();
    }

    void refresh() {
        // PERF: this stops record processing while all resident groups are sampled.
        // Fixed filters currently make three bit-array scans each; scalable filters
        // inspect slice counters. Interval tuning amortizes work, not the stall of
        // this record. Revisit bounded/incremental sampling later. Reporter threads
        // must never traverse the index or touch keyed state to avoid the pause.
        long started = nanoTime.getAsLong();
        BloomStatistics statistics = sample.get();
        long finished = nanoTime.getAsLong();
        long previous = published.current.refreshCount();
        published.current = new Sample(statistics, previous == Long.MAX_VALUE ? previous : previous + 1,
                finished - started);
        lastRefreshNanos = nanoTime.getAsLong();
    }

    void close() { published.current = Sample.EMPTY; }

    private record Sample(BloomStatistics statistics, long refreshCount, long durationNanos) {
        private static final Sample EMPTY = new Sample(BloomStatistics.EMPTY, 0, 0);
    }

    private static final class Published {
        private volatile Sample current = Sample.EMPTY;
    }
}
