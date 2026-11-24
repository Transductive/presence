package io.transductive.presence.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.transductive.presence.bloom.BloomFilter;
import io.transductive.presence.bloom.HashingStrategy;
import io.transductive.presence.bloom.ScalableBloomFilter;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/** Owner-thread instrumentation cost; no exporter or background collection. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class OtelOverheadBenchmark {
    static final int QUERY_COUNT = 8_192;
    static final double TARGET = 0.01;
    static final HashingStrategy STRATEGY = HashingStrategy.MURMUR3_128;

    public enum Kind { FIXED, SCALABLE }
    public enum Operation { HIT, MISS, REPEATED_PUT }

    @Param({"1000", "100000", "1000000"})
    public int N;
    @Param({"FIXED", "SCALABLE"})
    public Kind kind;
    @Param({"HIT", "MISS", "REPEATED_PUT"})
    public Operation operation;

    BloomFilter<String> fixed;
    ScalableBloomFilter<String> scalable;
    OtelBloomFilter<String> noop;
    OtelBloomFilter<String> sdk;
    OtelBloomFilter<String> refreshing;
    InMemoryMetricReader reader;
    private SdkMeterProvider provider;
    final DueClock dueClock = new DueClock();
    private String[] queries;
    private int cursor;

    @Setup(Level.Trial)
    public void setup() {
        if (N < 1_000) throw new IllegalArgumentException("N must be at least 1000");
        if (kind == Kind.FIXED) fixed = BloomFilter.forStrings(N, TARGET, STRATEGY);
        else scalable = ScalableBloomFilter.forStrings(1_000, TARGET, STRATEGY);
        // Generate values without retaining the input set; only the bounded query pool survives.
        for (int i = 0; i < N; i++) rawPut(key(i));
        queries = new String[QUERY_COUNT];
        var random = new SplittableRandom(0x50524553454e4345L);
        for (int i = 0; i < queries.length; i++) {
            long id = random.nextInt(N);
            if (operation == Operation.MISS) id += N;
            queries[i] = key(id);
        }
        cursor = 0;
        var noopMeter = OpenTelemetry.noop().getMeter("presence.benchmark");
        reader = InMemoryMetricReader.create();
        provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
        var meter = provider.get("presence.benchmark");
        noop = fixed != null ? OtelBloomFilter.wrap(fixed, noopMeter, "noop")
                : OtelBloomFilter.wrap(scalable, noopMeter, "noop");
        sdk = fixed != null ? OtelBloomFilter.wrap(fixed, meter, "sdk")
                : OtelBloomFilter.wrap(scalable, meter, "sdk");
        refreshing = fixed != null
                ? OtelBloomFilter.wrap(fixed, meter, "refresh", OtelBloomFilter.DEFAULT_REFRESH_INTERVAL, dueClock)
                : OtelBloomFilter.wrap(scalable, meter, "refresh", OtelBloomFilter.DEFAULT_REFRESH_INTERVAL, dueClock);
    }

    @Setup(Level.Iteration)
    public void beforeIteration() {
        // Each measured iteration lasts 500 ms, well below the default 30-second interval.
        noop.refreshStatistics();
        sdk.refreshStatistics();
        refreshing.refreshStatistics();
        // Establish both outcome series and the put series outside measurement.
        for (var wrapper : java.util.List.of(noop, sdk, refreshing)) {
            wrapper.put(key(0));
            wrapper.mightContain(key(0));
            wrapper.mightContain(key(2L * N));
        }
    }

    @Benchmark
    public boolean raw() {
        String query = nextQuery();
        if (operation == Operation.REPEATED_PUT) {
            rawPut(query);
            return true;
        }
        return rawLookup(query);
    }

    @Benchmark
    public boolean noopMeter() { return observedOperation(noop); }

    @Benchmark
    public boolean sdkRecording() { return observedOperation(sdk); }

    @Benchmark
    public boolean sdkRefreshing() {
        // Force the real interval branch, retaining both real nanoTime reads.
        // The extra offset increment/additions are included in this score.
        dueClock.advance();
        return observedOperation(refreshing);
    }

    private boolean observedOperation(OtelBloomFilter<String> wrapper) {
        String query = nextQuery();
        if (operation == Operation.REPEATED_PUT) {
            wrapper.put(query);
            return true;
        }
        return wrapper.mightContain(query);
    }

    private String nextQuery() {
        String query = queries[cursor];
        cursor = (cursor + 1) & (QUERY_COUNT - 1);
        return query;
    }

    boolean rawLookup(String value) {
        return fixed != null ? fixed.mightContain(value) : scalable.mightContain(value);
    }

    void rawPut(String value) {
        if (fixed != null) fixed.put(value);
        else scalable.put(value);
    }

    int layers() { return fixed != null ? 1 : scalable.layerCount(); }
    long arrayBytes() {
        return fixed != null ? ((long) fixed.bitSize() + 63) / 64 * 8 : scalable.bitSize() / 8;
    }

    static String key(long value) { return "event-" + Long.toHexString(value ^ Long.MIN_VALUE); }

    @TearDown(Level.Trial)
    public void close() {
        if (noop != null) noop.close();
        if (sdk != null) sdk.close();
        if (refreshing != null) refreshing.close();
        if (provider != null) provider.close();
    }

    static final class DueClock implements LongSupplier {
        private long offset;
        void advance() { offset += OtelBloomFilter.DEFAULT_REFRESH_INTERVAL.toNanos(); }
        @Override public long getAsLong() { return System.nanoTime() + offset; }
    }
}
