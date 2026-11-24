package io.transductive.presence.flink;

import static org.junit.jupiter.api.Assertions.*;

import io.transductive.presence.bloom.HashingStrategy;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.metrics.Counter;
import org.apache.flink.metrics.Gauge;
import org.apache.flink.metrics.Metric;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.executiongraph.ExecutionAttemptID;
import org.apache.flink.runtime.metrics.groups.TaskManagerMetricGroup;
import org.apache.flink.runtime.metrics.util.TestingMetricRegistry;
import org.apache.flink.runtime.operators.testutils.MockEnvironment;
import org.apache.flink.streaming.api.operators.KeyedProcessOperator;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.apache.flink.util.InstantiationUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** Exercises actual Flink metric registration and cached gauges through the keyed operator. */
@SuppressWarnings("try")
class BloomMetricsTest {
    private static final long INTERVAL = BloomMetrics.DEFAULT_REFRESH_INTERVAL.toNanos();

    @ParameterizedTest
    @CsvSource({"false,HEAP", "true,HEAP", "false,MANAGED_STATE", "true,MANAGED_STATE"})
    void samplesAllResidentKeysAfterForwardedAndDroppedRecordsAndResets(boolean scalable, BloomBackend backend) throws Exception {
        var fixture = new Fixture(scalable, backend, BloomMetrics.DETAILED, event -> Decision.DROP,
                List.of((event, membership) -> { if (event.f1.equals("reset")) membership.clearCurrentKey(); }));
        try (fixture) {
            fixture.open();
            assertEquals(8, fixture.metrics.keySet().stream().filter(key -> key.startsWith("presence.statistics.")).count());
            assertEquals(0, fixture.stat("bloomGroups"));
            fixture.send("a", "id");
            fixture.send("b", "id");
            assertEquals(2, fixture.gauge("presence.bloomGroups"));
            assertEquals(0, fixture.stat("estimatedElements"), "automatic statistics are initially cached");
            fixture.clock.nanos = INTERVAL - 1;
            fixture.send("b", "id");
            assertEquals(0, fixture.stat("refreshCount"));
            fixture.clock.nanos++;
            fixture.send("b", "id");
            assertEquals(1, fixture.stat("refreshCount"), "dropped traffic must trigger sampling too");
            assertEquals(2, fixture.stat("bloomGroups"));
            assertEquals(2, fixture.stat("layers"));
            assertEquals(2, fixture.stat("estimatedElements"), 0.03);
            assertTrue(fixture.stat("bitArrayBytes") > 0);
            assertTrue(fixture.stat("maximumActiveLayerFillRatio") > 0);
            assertTrue(fixture.stat("maximumEstimatedFalsePositiveProbability") > 0);
            assertEquals(2, fixture.counter("presence.possibleDuplicates"));
            assertEquals(2, fixture.counter("presence.dropped"));
            assertEquals(2, fixture.counter("presence.forwarded"));
            fixture.clock.nanos += INTERVAL;
            fixture.send("a", "second");
            assertEquals(3, fixture.stat("estimatedElements"), 0.03);
            fixture.clock.nanos += INTERVAL;
            fixture.send("a", "reset");
            assertEquals(2, fixture.stat("estimatedElements"), 0.03);
            assertEquals(1, fixture.counter("presence.membershipResets"));
            assertTrue(fixture.metrics.keySet().stream().noneMatch(name -> name.contains("tenant")));
        }
        assertEquals(0, fixture.stat("bitArrayBytes"), "retired gauges release their last sample");
    }

    @ParameterizedTest
    @EnumSource(BloomBackend.class)
    void basicMetricsKeepExistingCountersWithoutReadingTheStatisticsClock(BloomBackend backend) throws Exception {
        try (var fixture = new Fixture(true, backend, BloomMetrics.BASIC, event -> Decision.DROP, List.of())) {
            fixture.function.setMetricsClock(() -> { throw new AssertionError("BASIC must not check the sampling clock"); });
            fixture.open();
            fixture.send("a", "id");
            fixture.send("a", "id");
            assertEquals(1, fixture.counter("presence.forwarded"));
            assertEquals(1, fixture.counter("presence.dropped"));
            assertTrue(fixture.metrics.keySet().stream().noneMatch(key -> key.startsWith("presence.statistics.")));
        }
    }

