package io.transductive.presence.otel;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.BatchCallback;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.transductive.presence.bloom.BloomFilter;
import io.transductive.presence.bloom.HashingStrategy;
import io.transductive.presence.bloom.ScalableBloomFilter;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * An optional metrics wrapper around a fixed-size or scalable Bloom filter.
 *
 * <p>Calls delegate to the supplied filter and record successful operations.
 * A successful {@link #put} counts one call, including repeated values; it does
 * not count distinct insertions. Lookup outcomes preserve Bloom semantics:
 * "possibly present" is not an exact hit or a known duplicate. Exceptions from
 * the filter propagate without incrementing operation counters.
 *
 * <p>Use a bounded, application-defined name that distinguishes each live
 * wrapper in the same {@link Meter}. Never use event IDs, tenant IDs, or random
 * instance IDs as names. Metric names and attributes are documented in the
 * module README. Operations through the original filter bypass the counters.
 *
 * <p>Like the underlying filters, operations, wrapping, and closing are not
 * thread-safe; serialize them on the owning thread or with external locking.
 * Metric collection may run concurrently: callbacks read only an immutable,
 * safely published footprint and never access the underlying filter.
 *
 * <p>Statistics are sampled at wrapping and after the first successful operation
 * whose refresh interval has elapsed (30 seconds by default). Sampling runs
 * synchronously on the caller's thread before that operation returns. An idle
 * filter starts no timer or thread. Use {@link #refreshStatistics()} to sample
 * immediately, including before the application's final telemetry export.
 *
 * <p>The application owns the meter, SDK, readers, and exporters. Close this
 * wrapper when retiring it to unregister its callback. Closing never clears the
 * underlying filter or closes application telemetry. Subsequent operations on
 * this wrapper throw {@link IllegalStateException}.
 *
 * @param <T> value type accepted by the underlying filter
 */
public final class OtelBloomFilter<T> implements AutoCloseable {
    /** Default minimum interval between automatic statistics refreshes. */
    public static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofSeconds(30);

    private final Consumer<T> insert;
    private final Predicate<T> lookup;
    private final IntSupplier layerCount;
    private final LongSupplier arrayBytes;
    private final Supplier<Statistics> statistics;
    private final LongSupplier nanoTime;
    private final long refreshIntervalNanos;
    private final LongCounter puts;
    private final LongCounter lookups;
    private final Attributes attributes;
    private final Attributes possiblyPresent;
    private final Attributes definitelyAbsent;
    private final PublishedFootprint published;
    private final BatchCallback callback;
    private int knownLayerCount;
    private long lastRefreshNanos;
    private boolean closed;

    private OtelBloomFilter(Consumer<T> insert, Predicate<T> lookup, IntSupplier layerCount,
            LongSupplier arrayBytes, Supplier<Statistics> statistics, HashingStrategy strategy,
            String type, Meter meter, String name, Duration refreshInterval, LongSupplier nanoTime) {
        Objects.requireNonNull(meter, "meter");
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || name.length() > 128) {
            throw new IllegalArgumentException("name must be nonblank and at most 128 characters");
        }
        this.refreshIntervalNanos = refreshIntervalNanos(refreshInterval);
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.insert = insert;
        this.lookup = lookup;
        this.layerCount = layerCount;
        this.arrayBytes = arrayBytes;
        this.statistics = statistics;
        attributes = Attributes.builder()
                .put("presence.filter.name", name)
                .put("presence.filter.type", type)
                .put("presence.hashing.strategy", strategy.name())
                .build();
        possiblyPresent = attributes.toBuilder().put("presence.lookup.outcome", "possibly_present").build();
        definitelyAbsent = attributes.toBuilder().put("presence.lookup.outcome", "definitely_absent").build();
        puts = meter.counterBuilder("presence.bloom.puts")
                .setDescription("Successful put calls, including repeated values.")
                .setUnit("{operation}").build();
        lookups = meter.counterBuilder("presence.bloom.lookups")
                .setDescription("Successful lookup calls classified by approximate membership result.")
                .setUnit("{operation}").build();
        knownLayerCount = layerCount.getAsInt();
        published = new PublishedFootprint(new Footprint(knownLayerCount, arrayBytes.getAsLong()), statistics.get());
        lastRefreshNanos = nanoTime.getAsLong();
        callback = registerFootprint(meter, attributes, published);
    }

    /**
     * Wraps an existing fixed-size filter without copying or clearing it.
     * Counters start at zero; the initial footprint includes its current storage.
     * Uses {@link #DEFAULT_REFRESH_INTERVAL} for automatic statistics refreshes.
     *
     * @param filter filter to delegate to, non-null
     * @param meter application-supplied meter, non-null; a no-op meter is supported
     * @param name bounded logical name, nonblank and at most 128 characters
     * @param <T> value type
     * @return an explicitly closeable metrics wrapper
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is blank or too long
     */
    public static <T> OtelBloomFilter<T> wrap(BloomFilter<T> filter, Meter meter, String name) {
        return wrap(filter, meter, name, DEFAULT_REFRESH_INTERVAL);
    }

    /**
     * Wraps a fixed-size filter with a configurable statistics refresh interval.
     * The first successful put or lookup after the interval elapses refreshes
     * statistics synchronously before returning. Idle filters are not sampled.
     *
     * @param filter filter to delegate to, non-null
     * @param meter application-supplied meter, non-null
     * @param name bounded logical name, nonblank and at most 128 characters
     * @param refreshInterval positive interval representable in long nanoseconds
     * @param <T> value type
     * @return an explicitly closeable metrics wrapper
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name or interval is invalid
     */
    public static <T> OtelBloomFilter<T> wrap(BloomFilter<T> filter, Meter meter, String name,
            Duration refreshInterval) {
        return wrap(filter, meter, name, refreshInterval, System::nanoTime);
    }

    // Clock injection keeps timing tests deterministic without sleeps or a public clock API.
    static <T> OtelBloomFilter<T> wrap(BloomFilter<T> filter, Meter meter, String name,
            Duration refreshInterval, LongSupplier nanoTime) {
        Objects.requireNonNull(filter, "filter");
        // BitSet storage rounds the logical bit count to complete 64-bit words.
        long bytes = ((long) filter.bitSize() + Long.SIZE - 1) / Long.SIZE * Long.BYTES;
        return new OtelBloomFilter<>(filter::put, filter::mightContain, () -> 1, () -> bytes,
                () -> new Statistics(filter.estimatedElementCount(), filter.fillRatio(), filter.estimatedFalsePositiveProbability()),
                filter.hashingStrategy(), "fixed", meter, name, refreshInterval, nanoTime);
    }

    /**
     * Wraps an existing scalable filter without copying or clearing it.
     * Footprint metrics update automatically when an insertion grows the filter.
     * Counters describe calls through this wrapper, including any future repeats.
     * Uses {@link #DEFAULT_REFRESH_INTERVAL} for automatic statistics refreshes.
     *
     * @param filter filter to delegate to, non-null
     * @param meter application-supplied meter, non-null; a no-op meter is supported
     * @param name bounded logical name, nonblank and at most 128 characters
     * @param <T> value type
     * @return an explicitly closeable metrics wrapper
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name is blank or too long
     */
    public static <T> OtelBloomFilter<T> wrap(ScalableBloomFilter<T> filter, Meter meter, String name) {
        return wrap(filter, meter, name, DEFAULT_REFRESH_INTERVAL);
    }

    /**
     * Wraps a scalable filter with a configurable statistics refresh interval.
     * Layer-growth footprint updates remain immediate. Element, fill, and
     * probability estimates refresh after the first successful put or lookup
     * following the interval, synchronously on its caller's thread.
     *
     * @param filter filter to delegate to, non-null
     * @param meter application-supplied meter, non-null
     * @param name bounded logical name, nonblank and at most 128 characters
     * @param refreshInterval positive interval representable in long nanoseconds
     * @param <T> value type
     * @return an explicitly closeable metrics wrapper
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if the name or interval is invalid
     */
    public static <T> OtelBloomFilter<T> wrap(ScalableBloomFilter<T> filter, Meter meter, String name,
            Duration refreshInterval) {
        return wrap(filter, meter, name, refreshInterval, System::nanoTime);
    }

    static <T> OtelBloomFilter<T> wrap(ScalableBloomFilter<T> filter, Meter meter, String name,
            Duration refreshInterval, LongSupplier nanoTime) {
        Objects.requireNonNull(filter, "filter");
        return new OtelBloomFilter<>(filter::put, filter::mightContain, filter::layerCount,
                () -> filter.bitSize() / Byte.SIZE,
                () -> new Statistics(filter.estimatedElementCount(), filter.activeLayerFillRatio(), filter.estimatedFalsePositiveProbability()),
                filter.hashingStrategy(), "scalable", meter, name, refreshInterval, nanoTime);
    }

    /**
     * Delegates insertion, counts a successful call, and publishes any layer growth.
     * No lookup is added to infer whether the insertion changed membership.
     * Refreshes statistics before returning when the interval has elapsed.
     *
     * @param value value to insert, subject to the underlying encoder's contract
     * @throws IllegalStateException if this wrapper is closed
     * @throws NullPointerException if the value or its encoded bytes are null
     */
    public void put(T value) {
        requireOpen();
        insert.accept(value);
        puts.add(1, attributes);
        if (layerCount.getAsInt() != knownLayerCount) refreshFootprint();
        refreshStatisticsIfDue();
    }

    /**
     * Delegates a lookup and counts its approximate outcome without changing membership.
     * Refreshes statistics before returning when the interval has elapsed.
     *
     * @param value value to query, subject to the underlying encoder's contract
     * @return false for definitely absent, true for possibly present
     * @throws IllegalStateException if this wrapper is closed
     * @throws NullPointerException if the value or its encoded bytes are null
     */
    public boolean mightContain(T value) {
        requireOpen();
        boolean present = lookup.test(value);
        lookups.add(1, present ? possiblyPresent : definitelyAbsent);
        refreshStatisticsIfDue();
        return present;
    }

    /**
     * Refreshes footprint metrics after deliberate direct access to the original filter.
     * Call only on its owning thread or under the same external lock. Does not
     * reconstruct operation counts. Normal mutations through this wrapper do not
     * require this call. Work is proportional to layer count, not array size.
     * Does not resample statistics or reset their automatic refresh interval.
     *
     * @throws IllegalStateException if this wrapper is closed
     */
    public void refreshFootprint() {
        requireOpen();
        int currentLayers = layerCount.getAsInt();
        published.value = new Footprint(currentLayers, arrayBytes.getAsLong());
        knownLayerCount = currentLayers;
    }

    /**
     * Immediately samples element count, fill, and false-positive estimates on
     * the owning thread, and refreshes footprint. Restarts the automatic refresh
     * interval from completion. Call before a final SDK export when the newest
     * statistics are needed; SDK collection itself reads the published sample.
     * Fixed filters scan their bits; scalable filters inspect slice counters.
     * Never call this concurrently with filter operations. No background sampler
     * is installed by this library.
     *
     * <p>This is synchronous work on the membership hot path when triggered by
     * an operation. A longer interval reduces sampling frequency, not the delay
     * of the individual operation that samples. Fixed statistics currently make
     * three cardinality scans. Alternative sampling strategies remain future
     * work; moving this method to a reporter thread would violate filter ownership.
     * @throws IllegalStateException if this wrapper is closed
     */
    public void refreshStatistics() {
        requireOpen();
        // PERF: synchronous by design. Fixed statistics currently traverse the bits
        // three times. Revisit single-pass/incremental sampling separately, with the
        // raw-vs-wrapper benchmarks as a baseline. Do not offload live-filter reads
        // to an SDK callback or background executor: these filters are not thread-safe.
        published.statistics = statistics.get();
        refreshFootprint();
        lastRefreshNanos = nanoTime.getAsLong();
    }

    /**
     * Unregisters the footprint callback and retires this wrapper; idempotent.
     * Previously recorded counters remain subject to the application's SDK
     * aggregation and export lifecycle. The underlying filter remains usable.
     */
    @Override
    public void close() {
        if (!closed) {
            callback.close();
            closed = true;
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("OtelBloomFilter is closed");
    }

    private void refreshStatisticsIfDue() {
        // Subtraction is safe across nanoTime's signed wraparound for supported intervals.
        if (nanoTime.getAsLong() - lastRefreshNanos >= refreshIntervalNanos) refreshStatistics();
    }

    private static long refreshIntervalNanos(Duration interval) {
        Objects.requireNonNull(interval, "refreshInterval");
        try {
            long nanos = interval.toNanos();
            if (nanos > 0) return nanos;
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("refreshInterval must fit in long nanoseconds", error);
        }
        throw new IllegalArgumentException("refreshInterval must be positive");
    }

    private static BatchCallback registerFootprint(Meter meter, Attributes attributes,
            PublishedFootprint published) {
        var layers = meter.upDownCounterBuilder("presence.bloom.layers")
                .setDescription("Current allocated Bloom layers, including an empty initial layer.")
                .setUnit("{layer}").buildObserver();
        var bytes = meter.upDownCounterBuilder("presence.bloom.bit_array.bytes")
                .setDescription("Backing bit-array payload bytes; excludes object headers and auxiliary storage.")
                .setUnit("By").buildObserver();
        var elements = meter.gaugeBuilder("presence.bloom.elements.estimated")
                .setDescription("Occupancy estimate of represented distinct values; not exact input cardinality.")
                .setUnit("{element}").buildObserver();
        var fill = meter.gaugeBuilder("presence.bloom.active_layer.fill_ratio")
                .setDescription("Fixed bit fill or scalable active layer maximum slice fill; rollover at 0.5 for scalable.")
                .setUnit("1").buildObserver();
        var probability = meter.gaugeBuilder("presence.bloom.false_positive_probability.estimated")
                .setDescription("Occupancy-based false-positive probability estimate, not an observed rate.")
                .setUnit("1").buildObserver();
        // Capture only the published holder: the SDK must never retain the filter via this callback.
        return meter.batchCallback(() -> {
            Footprint footprint = published.value;
            layers.record(footprint.layers(), attributes);
            bytes.record(footprint.bytes(), attributes);
            Statistics sample = published.statistics;
            elements.record(sample.elements(), attributes);
            fill.record(sample.fill(), attributes);
            probability.record(sample.probability(), attributes);
        }, layers, bytes, elements, fill, probability);
    }

    private record Footprint(int layers, long bytes) {}
    private record Statistics(double elements, double fill, double probability) {}

    private static final class PublishedFootprint {
        private volatile Footprint value;
        private volatile Statistics statistics;

        private PublishedFootprint(Footprint value, Statistics statistics) {
            this.value = value;
            this.statistics = statistics;
        }
    }
}
