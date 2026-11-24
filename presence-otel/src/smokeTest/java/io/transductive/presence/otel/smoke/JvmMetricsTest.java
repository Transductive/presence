package io.transductive.presence.otel.smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

@SuppressWarnings("try") // Registrations live for the test scope and are closed after SDK collection.
class JvmMetricsTest {
    @Test
    void reportsActualPoolNamesAndSeparatesHeapFromNonHeap() {
        var heap = pool("Example Nursery", MemoryType.HEAP, new MemoryUsage(-1, 12, 20, 40),
                new MemoryUsage(-1, 16, 20, 40), new MemoryUsage(-1, 5, 20, 40), true);
        var nonHeap = pool("Metaspace", MemoryType.NON_HEAP, new MemoryUsage(-1, 8, 10, -1), null, null, true);
        var reader = InMemoryMetricReader.create();
        try (var provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
                var metrics = new JvmMetrics(provider.get("test"), List.of(heap, nonHeap), List.of(), List.of())) {
            var collected = reader.collectAllMetrics();
            var points = metric(collected, "memory.pool.used").getLongGaugeData().getPoints();
            assertEquals(2, points.size());
            for (var point : points) {
                String name = point.getAttributes().get(AttributeKey.stringKey("jvm.memory.pool.name"));
                assertEquals(name.equals("Metaspace") ? "non_heap" : "heap",
                        point.getAttributes().get(AttributeKey.stringKey("jvm.memory.type")));
                assertEquals(name.equals("Metaspace") ? 8 : 12, point.getValue());
            }
            var limits = metric(collected, "memory.pool.limit").getLongGaugeData().getPoints();
            assertEquals(1, limits.size(), "An undefined maximum must not be reported as zero");
            assertEquals(40, limits.iterator().next().getValue());
            assertEquals(16, metric(collected, "memory.pool.peak").getLongGaugeData().getPoints().iterator().next().getValue());
            assertEquals(5, metric(collected, "memory.pool.after_gc").getLongGaugeData().getPoints().iterator().next().getValue());
        }
    }

    @Test
    void reportsCumulativeGcCountersInSecondsAndZeroBufferUsage() {
        var reader = InMemoryMetricReader.create();
        try (var provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
                var metrics = new JvmMetrics(provider.get("test"), List.of(),
                        List.of(collector("Example Collector", 0, 1_250)), List.of(buffer("direct", 0, 0, 0)))) {
            var collected = reader.collectAllMetrics();
            var collections = metric(collected, "gc.collections").getLongSumData();
            assertTrue(collections.isMonotonic());
            assertEquals(0, collections.getPoints().iterator().next().getValue());
            assertEquals("Example Collector", collections.getPoints().iterator().next().getAttributes()
                    .get(AttributeKey.stringKey("jvm.gc.name")));
            var elapsed = metric(collected, "gc.collection_time");
            assertEquals("s", elapsed.getUnit());
            assertTrue(elapsed.getDoubleSumData().isMonotonic());
            assertEquals(1.25, elapsed.getDoubleSumData().getPoints().iterator().next().getValue());
            assertEquals(0, metric(collected, "buffer.used").getLongGaugeData().getPoints().iterator().next().getValue());
            // Observable counters record the current cumulative value, not an increment on each collection.
            assertEquals(1.25, metric(reader.collectAllMetrics(), "gc.collection_time")
                    .getDoubleSumData().getPoints().iterator().next().getValue());
        }
    }

    @Test
    void skipsUnavailableAndInvalidMeasurements() {
        var reader = InMemoryMetricReader.create();
        try (var provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
                var metrics = new JvmMetrics(provider.get("test"),
                        List.of(pool("Invalid", MemoryType.HEAP, new MemoryUsage(-1, 1, 1, 1), null, null, false),
                                pool("Unavailable", MemoryType.HEAP, null, null, null, true)),
                        List.of(collector("Unknown", -1, -1)), List.of(buffer("unknown", -1, -1, -1)))) {
            assertTrue(reader.collectAllMetrics().isEmpty());
        }
    }

    @Test
    void closingUnregistersAllJvmObservations() {
        var reader = InMemoryMetricReader.create();
        try (var provider = SdkMeterProvider.builder().registerMetricReader(reader).build()) {
            var metrics = new JvmMetrics(provider.get("test"), List.of(), List.of(collector("GC", 1, 20)), List.of());
            assertEquals(2, reader.collectAllMetrics().size());
            metrics.close();
            assertTrue(reader.collectAllMetrics().isEmpty());
        }
    }

    private static MemoryPoolMXBean pool(String name, MemoryType type, MemoryUsage usage,
            MemoryUsage peak, MemoryUsage collected, boolean valid) {
        return bean(MemoryPoolMXBean.class, method -> switch (method) {
            case "getName" -> name;
            case "getType" -> type;
            case "isValid" -> valid;
            case "getUsage" -> usage;
            case "getPeakUsage" -> peak;
            case "getCollectionUsage" -> collected;
            default -> throw new AssertionError(method);
        });
    }

    private static GarbageCollectorMXBean collector(String name, long count, long millis) {
        return bean(GarbageCollectorMXBean.class, method -> switch (method) {
            case "getName" -> name;
            case "isValid" -> true;
            case "getCollectionCount" -> count;
            case "getCollectionTime" -> millis;
            default -> throw new AssertionError(method);
        });
    }

    private static BufferPoolMXBean buffer(String name, long used, long capacity, long count) {
        return bean(BufferPoolMXBean.class, method -> switch (method) {
            case "getName" -> name;
            case "getMemoryUsed" -> used;
            case "getTotalCapacity" -> capacity;
            case "getCount" -> count;
            default -> throw new AssertionError(method);
        });
    }

    private static <T> T bean(Class<T> type, Function<String, Object> values) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, arguments) -> values.apply(method.getName())));
    }

    private static MetricData metric(Collection<MetricData> data, String suffix) {
        return data.stream().filter(metric -> metric.getName().equals("presence.smoke.jvm." + suffix)).findFirst().orElseThrow();
    }
}
