package io.transductive.presence.flink;

import static org.junit.jupiter.api.Assertions.*;

import io.transductive.presence.bloom.HashingStrategy;
import java.util.List;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.runtime.state.KeyGroupRangeAssignment;
import org.apache.flink.runtime.state.SharedStateRegistryImpl;
import org.apache.flink.state.rocksdb.EmbeddedRocksDBStateBackend;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.AbstractStreamOperatorTestHarness;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.AfterEach;

@SuppressWarnings("try")
class TieredBloomOperatorTest {
    private final SharedStateRegistryImpl sharedStates = new SharedStateRegistryImpl();

    @AfterEach
    void closeRegistry() { sharedStates.close(); }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void checkpointsAllKeysAndRestoresBeforeAndAfterReset(boolean scalable, boolean rocks) throws Exception {
        var hooks = List.<BeforeLookup<? super Event>>of(ResetPolicy.ON_TOMBSTONE);
        OperatorSubtaskState beforeReset;
        OperatorSubtaskState afterReset;
        try (var first = harness(scalable, rocks, 1, 0, hooks)) {
            first.open();
            for (int i = 0; i < 300; i++) {
                for (int key = 0; key < 8; key++) send(first, "tenant-" + key, "old-" + i, false);
            }
            Object keyBeforeSnapshot = first.getOperator().getCurrentKey();
            beforeReset = checkpoint(first, 1);
            assertEquals(keyBeforeSnapshot, first.getOperator().getCurrentKey());
            send(first, "tenant-0", "marker", true);
            send(first, "tenant-1", "after-checkpoint", false);
            afterReset = checkpoint(first, 2);
        }
        try (var old = harness(scalable, rocks, 1, 0, hooks);
                var latest = harness(scalable, rocks, 1, 0, hooks)) {
            old.initializeState(beforeReset);
            latest.initializeState(afterReset);
            old.open();
            latest.open();
            for (int key = 0; key < 8; key++) {
                for (int i = 0; i < 300; i++) {
                    assertTrue(send(old, "tenant-" + key, "old-" + i, false));
                    if (key != 0) assertTrue(send(latest, "tenant-" + key, "old-" + i, false));
                }
            }
            assertFalse(send(old, "tenant-1", "after-checkpoint", false), "later mutations must not alias earlier snapshots");
            assertTrue(send(latest, "tenant-1", "after-checkpoint", false));
            assertFalse(send(latest, "tenant-0", "old-0", false), "reset must not resurrect old layers");
            assertTrue(send(latest, "tenant-0", "marker", false));
            // Continue inserting after recovery to exercise restored occupancy and growth.
            for (int i = 0; i < 500; i++) send(latest, "tenant-0", "continued-" + i, false);
            var continued = checkpoint(latest, 3);
            try (var again = harness(scalable, rocks, 1, 0, hooks)) {
                again.initializeState(continued);
                again.open();
                for (int i = 0; i < 500; i++) assertTrue(send(again, "tenant-0", "continued-" + i, false));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void persistsEventCountersAndKeepsStateAfterAnAbortedCheckpoint(boolean rocks) throws Exception {
        var hooks = List.<BeforeLookup<? super Event>>of(ResetPolicy.BATCH_RESET_SIZE.every(3));
        try (var first = harness(true, rocks, 1, 0, hooks);
                var restored = harness(true, rocks, 1, 0, hooks)) {
            first.open();
            assertFalse(send(first, "a", "id", false));
            assertTrue(send(first, "a", "id", false));
            first.snapshot(1, 1);
            first.getOperator().notifyCheckpointAborted(1);
            // No new records: the backend must still retain everything flushed for checkpoint 1.
            var next = checkpoint(first, 2);
            restored.initializeState(next);
            restored.open();
            assertTrue(send(restored, "a", "id", false), "third event retains membership");
            assertFalse(send(restored, "a", "id", false), "fourth event starts a new batch after restore");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void redistributesKeyedLayersOnScaleOutAndBackIn(boolean rocks) throws Exception {
        OperatorSubtaskState original;
        try (var first = harness(true, rocks, 1, 0, List.of())) {
            first.open();
            for (int i = 0; i < 64; i++) send(first, "tenant-" + i, "original", false);
            original = checkpoint(first, 1);
        }
        OperatorSubtaskState left;
        OperatorSubtaskState right;
        try (var zero = harness(true, rocks, 2, 0, List.of());
                var one = harness(true, rocks, 2, 1, List.of())) {
            zero.initializeState(AbstractStreamOperatorTestHarness.repartitionOperatorState(original, 128, 1, 2, 0));
            one.initializeState(AbstractStreamOperatorTestHarness.repartitionOperatorState(original, 128, 1, 2, 1));
            zero.open();
            one.open();
            for (int i = 0; i < 64; i++) {
                String key = "tenant-" + i;
                var target = KeyGroupRangeAssignment.assignKeyToParallelOperator(key, 128, 2) == 0 ? zero : one;
                assertTrue(send(target, key, "original", false));
                assertFalse(send(target, key, "after-rescale", false));
            }
            left = checkpoint(zero, 2);
            right = checkpoint(one, 2);
        }
        var combined = AbstractStreamOperatorTestHarness.repackageState(left, right);
        try (var merged = harness(true, rocks, 1, 0, List.of())) {
            merged.initializeState(AbstractStreamOperatorTestHarness.repartitionOperatorState(combined, 128, 2, 1, 0));
            merged.open();
            for (int i = 0; i < 64; i++) {
                assertTrue(send(merged, "tenant-" + i, "original", false));
                assertTrue(send(merged, "tenant-" + i, "after-rescale", false));
            }
        }
    }

    private OperatorSubtaskState checkpoint(
            KeyedOneInputStreamOperatorTestHarness<String, Event, MembershipResult<Event>> harness, long id)
            throws Exception {
        var state = harness.snapshot(id, id);
        // The real checkpoint coordinator resolves incremental RocksDB shared-file handles.
        state.registerSharedStates(sharedStates, id);
        harness.notifyOfCompletedCheckpoint(id);
        return state;
    }

    private static boolean send(KeyedOneInputStreamOperatorTestHarness<String, Event, MembershipResult<Event>> harness,
            String key, String id, boolean tombstone) throws Exception {
        var event = new Event(key, id, tombstone);
        harness.processElement(new StreamRecord<>(event, 123));
        var records = harness.extractOutputValues();
        var result = records.getLast();
        assertEquals(new StreamRecord<>(result, 123), harness.getOutput().poll());
        assertEquals(event, result.value());
        return result.maybePresent();
    }

    private static KeyedOneInputStreamOperatorTestHarness<String, Event, MembershipResult<Event>> harness(
            boolean scalable, boolean rocks, int parallelism, int subtask, List<BeforeLookup<? super Event>> hooks)
            throws Exception {
        Assumptions.assumeTrue(!rocks || OS.LINUX.isCurrentOs(),
                "RocksDB JNI recovery is verified on Linux, including mise run ci:local / act");
        var function = new HeapBloomProcessFunction<String, Event, MembershipResult<Event>>(
                Event::id, event -> Decision.FORWARD_WITH_MEMBERSHIP,
                new BloomConfiguration(scalable, scalable ? 1 : 5_000, 0.0001,
                        HashingStrategy.MURMUR3_128, BloomBackend.MANAGED_STATE),
                Types.STRING, new CheckpointAwareness(true, "test storage"),
                HeapBloomProcessFunction.withMembership(), hooks);
        var harness = new KeyedOneInputStreamOperatorTestHarness<>(new TieredBloomOperator<>(function),
                Event::tenant, Types.STRING, 128, parallelism, subtask);
        if (rocks) harness.setStateBackend(new EmbeddedRocksDBStateBackend(true));
        harness.getExecutionConfig().enableObjectReuse();
        harness.setup(MembershipResult.typeInformation(Types.POJO(Event.class))
                .createSerializer(harness.getExecutionConfig().getSerializerConfig()));
        return harness;
    }

    public record Event(String tenant, String id, boolean tombstone) implements Tombstone {
        @Override public boolean isTombstone() { return tombstone; }
    }
}
