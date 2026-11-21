package io.transductive.presence.flink;

import static org.junit.jupiter.api.Assertions.*;

import io.transductive.presence.bloom.HashingStrategy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.streaming.api.operators.KeyedProcessOperator;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@SuppressWarnings("try")
class MembershipOutputTest {
    private static final TypeInformation<Tuple3<String, String, Integer>> INPUT_TYPE =
            Types.TUPLE(Types.STRING, Types.STRING, Types.INT);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reportsPreInsertionMembershipPerGroupAndPreservesPayloadsAndTime(boolean scalable) throws Exception {
        var calls = new AtomicInteger();
        try (var harness = harness(scalable, 1_000, 0.01, event -> {
            calls.incrementAndGet();
            return Decision.FORWARD_WITH_MEMBERSHIP;
        })) {
            harness.open();
            var first = Tuple3.of("tenant-a", "id", 1);
            var repeat = Tuple3.of("tenant-a", "id", 2);
            var otherGroup = Tuple3.of("tenant-b", "id", 3);
            harness.processElement(new StreamRecord<>(first, 123L));
            harness.processElement(new StreamRecord<>(repeat, 124L));
            harness.processElement(new StreamRecord<>(otherGroup, 125L));
            harness.processWatermark(new Watermark(200L));

            assertEquals(List.of(new MembershipResult<>(first, false), new MembershipResult<>(repeat, true),
                    new MembershipResult<>(otherGroup, false)), harness.extractOutputValues());
            assertEquals(List.of(new StreamRecord<>(new MembershipResult<>(first, false), 123L),
                    new StreamRecord<>(new MembershipResult<>(repeat, true), 124L),
                    new StreamRecord<>(new MembershipResult<>(otherGroup, false), 125L),
                    new Watermark(200L)), List.copyOf(harness.getOutput()));
            assertEquals(1, calls.get(), "only possible duplicates invoke the policy");
            assertEquals(Tuple3.of("tenant-a", "id", 1), first, "the input is not modified");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mixedDecisionsKeepEveryForwardedRecordInTheSameResultType(boolean scalable) throws Exception {
        try (var harness = harness(scalable, 1_000, 0.01, event -> switch (event.f2) {
            case 1 -> Decision.DROP;
            case 2 -> Decision.FORWARD;
            default -> Decision.FORWARD_WITH_MEMBERSHIP;
        })) {
            harness.open();
            for (int occurrence = 0; occurrence < 4; occurrence++) {
                harness.processElement(new StreamRecord<>(Tuple3.of("tenant", "id", occurrence)));
            }
            assertEquals(List.of(
                    new MembershipResult<>(Tuple3.of("tenant", "id", 0), false),
                    new MembershipResult<>(Tuple3.of("tenant", "id", 2), true),
                    new MembershipResult<>(Tuple3.of("tenant", "id", 3), true)), harness.extractOutputValues());
        }
    }

    @Test
    void trueCanRepresentAFalsePositiveForANewId() throws Exception {
        var calls = new AtomicInteger();
        try (var harness = harness(false, 1, 0.99, event -> {
            calls.incrementAndGet();
            return Decision.FORWARD_WITH_MEMBERSHIP;
        })) {
            harness.open();
            for (int id = 0; id < 100; id++) {
                harness.processElement(new StreamRecord<>(Tuple3.of("tenant", "unique-" + id, id)));
            }
            var output = harness.extractOutputValues();
            assertEquals(100, output.size(), "possible duplicates are forwarded, including false positives");
            assertFalse(output.getFirst().maybePresent());
            long possibleDuplicates = output.stream().filter(MembershipResult::maybePresent).count();
            assertTrue(possibleDuplicates > 0, "the intentionally saturated Bloom produces false positives");
            assertEquals(calls.get(), possibleDuplicates);
            for (int id = 0; id < 100; id++) {
                assertEquals(Tuple3.of("tenant", "unique-" + id, id), output.get(id).value());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void heapRestoreReportsAbsentAgain(boolean scalable) throws Exception {
        var event = Tuple3.of("tenant", "id", 1);
        try (var first = harness(scalable, 1_000, 0.01, value -> Decision.FORWARD_WITH_MEMBERSHIP);
                var restored = harness(scalable, 1_000, 0.01, value -> Decision.FORWARD_WITH_MEMBERSHIP)) {
            first.open();
            first.processElement(new StreamRecord<>(event));
            var snapshot = first.snapshot(1L, 1L);
            first.processElement(new StreamRecord<>(event));
            assertEquals(List.of(new MembershipResult<>(event, false), new MembershipResult<>(event, true)),
                    first.extractOutputValues());
            restored.initializeState(snapshot);
            restored.open();
            restored.processElement(new StreamRecord<>(event));
            assertEquals(List.of(new MembershipResult<>(event, false)), restored.extractOutputValues());
        }
    }

    private static KeyedOneInputStreamOperatorTestHarness<String, Tuple3<String, String, Integer>,
            MembershipResult<Tuple3<String, String, Integer>>> harness(boolean scalable, long capacity,
                    double probability, PossibleDuplicateHandler<Tuple3<String, String, Integer>> handler)
                    throws Exception {
        var function = new HeapBloomProcessFunction<String, Tuple3<String, String, Integer>,
                MembershipResult<Tuple3<String, String, Integer>>>(event -> event.f1, handler,
                new BloomConfiguration(scalable, capacity, probability, HashingStrategy.MURMUR3_128, BloomBackend.HEAP),
                Types.STRING, new CheckpointAwareness(false, "cluster-configured storage"),
                HeapBloomProcessFunction.withMembership());
        var harness = new KeyedOneInputStreamOperatorTestHarness<>(new KeyedProcessOperator<>(function),
                event -> event.f0, Types.STRING);
        harness.setup(MembershipResult.typeInformation(INPUT_TYPE)
                .createSerializer(harness.getExecutionConfig().getSerializerConfig()));
        return harness;
    }
}
