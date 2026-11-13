package io.transductive.presence.flink;

import static org.junit.jupiter.api.Assertions.*;

import io.transductive.presence.bloom.HashingStrategy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.api.operators.KeyedProcessOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.apache.flink.util.InstantiationUtil;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@SuppressWarnings("try")
class BloomDeduplicatorTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dropsRepeatsWithinEachGroupAndPreservesRecordsAndTimestamps(boolean scalable) throws Exception {
        var calls = new AtomicInteger();
        try (var harness = harness(scalable, 1_000, 0.01, event -> {
            calls.incrementAndGet();
            return Decision.DROP;
        })) {
            harness.open();
            var first = Tuple2.of("tenant-a", "event-1");
            harness.processElement(new StreamRecord<>(first, 123L));
            harness.processElement(new StreamRecord<>(Tuple2.of("tenant-a", "event-1"), 124L));
            harness.processElement(new StreamRecord<>(Tuple2.of("tenant-b", "event-1"), 125L));
            assertEquals(List.of(first, Tuple2.of("tenant-b", "event-1")), harness.extractOutputValues());
            assertEquals(1, calls.get());
            assertEquals(new StreamRecord<>(first, 123L), harness.getOutput().peek());
            assertEquals(Tuple2.of("tenant-a", "event-1"), first);
        }
    }

    @Test
    void forwardPolicyPreservesPossibleDuplicates() throws Exception {
        try (var harness = harness(true, 1_000, 0.01, event -> Decision.FORWARD)) {
            harness.open();
            var event = Tuple2.of("tenant", "id");
            harness.processElement(new StreamRecord<>(event));
            harness.processElement(new StreamRecord<>(event));
            assertEquals(List.of(event, event), harness.extractOutputValues());
        }
    }

    @Test
    void falsePositiveAlsoInvokesThePolicy() throws Exception {
        var seen = new ArrayList<String>();
        try (var harness = harness(false, 1, 0.99, event -> {
            seen.add(event.f1);
            return Decision.FORWARD;
        })) {
            harness.open();
            harness.processElement(new StreamRecord<>(Tuple2.of("tenant", "first")));
            for (int i = 0; i < 100; i++) {
                harness.processElement(new StreamRecord<>(Tuple2.of("tenant", "new-" + i)));
            }
            assertFalse(seen.isEmpty(), "an intentionally saturated Bloom must produce possible duplicates");
            assertTrue(seen.stream().allMatch(id -> id.startsWith("new-")));
            assertEquals(101, harness.extractOutputValues().size());
        }
    }

    @Test
    void scalableGrowthDoesNotForgetPreviouslyObservedIds() throws Exception {
        try (var harness = harness(true, 1, 0.01, event -> Decision.DROP)) {
            harness.open();
            for (int i = 0; i < 5_000; i++) {
                harness.processElement(new StreamRecord<>(Tuple2.of("tenant", "id-" + i)));
            }
            int initialOutputs = harness.extractOutputValues().size();
            assertTrue(initialOutputs > 4_800);
            for (int i = 0; i < 5_000; i++) {
                harness.processElement(new StreamRecord<>(Tuple2.of("tenant", "id-" + i)));
            }
            assertEquals(initialOutputs, harness.extractOutputValues().size());
        }
    }

    @Test
    void aCheckpointDoesNotPersistHeapMembership() throws Exception {
        var event = Tuple2.of("tenant", "event");
        try (var first = harness(true, 1_000, 0.01, value -> Decision.DROP);
                var restored = harness(true, 1_000, 0.01, value -> Decision.DROP)) {
            first.open();
            first.processElement(new StreamRecord<>(event));
            var snapshot = first.snapshot(1L, 1L);
            first.processElement(new StreamRecord<>(event));
            assertEquals(1, first.extractOutputValues().size());
            restored.initializeState(snapshot);
            restored.open();
            restored.processElement(new StreamRecord<>(event));
            assertEquals(List.of(event), restored.extractOutputValues());
        }
    }

    @Test
    void nullIdsAndInvalidDecisionsFailRatherThanSilentlyDropping() throws Exception {
        try (var harness = harness(true, 1_000, 0.01, event -> null)) {
            harness.open();
            assertThrows(NullPointerException.class,
                    () -> harness.processElement(new StreamRecord<>(Tuple2.of("tenant", null))));
            var event = Tuple2.of("tenant", "valid");
            harness.processElement(new StreamRecord<>(event));
            assertThrows(NullPointerException.class,
                    () -> harness.processElement(new StreamRecord<>(event)));
            assertEquals(List.of(event), harness.extractOutputValues());
        }
    }

    @Test
    void callbackFailuresPropagate() throws Exception {
        var failure = new Exception("policy unavailable");
        try (var harness = harness(true, 1_000, 0.01, event -> { throw failure; })) {
            harness.open();
            var event = Tuple2.of("tenant", "id");
            harness.processElement(new StreamRecord<>(event));
            assertSame(failure, assertThrows(Exception.class,
                    () -> harness.processElement(new StreamRecord<>(event))));
        }
    }

    @Test
    void configurationSerializesWithoutSerializingLiveBlooms() throws Exception {
        var original = BloomDeduplicator.<String>by(value -> value)
                .scalable().backend(BloomBackend.HEAP)
                .onPossibleDuplicate(value -> Decision.DROP).build();
        byte[] serialized = InstantiationUtil.serializeObject(original);
        BloomDeduplicator<String> restored = InstantiationUtil.deserializeObject(serialized, getClass().getClassLoader());
        assertNotNull(restored);
        assertTrue(serialized.length < 10_000);
    }

    @Test
    void checkpointMismatchWarnsOnceAtStartupAndContinues() throws Exception {
        try (var warnings = new WarningCapture();
                var harness = harness(true, 1_000, 0.01, event -> Decision.DROP,
                        new CheckpointAwareness(true, "s3 checkpoint storage"))) {
            harness.open();
            assertEquals(1, warnings.messages.size());
            assertTrue(warnings.messages.getFirst().contains("s3 checkpoint storage"));
            harness.snapshot(1, 1);
            harness.snapshot(2, 2);
            harness.processElement(new StreamRecord<>(Tuple2.of("tenant", "id")));
            assertEquals(1, warnings.messages.size());
            assertEquals(1, harness.extractOutputValues().size());
        }
    }

    @Test
    void laterCheckpointingAndRestoreAlsoWarn() throws Exception {
        try (var warnings = new WarningCapture();
                var first = harness(true, 1_000, 0.01, event -> Decision.DROP);
                var restored = harness(true, 1_000, 0.01, event -> Decision.DROP)) {
            first.open();
            assertTrue(warnings.messages.isEmpty());
            var snapshot = first.snapshot(1, 1);
            assertEquals(1, warnings.messages.size());
            restored.initializeState(snapshot);
            restored.open();
            assertEquals(2, warnings.messages.size());
        }
    }

    @Test
    void validatesConfigurationBeforeAttachingAnOperator() {
        assertThrows(NullPointerException.class, () -> BloomDeduplicator.by(null));
        assertThrows(NullPointerException.class, () -> BloomDeduplicator.<String>by(value -> value).build());
        var builder = BloomDeduplicator.<String>by(value -> value);
        assertThrows(IllegalArgumentException.class, () -> builder.fixed(0));
        assertThrows(IllegalArgumentException.class, () -> builder.scalable(-1));
        for (double probability : new double[] {0, 1, -0.1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> builder.falsePositiveProbability(probability));
        }
        assertThrows(NullPointerException.class, () -> builder.backend(null));
        assertThrows(NullPointerException.class, () -> builder.hashingStrategy(null));
        assertThrows(NullPointerException.class, () -> builder.onPossibleDuplicate(null));
    }

    private static KeyedOneInputStreamOperatorTestHarness<String, Tuple2<String, String>, Tuple2<String, String>>
            harness(boolean scalable, long capacity, double probability,
                    PossibleDuplicateHandler<Tuple2<String, String>> handler) throws Exception {
        return harness(scalable, capacity, probability, handler,
                new CheckpointAwareness(false, "cluster-configured storage"));
    }

    private static KeyedOneInputStreamOperatorTestHarness<String, Tuple2<String, String>, Tuple2<String, String>>
            harness(boolean scalable, long capacity, double probability,
                    PossibleDuplicateHandler<Tuple2<String, String>> handler,
                    CheckpointAwareness awareness) throws Exception {
        var function = new HeapBloomProcessFunction<String, Tuple2<String, String>>(event -> event.f1, handler,
                new BloomConfiguration(scalable, capacity, probability, HashingStrategy.MURMUR3_128, BloomBackend.HEAP),
                Types.STRING, awareness);
        return new KeyedOneInputStreamOperatorTestHarness<>(new KeyedProcessOperator<>(function),
                event -> event.f0, Types.STRING);
    }

    private static final class WarningCapture extends AbstractAppender implements AutoCloseable {
        private final List<String> messages = new ArrayList<>();
        private final Logger logger = (Logger) LogManager.getLogger(HeapBloomProcessFunction.class);
        private final Level previousLevel = logger.getLevel();

        WarningCapture() {
            super("presence-warning-test", null, PatternLayout.createDefaultLayout(), false, Property.EMPTY_ARRAY);
            start();
            logger.addAppender(this);
            logger.setLevel(Level.WARN);
        }

        @Override public void append(LogEvent event) { messages.add(event.getMessage().getFormattedMessage()); }
        @Override public void close() {
            logger.removeAppender(this);
            logger.setLevel(previousLevel);
            stop();
        }
    }
}
