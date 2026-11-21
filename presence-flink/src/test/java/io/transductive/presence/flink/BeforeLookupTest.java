package io.transductive.presence.flink;

import static org.junit.jupiter.api.Assertions.*;

import io.transductive.presence.bloom.HashingStrategy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.metrics.SimpleCounter;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.operators.KeyedProcessOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@SuppressWarnings("try")
class BeforeLookupTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void countsAllEventsPerKeyAndResetsBeforeNPlusOneEvenWhenDuplicatesAreDropped(boolean scalable) throws Exception {
        var counts = new ArrayList<Long>();
        try (var harness = harness(scalable, event -> Decision.DROP, List.of(
                ResetPolicy.BATCH_RESET_SIZE.every(3),
                (event, membership) -> counts.add(membership.eventsSinceReset())))) {
            harness.open();
            for (String group : List.of("a", "b", "a", "a", "a", "b", "b", "b")) {
                harness.processElement(new StreamRecord<>(new Event(group, "id", false)));
            }
            assertEquals(List.of(0L, 0L, 1L, 2L, 0L, 1L, 2L, 0L), counts);
            assertEquals(List.of(result("a", false), result("b", false), result("a", false), result("b", false)),
                    harness.extractOutputValues());
        }
    }

    @Test
    void batchSizeOneMakesEveryEventTheFirstOfItsBatch() throws Exception {
        try (var harness = harness(true, event -> Decision.DROP, List.of(ResetPolicy.BATCH_RESET_SIZE.every(1)))) {
            harness.open();
            for (int i = 0; i < 4; i++) harness.processElement(new StreamRecord<>(new Event("a", "id", false)));
            assertEquals(List.of(result("a", false), result("a", false), result("a", false), result("a", false)),
                    harness.extractOutputValues());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tombstoneClearsTheWholeCurrentGroupThenTheMarkerIsRememberedAndForwarded(boolean scalable) throws Exception {
        try (var harness = harness(scalable, event -> Decision.FORWARD_WITH_MEMBERSHIP,
                List.of(ResetPolicy.ON_TOMBSTONE))) {
            harness.open();
            var events = List.of(new Event("a", "old-1", false), new Event("a", "old-2", false),
                    new Event("b", "old-1", false), new Event("a", "marker", true),
                    new Event("a", "old-1", false), new Event("a", "old-2", false),
                    new Event("b", "old-1", false), new Event("a", "marker", false));
            for (var event : events) harness.processElement(new StreamRecord<>(event, 123L));
            var output = harness.extractOutputValues();
            assertEquals(events, output.stream().map(MembershipResult::value).toList());
            assertEquals(List.of(false, false, false, false, false, false, true, true),
                    output.stream().map(MembershipResult::maybePresent).toList());
            assertEquals(new StreamRecord<>(output.get(3), 123L), harness.getOutput().toArray()[3]);
        }
    }

    @Test
    void customHooksRunInOrderAndCanResetTheCounterForLaterHooks() throws Exception {
        var counts = new ArrayList<Long>();
        try (var harness = harness(true, event -> Decision.FORWARD_WITH_MEMBERSHIP, List.of(
                (event, membership) -> {
                    counts.add(membership.eventsSinceReset());
                    if (event.isTombstone()) membership.clearCurrentKey();
                },
                (event, membership) -> counts.add(membership.eventsSinceReset())))) {
            harness.open();
            harness.processElement(new StreamRecord<>(new Event("a", "id", false)));
            harness.processElement(new StreamRecord<>(new Event("a", "id", true)));
            harness.processElement(new StreamRecord<>(new Event("a", "id", false)));
            assertEquals(List.of(0L, 0L, 1L, 0L, 1L, 1L), counts);
            assertEquals(List.of(false, false, true),
                    harness.extractOutputValues().stream().map(MembershipResult::maybePresent).toList());
        }
    }

    @Test
    void retainedControlsStayInvalidEvenDuringTheNextRecordsCallback() throws Exception {
        var saved = new AtomicReference<MembershipControl>();
        try (var harness = harness(true, event -> Decision.FORWARD, List.of((event, membership) -> {
            var previous = saved.getAndSet(membership);
            if (previous != null) {
                assertNotSame(previous, membership);
                assertThrows(IllegalStateException.class, previous::clearCurrentKey);
                assertThrows(IllegalStateException.class, previous::eventsSinceReset);
                assertEquals(1, membership.eventsSinceReset());
            }
        }))) {
            harness.open();
            harness.processElement(new StreamRecord<>(new Event("a", "id", false)));
            assertThrows(IllegalStateException.class, saved.get()::clearCurrentKey);
            harness.processElement(new StreamRecord<>(new Event("a", "id", false)));
            assertEquals(List.of(false, true),
                    harness.extractOutputValues().stream().map(MembershipResult::maybePresent).toList());
        }
        assertThrows(IllegalStateException.class, saved.get()::eventsSinceReset);
    }

    @Test
    void rejectsControlCallsFromAnotherThread() throws Exception {
        try (var harness = harness(true, event -> Decision.FORWARD, List.of((event, membership) -> {
            CompletableFuture.runAsync(() -> {
                assertThrows(IllegalStateException.class, membership::clearCurrentKey);
                assertThrows(IllegalStateException.class, membership::eventsSinceReset);
            }).get(5, TimeUnit.SECONDS);
        }))) {
            harness.open();
            harness.processElement(new StreamRecord<>(new Event("a", "id", false)));
            harness.processElement(new StreamRecord<>(new Event("a", "id", false)));
            assertEquals(List.of(false, true),
                    harness.extractOutputValues().stream().map(MembershipResult::maybePresent).toList());
        }
    }

    @Test
    void aFailedHookInvalidatesItsControlAndDoesNotForwardTheEvent() throws Exception {
        var saved = new AtomicReference<MembershipControl>();
        var failure = new Exception("reset unavailable");
        try (var harness = harness(true, event -> Decision.FORWARD, List.of((event, membership) -> {
            saved.set(membership);
            throw failure;
        }))) {
            harness.open();
            assertSame(failure, assertThrows(Exception.class,
                    () -> harness.processElement(new StreamRecord<>(new Event("a", "id", false)))));
            assertTrue(harness.extractOutputValues().isEmpty());
            assertThrows(IllegalStateException.class, saved.get()::clearCurrentKey);
        }
    }

    @Test
    void invalidIdsFailBeforeAnyResetHookRuns() throws Exception {
        var calls = new AtomicInteger();
        try (var harness = harness(true, event -> Decision.FORWARD, List.of((event, membership) -> {
            calls.incrementAndGet();
            membership.clearCurrentKey();
        }))) {
            harness.open();
            assertThrows(NullPointerException.class,
                    () -> harness.processElement(new StreamRecord<>(new Event("a", null, true))));
            assertEquals(0, calls.get());
            assertTrue(harness.extractOutputValues().isEmpty());
        }
    }

    @Test
    void heapRestoreStartsANewEventBatchAsWellAsEmptyMembership() throws Exception {
        var hooks = List.<BeforeLookup<? super Event>>of(ResetPolicy.BATCH_RESET_SIZE.every(3));
        try (var first = harness(true, event -> Decision.FORWARD, hooks);
                var restored = harness(true, event -> Decision.FORWARD, hooks)) {
            first.open();
            var record = new StreamRecord<>(new Event("a", "id", false));
            first.processElement(record);
            first.processElement(record);
            var snapshot = first.snapshot(1L, 1L);
            restored.initializeState(snapshot);
            restored.open();
            for (int i = 0; i < 4; i++) restored.processElement(record);
            assertEquals(List.of(false, true, true, false),
                    restored.extractOutputValues().stream().map(MembershipResult::maybePresent).toList());
        }
    }

    @Test
    void validatesPolicyParametersAndRequiresTombstoneOnBothPublicOutputModes() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ResetPolicy.BATCH_RESET_SIZE.every(0));
        assertThrows(IllegalArgumentException.class, () -> ResetPolicy.BATCH_RESET_SIZE.every(-1));
        assertNotNull(ResetPolicy.BATCH_RESET_SIZE.every(Long.MAX_VALUE));
        assertThrows(IllegalStateException.class, () -> ResetPolicy.ON_TOMBSTONE.every(3));
        var builder = BloomDeduplicator.<String>by(value -> value).onPossibleDuplicate(event -> Decision.DROP);
        assertThrows(NullPointerException.class, () -> builder.beforeLookup(null));
        assertThrows(IllegalArgumentException.class, () -> builder.beforeLookup(ResetPolicy.BATCH_RESET_SIZE));
        var deduplicator = builder.beforeLookup(ResetPolicy.ON_TOMBSTONE).build();
        try (var environment = StreamExecutionEnvironment.getExecutionEnvironment()) {
            var keyed = environment.fromData("id").keyBy(value -> "a");
            var plainFailure = assertThrows(IllegalArgumentException.class, () -> deduplicator.applyTo(keyed));
            assertTrue(plainFailure.getMessage().contains(Tombstone.class.getName()));
            assertTrue(plainFailure.getMessage().contains(String.class.getName()));
            assertThrows(IllegalArgumentException.class, () -> deduplicator.applyToWithMembership(keyed));
        }
    }

    @Test
    void resetMetricCountsRemovedGroupsAndTombstonePolicyAlsoChecksRuntimeValues() {
        var index = new HeapBloomIndex<String>(
                new BloomConfiguration(true, 1_000, 0.01, HashingStrategy.MURMUR3_128, BloomBackend.HEAP), key -> key);
        var resets = new SimpleCounter();
        try (var membership = new HeapMembershipControl<>(index, "a", resets)) {
            membership.clearCurrentKey();
            assertEquals(0, resets.getCount());
            index.observe("a", "id");
            assertThrows(IllegalArgumentException.class,
                    () -> ResetPolicy.ON_TOMBSTONE.beforeLookup("wrong type", membership));
            assertEquals(1, membership.eventsSinceReset());
            membership.clearCurrentKey();
            membership.clearCurrentKey();
            assertEquals(1, resets.getCount());
            assertEquals(0, membership.eventsSinceReset());
            assertEquals(0, index.groupCount());
        }
    }

    private static MembershipResult<Event> result(String key, boolean maybePresent) {
        return new MembershipResult<>(new Event(key, "id", false), maybePresent);
    }

    private static KeyedOneInputStreamOperatorTestHarness<String, Event, MembershipResult<Event>> harness(
            boolean scalable, PossibleDuplicateHandler<Event> handler, List<BeforeLookup<? super Event>> hooks)
            throws Exception {
        var function = new HeapBloomProcessFunction<String, Event, MembershipResult<Event>>(
                Event::id, handler,
                new BloomConfiguration(scalable, 1_000, 0.01, HashingStrategy.MURMUR3_128, BloomBackend.HEAP),
                Types.STRING, new CheckpointAwareness(false, "cluster-configured storage"),
                HeapBloomProcessFunction.withMembership(), hooks);
        var harness = new KeyedOneInputStreamOperatorTestHarness<>(new KeyedProcessOperator<>(function),
                Event::tenant, Types.STRING);
        harness.setup(MembershipResult.typeInformation(Types.POJO(Event.class))
                .createSerializer(harness.getExecutionConfig().getSerializerConfig()));
        return harness;
    }

    public record Event(String tenant, String id, boolean tombstone) implements Tombstone {
        @Override public boolean isTombstone() { return tombstone; }
    }
}
