package io.transductive.presence.otel.smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.time.Duration;
import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TelemetrySmokeTest {
    @Test
    void workloadAccountingMatchesSdkAndExercisesGrowthAndSaturation() {
        var reader = InMemoryMetricReader.create();
        try (var provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
                var workload = new TelemetrySmoke.Workload(provider.get("smoke-test"), root -> 1234L)) {
            for (int i = 0; i < 10_000; i++) workload.cycle();
            workload.refreshStatistics();
            workload.sample();
            var expected = workload.result();
            var metrics = reader.collectAllMetrics();
            assertEquals(42_000, total(metrics, "presence.bloom.puts"));
            assertEquals(40_000, total(metrics, "presence.bloom.lookups"));
            assertEquals(20_000, total(metrics, "presence.smoke.absent_queries"));
            assertEquals(Long.parseLong(expected.getProperty("puts")), total(metrics, "presence.bloom.puts"));
            assertEquals(Long.parseLong(expected.getProperty("lookups")), total(metrics, "presence.bloom.lookups"));
            long fixedFp = Long.parseLong(expected.getProperty("fixedFalsePositives"));
            long scalableFp = Long.parseLong(expected.getProperty("scalableFalsePositives"));
            assertEquals(fixedFp + scalableFp, total(metrics, "presence.smoke.false_positives"));
            assertTrue(fixedFp > 5_000, "Fixed filter should saturate far beyond its sizing capacity");
            assertTrue(scalableFp < 200, "Scalable filter should retain its false-positive budget on this fixture");
            assertTrue(workload.scalableCore.layerCount() > 1);
            var distinct = metric(metrics, "presence.smoke.distinct_inputs").getLongGaugeData().getPoints();
            assertEquals(2, distinct.size());
            distinct.forEach(point -> assertEquals(11_000, point.getValue()));
            metric(metrics, "presence.smoke.filter.reachable_heap.bytes").getLongGaugeData().getPoints()
                    .forEach(point -> assertEquals(1234, point.getValue()));
            for (var point : metric(metrics, "presence.bloom.layers").getLongSumData().getPoints()) {
                String type = point.getAttributes().get(AttributeKey.stringKey("presence.filter.type"));
                assertEquals(type.equals("fixed") ? 1 : workload.scalableCore.layerCount(), point.getValue());
            }
            for (int i = 0; i < 1_000; i++) {
                assertTrue(workload.fixed.mightContain("seed-" + i));
                assertTrue(workload.scalable.mightContain("seed-" + i));
            }
        }
    }

    @Test
    void acceptsFractionalAndDefaultDuration() {
        assertEquals(Duration.ofMinutes(1), TelemetrySmoke.duration("1"));
        assertEquals(Duration.ofSeconds(3), TelemetrySmoke.duration("0.05"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "NaN", "Infinity", "1441", "1e-30", "abc"})
    void rejectsInvalidDurations(String input) {
        assertThrows(IllegalArgumentException.class, () -> TelemetrySmoke.duration(input));
    }

    private static MetricData metric(Collection<MetricData> metrics, String name) {
        return metrics.stream().filter(metric -> metric.getName().equals(name)).findFirst().orElseThrow();
    }

    private static long total(Collection<MetricData> metrics, String name) {
        return metric(metrics, name).getLongSumData().getPoints().stream().mapToLong(point -> point.getValue()).sum();
    }
}
