package io.transductive.presence.flink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.api.java.tuple.Tuple4;
import org.apache.flink.api.java.typeutils.PojoTypeInfo;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.PipelineOptions;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.test.junit5.MiniClusterExtension;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Runs the public API through serialization, network partitioning, and real task execution. */
@SuppressWarnings("try")
class BloomDeduplicatorMiniClusterTest {
    @RegisterExtension
    static final MiniClusterExtension CLUSTER = new MiniClusterExtension(
            new MiniClusterResourceConfiguration.Builder()
                    .setNumberTaskManagers(2)
                    .setNumberSlotsPerTaskManager(1)
                    .setConfiguration(new Configuration().set(RestOptions.BIND_PORT, "0"))
                    .build());

    @ParameterizedTest(name = "scalable={0}, possibleDuplicate={1}")
    @CsvSource({"false, DROP", "true, DROP", "false, FORWARD", "true, FORWARD"})
    @Timeout(90)
    void runsKeyedDuplicatePoliciesOnMiniCluster(boolean scalable, Decision decision) throws Exception {
        assertTrue(CLUSTER.isRunning());
        try (var environment = StreamExecutionEnvironment.getExecutionEnvironment()) {
            environment.setRuntimeMode(RuntimeExecutionMode.STREAMING);
            environment.setParallelism(2);
            environment.getConfig().enableObjectReuse();
            TypeInformation<Tuple3<String, String, Integer>> type = Types.TUPLE(
                    Types.STRING, Types.STRING, Types.INT);
            var input = new ArrayList<Tuple3<String, String, Integer>>();
            var expected = new HashSet<Tuple3<String, String, Integer>>();
            for (int tenant = 0; tenant < 32; tenant++) {
                for (int id = 0; id < 3; id++) {
                    for (int occurrence = 0; occurrence < 4; occurrence++) {
                        // The IDs recur across tenants; payloads distinguish each occurrence.
                        var event = Tuple3.of("tenant-" + tenant, "event-" + id, occurrence);
                        input.add(event);
                        if (decision == Decision.FORWARD || occurrence == 0) expected.add(event);
                    }
                }
            }

            // A single producer establishes which occurrence is first for each key.
            var keyed = environment.fromData(input, type).setParallelism(1).keyBy(event -> event.f0);
            var builder = BloomDeduplicator.<Tuple3<String, String, Integer>>by(event -> event.f1)
                    .backend(BloomBackend.HEAP)
                    .onPossibleDuplicate(event -> decision);
            if (scalable) builder.scalable(1_000);
            else builder.fixed(1_000);
            var output = builder.build().applyTo(keyed).uid("mini-cluster-membership").setParallelism(2);
            assertEquals(type, output.getType());

            // This chained map identifies the parallel task that produced each result.
            var tagged = output.map(new TagSubtask()).setParallelism(2);
            List<Tuple3<String, String, Integer>> actual = new ArrayList<>();
            Set<Integer> producingTasks = new HashSet<>();
            try (var results = tagged.executeAndCollect()) {
                results.forEachRemaining(event -> {
                    actual.add(Tuple3.of(event.f0, event.f1, event.f2));
                    producingTasks.add(event.f3);
                });
            }

            assertEquals(expected.size(), actual.size(), "no extra records or dropped expected occurrences");
            assertEquals(expected, new HashSet<>(actual), "tenant, event ID, and payload must be preserved");
            assertEquals(Set.of(0, 1), producingTasks, "both parallel membership tasks must produce output");
        }
    }