    @Test
    void manualRefreshUsesTheOwningHookAndRestartsTheInterval() throws Exception {
        var retained = new AtomicReference<MembershipControl>();
        var hooks = List.<BeforeLookup<? super Tuple2<String, String>>>of((event, membership) -> {
            retained.set(membership);
            if (event.f1.equals("sample")) membership.refreshStatistics();
        });
        try (var fixture = new Fixture(true, BloomBackend.HEAP, BloomMetrics.DETAILED, event -> Decision.DROP, hooks)) {
            fixture.open();
            fixture.send("a", "id");
            fixture.clock.nanos = Duration.ofSeconds(10).toNanos();
            fixture.send("a", "sample");
            assertEquals(1, fixture.stat("refreshCount"));
            assertEquals(1, fixture.stat("estimatedElements"), 0.01, "manual hook runs before the current lookup");
            assertThrows(IllegalStateException.class, retained.get()::refreshStatistics);
            fixture.clock.nanos += INTERVAL - 1;
            fixture.send("a", "id");
            assertEquals(1, fixture.stat("refreshCount"));
            fixture.clock.nanos++;
            fixture.send("a", "id");
            assertEquals(2, fixture.stat("refreshCount"));
            assertEquals(2, fixture.stat("estimatedElements"), 0.02);
        }
    }

