package io.transductive.presence.otel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.transductive.presence.bloom.BloomFilter;
import io.transductive.presence.bloom.ScalableBloomFilter;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OtelBloomFilterTest {
    private InMemoryMetricReader reader;
    private SdkMeterProvider provider;
    private Meter meter;

    @BeforeEach
    void setUp() {
        reader = InMemoryMetricReader.create();
        provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
        meter = provider.get("io.transductive.presence.otel.test");
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesMembershipAndCountsSuccessfulCalls(boolean scalable) {
        try (var observed = wrap(scalable)) {
            assertFalse(observed.mightContain("before-insertion"));
            observed.put("remembered");
            observed.put("remembered");
            assertTrue(observed.mightContain("remembered"));
            assertThrows(NullPointerException.class, () -> observed.put(null));
            assertThrows(NullPointerException.class, () -> observed.mightContain(null));

            var metrics = reader.collectAllMetrics();
            assertEquals(2, total(metrics, "presence.bloom.puts"));
            assertEquals(2, total(metrics, "presence.bloom.lookups"));
            var lookups = metric(metrics, "presence.bloom.lookups");
            assertEquals(2, lookups.getLongSumData().getPoints().size());
            for (var point : lookups.getLongSumData().getPoints()) {
                assertEquals(1, point.getValue());
                assertEquals("test-filter", point.getAttributes().get(AttributeKey.stringKey("presence.filter.name")));
                assertEquals(scalable ? "scalable" : "fixed",
                        point.getAttributes().get(AttributeKey.stringKey("presence.filter.type")));
                assertEquals("MURMUR3_128", point.getAttributes().get(AttributeKey.stringKey("presence.hashing.strategy")));
                String outcome = point.getAttributes().get(AttributeKey.stringKey("presence.lookup.outcome"));
                assertTrue("possibly_present".equals(outcome) || "definitely_absent".equals(outcome));
            }
            assertTrue(lookups.getLongSumData().isMonotonic());
            assertFalse(metric(metrics, "presence.bloom.layers").getLongSumData().isMonotonic());
        }
    }

    @Test
    void falsePositiveIsOnlyReportedAsPossiblyPresent() {
        var core = new BloomFilter<String>(1, 3, String::getBytes);
        try (var observed = OtelBloomFilter.wrap(core, meter, "saturated")) {
            observed.put("inserted");
            assertTrue(observed.mightContain("never-inserted"));
            var point = metric(reader.collectAllMetrics(), "presence.bloom.lookups")
                    .getLongSumData().getPoints().iterator().next();
            assertEquals("possibly_present", point.getAttributes().get(AttributeKey.stringKey("presence.lookup.outcome")));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 63, 64, 65, 127, 128, 129})
    void fixedFootprintIncludesWordRounding(int bits) {
        var core = new BloomFilter<byte[]>(bits, 1, value -> value);
        try (var observed = OtelBloomFilter.wrap(core, meter, "rounded")) {
            observed.put(new byte[] {42});
            var metrics = reader.collectAllMetrics();
            assertEquals(bits <= 64 ? 8 : bits <= 128 ? 16 : 24, total(metrics, "presence.bloom.bit_array.bytes"));
            assertEquals(1, total(metrics, "presence.bloom.layers"));
            assertEquals("By", metric(metrics, "presence.bloom.bit_array.bytes").getUnit());
        }
    }

    @Test
    void growthPublishesFootprintWithoutLookingUpOrInsertingExtraValues() {
        var core = ScalableBloomFilter.forStrings(1_000, 0.01);
        var control = ScalableBloomFilter.forStrings(1_000, 0.01);
        try (var observed = OtelBloomFilter.wrap(core, meter, "growing")) {
            for (int i = 0; i < 10_000; i++) {
                String value = "insert-" + i;
                observed.put(value);
                control.put(value);
            }
            assertTrue(core.layerCount() > 1);
            for (int i = 0; i < 10_000; i++) {
                assertTrue(observed.mightContain("insert-" + i));
                assertEquals(control.mightContain("miss-" + i), observed.mightContain("miss-" + i));
            }
            var metrics = reader.collectAllMetrics();
            assertEquals(core.layerCount(), total(metrics, "presence.bloom.layers"));
            assertEquals(core.bitSize() / 8, total(metrics, "presence.bloom.bit_array.bytes"));
            assertEquals(10_000, total(metrics, "presence.bloom.puts"));
            assertEquals(20_000, total(metrics, "presence.bloom.lookups"));
        }
    }

    @Test
    void wrappingAnExistingFilterDoesNotInventHistoricalCounts() {
        var core = ScalableBloomFilter.forStrings(1_000, 0.01);
        for (int i = 0; i < 10_000; i++) core.put("existing-" + i);
        try (var observed = OtelBloomFilter.wrap(core, meter, "existing")) {
            var metrics = reader.collectAllMetrics();
            assertEquals(core.bitSize() / 8, total(metrics, "presence.bloom.bit_array.bytes"));
            assertEquals(core.layerCount(), total(metrics, "presence.bloom.layers"));
            assertEquals(0, total(metrics, "presence.bloom.puts"));
            assertTrue(observed.mightContain("existing-42"));
        }
    }

    @Test
    void directMutationsRequireFootprintRefreshAndBypassOperationCounts() {
        var core = ScalableBloomFilter.forStrings(1_000, 0.01);
        try (var observed = OtelBloomFilter.wrap(core, meter, "direct")) {
            long initialBytes = core.bitSize() / 8;
            for (int i = 0; i < 10_000; i++) core.put("direct-" + i);
            assertTrue(core.bitSize() / 8 > initialBytes);
            assertEquals(initialBytes, total(reader.collectAllMetrics(), "presence.bloom.bit_array.bytes"));
            observed.refreshFootprint();
            var metrics = reader.collectAllMetrics();
            assertEquals(core.bitSize() / 8, total(metrics, "presence.bloom.bit_array.bytes"));
            assertEquals(0, total(metrics, "presence.bloom.puts"));
            assertEquals(0, total(metrics, "presence.bloom.lookups"));
        }
    }

    @Test
    void encoderFailuresPropagateWithoutCountingSuccess() {
        var core = BloomFilter.<Integer>create(1_000, 0.01, value -> {
            if (value < 0) throw new IllegalArgumentException("invalid ID");
            return new byte[] {value.byteValue()};
        });
        try (var observed = OtelBloomFilter.wrap(core, meter, "integers")) {
            assertThrows(IllegalArgumentException.class, () -> observed.put(-1));
            assertThrows(IllegalArgumentException.class, () -> observed.mightContain(-1));
            observed.put(42);
            assertTrue(observed.mightContain(42));
            assertEquals(1, total(reader.collectAllMetrics(), "presence.bloom.puts"));
            assertEquals(1, total(reader.collectAllMetrics(), "presence.bloom.lookups"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void statisticsRemainCachedBeforeIntervalAndCanBeRefreshedManually(boolean scalable) {
        try (var observed = wrap(scalable, Duration.ofSeconds(30), new TestClock())) {
            assertEquals(0, metric(reader.collectAllMetrics(), "presence.bloom.elements.estimated")
                    .getDoubleGaugeData().getPoints().iterator().next().getValue());
            for (int i = 0; i < 1_000; i++) observed.put("member-" + i);
            // Collection reads the last sample, never mutable core state.
            assertEquals(0, metric(reader.collectAllMetrics(), "presence.bloom.elements.estimated")
                    .getDoubleGaugeData().getPoints().iterator().next().getValue());
            observed.refreshStatistics();
            var metrics = reader.collectAllMetrics();
            assertEquals(1_000, metric(metrics, "presence.bloom.elements.estimated")
                    .getDoubleGaugeData().getPoints().iterator().next().getValue(), 100);
            assertTrue(metric(metrics, "presence.bloom.active_layer.fill_ratio")
                    .getDoubleGaugeData().getPoints().iterator().next().getValue() > 0);
            assertTrue(metric(metrics, "presence.bloom.false_positive_probability.estimated")
                    .getDoubleGaugeData().getPoints().iterator().next().getValue() > 0);
            assertEquals(1_000, total(metrics, "presence.bloom.puts"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void firstSuccessfulPutAtIntervalPublishesUpdatedStatistics(boolean scalable) {
        var clock = new TestClock();
        assertEquals(Duration.ofSeconds(30), OtelBloomFilter.DEFAULT_REFRESH_INTERVAL);
        try (var observed = wrap(scalable, OtelBloomFilter.DEFAULT_REFRESH_INTERVAL, clock)) {
            for (int i = 0; i < 1_000; i++) observed.put("member-" + i);
            clock.nanos = Duration.ofSeconds(30).toNanos() - 1;
            observed.put("just-before");
            assertEquals(0, estimatedElements());
            clock.nanos++;
            observed.put("at-boundary");
            double refreshed = estimatedElements();
            assertEquals(1_002, refreshed, 100);
            for (int i = 0; i < 100; i++) observed.put("later-" + i);
            assertEquals(refreshed, estimatedElements(), "Other operations in the interval use the cached sample");
            assertEquals(1_102, total(reader.collectAllMetrics(), "presence.bloom.puts"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void idleCollectionDoesNotRefreshButNextLookupDoes(boolean scalable) {
        var clock = new TestClock();
        try (var observed = wrap(scalable, Duration.ofSeconds(2), clock)) {
            observed.put("remembered");
            clock.nanos = Duration.ofHours(1).toNanos();
            assertEquals(0, estimatedElements(), "SDK collection must not sample mutable core state");
            assertTrue(observed.mightContain("remembered"));
            assertTrue(estimatedElements() > 0);
            assertEquals(1, total(reader.collectAllMetrics(), "presence.bloom.puts"));
            assertEquals(1, total(reader.collectAllMetrics(), "presence.bloom.lookups"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void manualRefreshRestartsTheInterval(boolean scalable) {
        var clock = new TestClock();
        try (var observed = wrap(scalable, Duration.ofSeconds(30), clock)) {
            observed.put("one");
            clock.nanos = Duration.ofSeconds(20).toNanos();
            observed.refreshStatistics();
            double manual = estimatedElements();
            assertTrue(manual > 0);
            clock.nanos = Duration.ofSeconds(30).toNanos();
            observed.put("two");
            assertEquals(manual, estimatedElements(), "Manual refresh starts a new interval");
            clock.nanos = Duration.ofSeconds(50).toNanos() - 1;
            assertTrue(observed.mightContain("two"));
            assertEquals(manual, estimatedElements());
            clock.nanos++;
            assertTrue(observed.mightContain("two"));
            assertTrue(estimatedElements() > manual);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedOperationsLeaveRefreshDueForNextSuccess(boolean scalable) {
        var clock = new TestClock();
        try (var observed = wrap(scalable, Duration.ofSeconds(1), clock)) {
            observed.put("one");
            clock.nanos = Duration.ofSeconds(1).toNanos();
            assertThrows(NullPointerException.class, () -> observed.put(null));
            assertThrows(NullPointerException.class, () -> observed.mightContain(null));
            assertEquals(0, estimatedElements());
            assertTrue(observed.mightContain("one"));
            assertTrue(estimatedElements() > 0);
            assertEquals(1, total(reader.collectAllMetrics(), "presence.bloom.puts"));
            assertEquals(1, total(reader.collectAllMetrics(), "presence.bloom.lookups"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void footprintRefreshDoesNotPostponeStatistics(boolean scalable) {
        var clock = new TestClock();
        try (var observed = wrap(scalable, Duration.ofSeconds(30), clock)) {
            observed.put("one");
            clock.nanos = Duration.ofSeconds(29).toNanos();
            observed.refreshFootprint();
            assertEquals(0, estimatedElements());
            clock.nanos = Duration.ofSeconds(30).toNanos();
            assertTrue(observed.mightContain("one"));
            assertTrue(estimatedElements() > 0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void elapsedTimeSurvivesSignedNanoTimeWraparound(boolean scalable) {
        var clock = new TestClock();
        clock.nanos = Long.MAX_VALUE - 5;
        try (var observed = wrap(scalable, Duration.ofNanos(10), clock)) {
            observed.put("one");
            clock.nanos += 9;
            assertTrue(observed.mightContain("one"));
            assertEquals(0, estimatedElements());
            clock.nanos++;
            assertTrue(observed.mightContain("one"));
            assertTrue(estimatedElements() > 0);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void validatesRefreshIntervalBeforeRegisteringMetrics(boolean scalable) {
        var clock = new TestClock();
        assertThrows(NullPointerException.class, () -> wrap(scalable, null, clock));
        for (Duration interval : new Duration[] {Duration.ZERO, Duration.ofNanos(-1), Duration.ofSeconds(Long.MAX_VALUE)}) {
            assertThrows(IllegalArgumentException.class, () -> wrap(scalable, interval, clock));
        }
        assertTrue(reader.collectAllMetrics().isEmpty());
    }

    @Test
    void collectionCanRunDuringOwnerThreadGrowth() throws Exception {
        Thread owner = Thread.currentThread();
        var clock = new TestClock();
        var core = ScalableBloomFilter.<Integer>create(1_000, 0.01, value -> {
            assertEquals(owner, Thread.currentThread(), "collection must not touch the filter's encoder");
            return new byte[] {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), value.byteValue()};
        });
        try (var observed = OtelBloomFilter.wrap(core, meter, "concurrent-collection", Duration.ofNanos(100), () -> {
                    assertEquals(owner, Thread.currentThread(), "Only the owner checks the refresh clock");
                    return clock.getAsLong();
                });
                var executor = Executors.newSingleThreadExecutor()) {
            var collection = executor.submit(() -> {
                for (int i = 0; i < 500; i++) {
                    var metrics = reader.collectAllMetrics();
                    assertTrue(total(metrics, "presence.bloom.layers") >= 1);
                    assertTrue(total(metrics, "presence.bloom.bit_array.bytes") > 0);
                }
            });
            for (int i = 0; i < 10_000; i++) {
                clock.nanos++;
                observed.put(i);
            }
            collection.get(20, TimeUnit.SECONDS);
            assertEquals(core.bitSize() / 8, total(reader.collectAllMetrics(), "presence.bloom.bit_array.bytes"));
            assertTrue(estimatedElements() > 0);
        }
    }

    @Test
    void closeRemovesCallbackButPreservesCoreAndApplicationSdk() {
        var core = BloomFilter.forStrings(1_000, 0.01);
        var observed = OtelBloomFilter.wrap(core, meter, "retired");
        observed.put("retained");
        assertEquals(1, total(reader.collectAllMetrics(), "presence.bloom.layers"));
        observed.close();
        observed.close();
        assertEquals(0, total(reader.collectAllMetrics(), "presence.bloom.layers"));
        assertEquals(0, total(reader.collectAllMetrics(), "presence.bloom.bit_array.bytes"));
        assertThrows(IllegalStateException.class, () -> observed.put("new"));
        assertThrows(IllegalStateException.class, () -> observed.mightContain("retained"));
        assertThrows(IllegalStateException.class, observed::refreshFootprint);
        assertThrows(IllegalStateException.class, observed::refreshStatistics);
        assertTrue(core.mightContain("retained"));
        meter.counterBuilder("application.counter").build().add(1);
        assertEquals(1, total(reader.collectAllMetrics(), "application.counter"));
    }

    @Test
    void distinctLogicalNamesKeepFootprintsSeparate() {
        try (var first = OtelBloomFilter.wrap(new BloomFilter<byte[]>(1, 1, value -> value), meter, "first");
                var second = OtelBloomFilter.wrap(new BloomFilter<byte[]>(65, 1, value -> value), meter, "second")) {
            first.put(new byte[] {1});
            second.put(new byte[] {2});
            var metrics = reader.collectAllMetrics();
            assertEquals(2, metric(metrics, "presence.bloom.bit_array.bytes").getLongSumData().getPoints().size());
            assertEquals(24, total(metrics, "presence.bloom.bit_array.bytes"));
            assertEquals(2, total(metrics, "presence.bloom.layers"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void noOpMeterNeedsNoSdk(boolean scalable) {
        Meter noop = OpenTelemetry.noop().getMeter("presence-example");
        try (var observed = scalable
                ? OtelBloomFilter.wrap(ScalableBloomFilter.forStrings(1_000, 0.01), noop, "noop")
                : OtelBloomFilter.wrap(BloomFilter.forStrings(1_000, 0.01), noop, "noop")) {
            assertFalse(observed.mightContain("absent"));
            observed.put("present");
            assertTrue(observed.mightContain("present"));
        }
    }

    @Test
    void validatesArgumentsBeforeRegisteringMetrics() {
        var core = BloomFilter.forStrings(1_000, 0.01);
        assertThrows(NullPointerException.class, () -> OtelBloomFilter.wrap((BloomFilter<String>) null, meter, "name"));
        assertThrows(NullPointerException.class, () -> OtelBloomFilter.wrap((ScalableBloomFilter<String>) null, meter, "name"));
        assertThrows(NullPointerException.class, () -> OtelBloomFilter.wrap(core, null, "name"));
        assertThrows(NullPointerException.class, () -> OtelBloomFilter.wrap(core, meter, null));
        assertThrows(IllegalArgumentException.class, () -> OtelBloomFilter.wrap(core, meter, "   "));
        assertThrows(IllegalArgumentException.class, () -> OtelBloomFilter.wrap(core, meter, "a".repeat(129)));
        assertTrue(reader.collectAllMetrics().isEmpty());
    }

    private OtelBloomFilter<String> wrap(boolean scalable) {
        return scalable
                ? OtelBloomFilter.wrap(ScalableBloomFilter.forStrings(1_000, 0.01), meter, "test-filter")
                : OtelBloomFilter.wrap(BloomFilter.forStrings(1_000, 0.01), meter, "test-filter");
    }

    private OtelBloomFilter<String> wrap(boolean scalable, Duration interval, LongSupplier clock) {
        return scalable
                ? OtelBloomFilter.wrap(ScalableBloomFilter.forStrings(1_000, 0.01), meter, "timed", interval, clock)
                : OtelBloomFilter.wrap(BloomFilter.forStrings(1_000, 0.01), meter, "timed", interval, clock);
    }

    private double estimatedElements() {
        return metric(reader.collectAllMetrics(), "presence.bloom.elements.estimated")
                .getDoubleGaugeData().getPoints().iterator().next().getValue();
    }

    private static final class TestClock implements LongSupplier {
        private long nanos;
        @Override public long getAsLong() { return nanos; }
    }

    private static MetricData metric(Collection<MetricData> metrics, String name) {
        return metrics.stream().filter(metric -> metric.getName().equals(name)).findFirst().orElseThrow();
    }

    private static long total(Collection<MetricData> metrics, String name) {
        return metrics.stream().filter(metric -> metric.getName().equals(name))
                .flatMap(metric -> metric.getLongSumData().getPoints().stream()).mapToLong(point -> point.getValue()).sum();
    }
}