    @ParameterizedTest(name = "membership output: scalable={0}, possibleDuplicate={1}")
    @CsvSource({"false, FORWARD_WITH_MEMBERSHIP", "true, FORWARD_WITH_MEMBERSHIP",
            "false, DROP", "true, DROP", "false, FORWARD", "true, FORWARD"})
    @Timeout(90)
    void sendsMembershipResultsAcrossTheNetworkWithoutGenericSerialization(boolean scalable, Decision decision)
            throws Exception {
        try (var environment = StreamExecutionEnvironment.getExecutionEnvironment()) {
            environment.configure(new Configuration().set(PipelineOptions.GENERIC_TYPES, false));
            environment.setRuntimeMode(RuntimeExecutionMode.STREAMING);
            environment.setParallelism(2);
            environment.getConfig().enableObjectReuse();
            TypeInformation<Tuple3<String, String, Integer>> type = Types.TUPLE(
                    Types.STRING, Types.STRING, Types.INT);
            var input = new ArrayList<Tuple3<String, String, Integer>>();
            var expected = new HashSet<MembershipResult<Tuple3<String, String, Integer>>>();
            for (int tenant = 0; tenant < 32; tenant++) {
                for (int id = 0; id < 3; id++) {
                    for (int occurrence = 0; occurrence < 4; occurrence++) {
                        var event = Tuple3.of("tenant-" + tenant, "event-" + id, occurrence);
                        input.add(event);
                        if (decision != Decision.DROP || occurrence == 0) {
                            expected.add(new MembershipResult<>(event, occurrence != 0));
                        }
                    }
                }
            }
            var keyed = environment.fromData(input, type).setParallelism(1).keyBy(event -> event.f0);
            var builder = BloomDeduplicator.<Tuple3<String, String, Integer>>by(event -> event.f1)
                    .onPossibleDuplicate(event -> decision);
            if (scalable) builder.scalable(1_000);
            else builder.fixed(1_000);
            var output = builder.build().applyToWithMembership(keyed)
                    .uid("mini-cluster-membership-result").setParallelism(2);
            assertTrue(output.getType() instanceof PojoTypeInfo<?>);
            var resultType = (PojoTypeInfo<?>) output.getType();
            assertEquals(type, resultType.getTypeAt("value"));
            assertEquals(Types.BOOLEAN, resultType.getTypeAt("maybePresent"));

            // Keep the result nested inside the tag, then force network serialization.
            var tagged = output.map(new TagMembershipSubtask()).setParallelism(2);
            var shuffled = tagged.rebalance().map(event -> event).returns(tagged.getType())
                    .setParallelism(2).disableChaining();
            var actual = new ArrayList<MembershipResult<Tuple3<String, String, Integer>>>();
            var producingTasks = new HashSet<Integer>();
            try (var results = shuffled.executeAndCollect()) {
                results.forEachRemaining(taggedResult -> {
                    var result = taggedResult.f0;
                    var event = result.value();
                    actual.add(new MembershipResult<>(Tuple3.of(event.f0, event.f1, event.f2), result.maybePresent()));
                    producingTasks.add(taggedResult.f1);
                });
            }
            assertEquals(expected.size(), actual.size());
            assertEquals(expected, new HashSet<>(actual));
            assertEquals(Set.of(0, 1), producingTasks);
        }
    }

    private static final class TagMembershipSubtask extends RichMapFunction<
            MembershipResult<Tuple3<String, String, Integer>>,
            Tuple2<MembershipResult<Tuple3<String, String, Integer>>, Integer>> {
        private static final long serialVersionUID = 1L;

        @Override
        public Tuple2<MembershipResult<Tuple3<String, String, Integer>>, Integer>
                map(MembershipResult<Tuple3<String, String, Integer>> result) {
            return Tuple2.of(result,
                    getRuntimeContext().getTaskInfo().getIndexOfThisSubtask());
        }
    }

    private static final class TagSubtask extends RichMapFunction<
            Tuple3<String, String, Integer>, Tuple4<String, String, Integer, Integer>> {
        private static final long serialVersionUID = 1L;

        @Override
        public Tuple4<String, String, Integer, Integer> map(Tuple3<String, String, Integer> event) {
            return Tuple4.of(event.f0, event.f1, event.f2,
                    getRuntimeContext().getTaskInfo().getIndexOfThisSubtask());
        }
    }
}