    @Test
    void manualRefreshRequiresDetailedMetricsAndCannotRunFromAnotherThread() throws Exception {
        var hook = List.<BeforeLookup<? super Tuple2<String, String>>>of((event, membership) -> membership.refreshStatistics());
        try (var fixture = new Fixture(true, BloomBackend.HEAP, BloomMetrics.BASIC, event -> Decision.DROP, hook)) {
            fixture.open();
            assertThrows(IllegalStateException.class, () -> fixture.send("a", "id"));
        }
        var crossThread = List.<BeforeLookup<? super Tuple2<String, String>>>of((event, membership) -> {
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> assertThrows(IllegalStateException.class, membership::refreshStatistics))
                        .get(5, TimeUnit.SECONDS);
            }
        });
        try (var fixture = new Fixture(true, BloomBackend.HEAP, BloomMetrics.DETAILED, event -> Decision.DROP, crossThread)) {
            fixture.open();
            fixture.send("a", "id");
            assertEquals(0, fixture.stat("refreshCount"));
        }
    }

    @Test
    void failedRecordsDoNotConsumeTheRefreshDeadline() throws Exception {
        try (var fixture = new Fixture(true, BloomBackend.HEAP, BloomMetrics.DETAILED,
                event -> { throw new IllegalStateException("callback failure"); }, List.of())) {
            fixture.open();
            fixture.send("a", "id");
            fixture.clock.nanos = INTERVAL;
            assertThrows(NullPointerException.class, () -> fixture.send("a", null));
            assertThrows(IllegalStateException.class, () -> fixture.send("a", "id"));
            assertEquals(0, fixture.stat("refreshCount"));
            fixture.send("b", "new");
            assertEquals(1, fixture.stat("refreshCount"));
            assertEquals(2, fixture.stat("bloomGroups"));
        }
    }

    @Test
    void reporterThreadsReadCachedValuesWhileTheOperatorMutatesAndSamples() throws Exception {
        try (var fixture = new Fixture(true, BloomBackend.HEAP, BloomMetrics.DETAILED, event -> Decision.DROP, List.of());
                var executor = Executors.newSingleThreadExecutor()) {
            fixture.open();
            var gauges = fixture.metrics.entrySet().stream().filter(entry -> entry.getKey().startsWith("presence.statistics."))
                    .map(entry -> (Gauge<?>) entry.getValue()).toList();
            var collection = executor.submit(() -> {
                for (int sample = 0; sample < 10_000; sample++) {
                    for (Gauge<?> gauge : gauges) assertTrue(((Number) gauge.getValue()).doubleValue() >= 0);
                }
            });
            for (int key = 0; key < 1_000; key++) {
                fixture.clock.nanos += INTERVAL;
                fixture.send("key-" + key, "id");
            }
            collection.get(10, TimeUnit.SECONDS);
            assertEquals(1_000, fixture.stat("bloomGroups"));
            assertEquals(1_000, fixture.stat("estimatedElements"), 10);
            // No background clock access, no scan during a scrape, and no catch-up loop after idleness.
            double count = fixture.stat("refreshCount");
            fixture.clock.nanos += INTERVAL * 100;
            for (Gauge<?> gauge : gauges) gauge.getValue();
            assertEquals(count, fixture.stat("refreshCount"));
            fixture.send("key-0", "id");
            assertEquals(count + 1, fixture.stat("refreshCount"));
        }
    }

    @ParameterizedTest
    @EnumSource(BloomMetrics.class)
    void managedRecoveryCanChangeMetricsWithoutLoadingUntouchedKeys(BloomMetrics previousMetrics) throws Exception {
        try (var first = new Fixture(true, BloomBackend.MANAGED_STATE, previousMetrics, event -> Decision.DROP, List.of());
                var restored = new Fixture(true, BloomBackend.MANAGED_STATE, BloomMetrics.DETAILED, event -> Decision.DROP, List.of())) {
            first.open();
            first.send("a", "id");
            first.send("b", "id");
            var checkpoint = first.harness.snapshot(1, 1);
            restored.harness.initializeState(checkpoint);
            restored.open();
            assertEquals(0, restored.stat("bloomGroups"));
            assertEquals(0, restored.counter("presence.stateLayerReads"));
            restored.clock.nanos = INTERVAL;
            restored.send("a", "id");
            assertEquals(0, restored.harness.extractOutputValues().size());
            assertEquals(1, restored.stat("bloomGroups"));
            assertEquals(1, restored.counter("presence.stateLayerReads"));
            restored.clock.nanos += INTERVAL;
            restored.send("a", "id");
            assertEquals(1, restored.counter("presence.stateLayerReads"), "sampling cannot read untouched key b");
        }
    }

    @Test
    void builderValidatesIntervalsAndSerializableConfiguration() throws Exception {
        var builder = BloomDeduplicator.<String>by(value -> value).onPossibleDuplicate(value -> Decision.DROP);
        assertThrows(NullPointerException.class, () -> builder.metrics(null));
        assertThrows(NullPointerException.class, () -> builder.metricsRefreshInterval(null));
        for (Duration invalid : new Duration[] {Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(Long.MAX_VALUE)}) {
            assertThrows(IllegalArgumentException.class, () -> builder.metricsRefreshInterval(invalid));
        }
        var value = builder.metrics(BloomMetrics.DETAILED).metricsRefreshInterval(Duration.ofSeconds(5)).build();
        byte[] serialized = InstantiationUtil.serializeObject(value);
        assertNotNull(InstantiationUtil.deserializeObject(serialized, getClass().getClassLoader()));
        assertTrue(serialized.length < 10_000, "configuration contains no sampler, live index, or SDK");
    }

    private static final class Clock implements LongSupplier {
        private final Thread owner = Thread.currentThread();
        private long nanos;
        @Override public long getAsLong() {
            assertSame(owner, Thread.currentThread(), "only the owning operator samples statistics");
            return nanos;
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Clock clock = new Clock();
        private final Map<String, Metric> metrics = new HashMap<>();
        private final HeapBloomProcessFunction<String, Tuple2<String, String>, Tuple2<String, String>> function;
        private final KeyedOneInputStreamOperatorTestHarness<String, Tuple2<String, String>, Tuple2<String, String>> harness;

        private Fixture(boolean scalable, BloomBackend backend, BloomMetrics level,
                PossibleDuplicateHandler<Tuple2<String, String>> handler,
                List<BeforeLookup<? super Tuple2<String, String>>> hooks) throws Exception {
            var registry = TestingMetricRegistry.builder().setRegisterConsumer((metric, name, group) -> {
                String identifier = group.getMetricIdentifier(name);
                int prefix = identifier.indexOf(".presence.");
                if (prefix >= 0) metrics.put(identifier.substring(prefix + 1), metric);
            }).build();
            var taskMetrics = TaskManagerMetricGroup.createTaskManagerMetricGroup(registry, "localhost", new ResourceID("test"))
                    .addJob(new JobID(), "presence-test").addTask(ExecutionAttemptID.randomId(), "presence-test");
            var environment = MockEnvironment.builder().setMetricGroup(taskMetrics).build();
            function = new HeapBloomProcessFunction<>(event -> event.f1, handler,
                    new BloomConfiguration(scalable, 1_000, 0.01, HashingStrategy.MURMUR3_128, backend),
                    Types.STRING, new CheckpointAwareness(false, "cluster-configured"),
                    HeapBloomProcessFunction.records(), hooks, level, INTERVAL);
            function.setMetricsClock(clock);
            OneInputStreamOperator<Tuple2<String, String>, Tuple2<String, String>> operator = backend == BloomBackend.HEAP
                    ? new KeyedProcessOperator<>(function) : new TieredBloomOperator<>(function);
            harness = new KeyedOneInputStreamOperatorTestHarness<>(operator, event -> event.f0, Types.STRING, environment);
        }

        private void open() throws Exception { harness.open(); }
        private void send(String key, String id) throws Exception { harness.processElement(new StreamRecord<>(Tuple2.of(key, id))); }
        private double stat(String name) { return gauge("presence.statistics." + name); }
        private double gauge(String name) { return ((Number) ((Gauge<?>) metrics.get(name)).getValue()).doubleValue(); }
        private long counter(String name) { return ((Counter) metrics.get(name)).getCount(); }
        @Override public void close() throws Exception { harness.close(); }
    }
}
