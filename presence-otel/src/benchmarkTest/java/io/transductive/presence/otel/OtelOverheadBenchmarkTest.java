package io.transductive.presence.otel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.common.AttributeKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class OtelOverheadBenchmarkTest {
    @ParameterizedTest
    @EnumSource(OtelOverheadBenchmark.Kind.class)
    void allPathsUseTheSameAbsentQueriesAndRecordRealSdkCounters(OtelOverheadBenchmark.Kind kind) {
        var fixture = fixture(kind, OtelOverheadBenchmark.Operation.MISS);
        try {
            var outcomes = new boolean[OtelOverheadBenchmark.QUERY_COUNT];
            int positives = 0;
            for (int i = 0; i < outcomes.length; i++) {
                outcomes[i] = fixture.raw();
                if (outcomes[i]) positives++;
            }
            assertTrue(positives < outcomes.length / 20, "The disjoint absent pool should mostly miss");
            for (boolean expected : outcomes) assertEquals(expected, fixture.noopMeter());
            for (boolean expected : outcomes) assertEquals(expected, fixture.sdkRecording());
            for (boolean expected : outcomes) assertEquals(expected, fixture.sdkRefreshing());
            assertEquals(outcomes.length, count(fixture, "sdk", "presence.bloom.lookups"));
            assertEquals(outcomes.length, count(fixture, "refresh", "presence.bloom.lookups"));
            assertEquals(positives, possibleCount(fixture, "sdk"));
            assertEquals(positives, possibleCount(fixture, "refresh"));
        } finally {
            fixture.close();
        }
    }

    @ParameterizedTest
    @EnumSource(OtelOverheadBenchmark.Kind.class)
    void everyForcedOperationPublishesNewStatisticsWhileSteadyPathDoesNot(OtelOverheadBenchmark.Kind kind) {
        var fixture = fixture(kind, OtelOverheadBenchmark.Operation.HIT);
        try {
            double previous = estimate(fixture, "refresh");
            double steady = estimate(fixture, "sdk");
            for (int round = 0; round < 3; round++) {
                for (int i = 0; i < 500; i++) fixture.rawPut(OtelOverheadBenchmark.key(1_000L + round * 500L + i));
                assertTrue(fixture.sdkRecording());
                assertEquals(steady, estimate(fixture, "sdk"), "A normal lookup must not rescan before due");
                assertTrue(fixture.sdkRefreshing());
                double refreshed = estimate(fixture, "refresh");
                assertTrue(refreshed > previous, "Every forced operation must actually refresh");
                previous = refreshed;
            }
        } finally {
            fixture.close();
        }
    }

    @ParameterizedTest
    @EnumSource(OtelOverheadBenchmark.Kind.class)
    void presentQueriesAndRepeatedPutsKeepMembershipAndOccupancyStable(OtelOverheadBenchmark.Kind kind) {
        for (var operation : new OtelOverheadBenchmark.Operation[] {
                OtelOverheadBenchmark.Operation.HIT, OtelOverheadBenchmark.Operation.REPEATED_PUT}) {
            var fixture = fixture(kind, operation);
            try {
                double initial = estimate(fixture, "refresh");
                long bytes = fixture.arrayBytes();
                int layers = fixture.layers();
                for (int i = 0; i < OtelOverheadBenchmark.QUERY_COUNT; i++) {
                    assertTrue(fixture.raw());
                    assertTrue(fixture.noopMeter());
                    assertTrue(fixture.sdkRecording());
                    assertTrue(fixture.sdkRefreshing());
                }
                assertEquals(initial, estimate(fixture, "refresh"));
                assertEquals(bytes, fixture.arrayBytes());
                assertEquals(layers, fixture.layers());
                for (int i = 0; i < fixture.N; i++) assertTrue(fixture.rawLookup(OtelOverheadBenchmark.key(i)));
                String metric = operation == OtelOverheadBenchmark.Operation.HIT ? "presence.bloom.lookups" : "presence.bloom.puts";
                assertEquals(OtelOverheadBenchmark.QUERY_COUNT, count(fixture, "sdk", metric));
                assertEquals(OtelOverheadBenchmark.QUERY_COUNT, count(fixture, "refresh", metric));
            } finally {
                fixture.close();
            }
        }
    }

    @Test
    void reportSizesAreValidatedAndNormalized() {
        assertArrayEquals(new String[] {"1000", "10000"}, OtelBenchmarkRunner.sizes("10000,1000,01000"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "999", "-1000", "1000,", "1000,abc", "1e6", "2147483648"})
    void rejectsInvalidSizes(String sizes) {
        assertThrows(IllegalArgumentException.class, () -> OtelBenchmarkRunner.sizes(sizes));
    }

    private static OtelOverheadBenchmark fixture(OtelOverheadBenchmark.Kind kind, OtelOverheadBenchmark.Operation operation) {
        var fixture = new OtelOverheadBenchmark();
        fixture.N = 1_000;
        fixture.kind = kind;
        fixture.operation = operation;
        fixture.setup();
        return fixture;
    }

    private static double estimate(OtelOverheadBenchmark fixture, String filter) {
        return fixture.reader.collectAllMetrics().stream()
                .filter(metric -> metric.getName().equals("presence.bloom.elements.estimated"))
                .flatMap(metric -> metric.getDoubleGaugeData().getPoints().stream())
                .filter(point -> filter.equals(point.getAttributes().get(AttributeKey.stringKey("presence.filter.name"))))
                .findFirst().orElseThrow().getValue();
    }

    private static long count(OtelOverheadBenchmark fixture, String filter, String name) {
        return fixture.reader.collectAllMetrics().stream().filter(metric -> metric.getName().equals(name))
                .flatMap(metric -> metric.getLongSumData().getPoints().stream())
                .filter(point -> filter.equals(point.getAttributes().get(AttributeKey.stringKey("presence.filter.name"))))
                .mapToLong(point -> point.getValue()).sum();
    }

    private static long possibleCount(OtelOverheadBenchmark fixture, String filter) {
        return fixture.reader.collectAllMetrics().stream().filter(metric -> metric.getName().equals("presence.bloom.lookups"))
                .flatMap(metric -> metric.getLongSumData().getPoints().stream())
                .filter(point -> filter.equals(point.getAttributes().get(AttributeKey.stringKey("presence.filter.name"))))
                .filter(point -> "possibly_present".equals(point.getAttributes().get(AttributeKey.stringKey("presence.lookup.outcome"))))
                .mapToLong(point -> point.getValue()).sum();
    }
}
