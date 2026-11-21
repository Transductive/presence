package io.transductive.presence.flink;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.PipelineOptions;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.test.junit5.MiniClusterExtension;
import org.apache.flink.util.InstantiationUtil;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@SuppressWarnings("try")
class ResetPolicyMiniClusterTest {
    @RegisterExtension
    static final MiniClusterExtension CLUSTER = new MiniClusterExtension(
            new MiniClusterResourceConfiguration.Builder()
                    .setNumberTaskManagers(2)
                    .setNumberSlotsPerTaskManager(1)
                    .setConfiguration(new Configuration().set(RestOptions.BIND_PORT, "0"))
                    .build());

    @ParameterizedTest
    @CsvSource({"false,HEAP", "true,HEAP", "false,MANAGED_STATE", "true,MANAGED_STATE"})
    @Timeout(90)
    void serializesAndCombinesCannedAndCustomHooksAcrossParallelKeys(boolean scalable, BloomBackend backend) throws Exception {
        try (var environment = StreamExecutionEnvironment.getExecutionEnvironment()) {
            environment.configure(new Configuration().set(PipelineOptions.GENERIC_TYPES, false));
            environment.setRuntimeMode(RuntimeExecutionMode.STREAMING);
            environment.setParallelism(2);
            environment.getConfig().enableObjectReuse();
            var input = new ArrayList<Event>();
            var expected = new HashSet<MembershipResult<Event>>();
            for (int sequence = 0; sequence < 8; sequence++) {
                for (int tenant = 0; tenant < 32; tenant++) {
                    var event = new Event("tenant-" + tenant, "same-id", sequence, sequence == 4, sequence == 6);
                    input.add(event);
                    // Reset at 3 by size, 4 by tombstone, 6 by the caller's custom hook.
                    boolean maybePresent = sequence == 1 || sequence == 2 || sequence == 5 || sequence == 7;
                    expected.add(new MembershipResult<>(event, maybePresent));
                }
            }

            var builder = BloomDeduplicator.<Event>by(Event::id)
                    .backend(backend)
                    .beforeLookup(ResetPolicy.BATCH_RESET_SIZE.every(3))
                    .beforeLookup(ResetPolicy.ON_TOMBSTONE)
                    .beforeLookup((event, membership) -> {
                        if (event.manualReset()) membership.clearCurrentKey();
                    })
                    .onPossibleDuplicate(event -> Decision.FORWARD_WITH_MEMBERSHIP);
            if (scalable) builder.scalable(1);
            else builder.fixed(1);
            var built = builder.build();
            // An appended hook must not mutate an already-built operator's configuration.
            builder.beforeLookup((event, membership) -> { throw new AssertionError("builder snapshot changed"); });
            BloomDeduplicator<Event> restored = InstantiationUtil.deserializeObject(
                    InstantiationUtil.serializeObject(built), getClass().getClassLoader());

            var keyed = environment.fromData(input, Types.POJO(Event.class)).setParallelism(1).keyBy(Event::tenant);
            var output = restored.applyToWithMembership(keyed).setParallelism(2).uid("reset-policies");
            var tagged = output.map(new TagSubtask()).setParallelism(2);
            var shuffled = tagged.rebalance().map(value -> value).returns(tagged.getType())
                    .setParallelism(2).disableChaining();
            var actual = new ArrayList<MembershipResult<Event>>();
            var tasks = new HashSet<Integer>();
            try (var iterator = shuffled.executeAndCollect()) {
                iterator.forEachRemaining(value -> {
                    actual.add(value.f0);
                    tasks.add(value.f1);
                });
            }
            assertEquals(expected.size(), actual.size());
            assertEquals(expected, new HashSet<>(actual));
            assertEquals(Set.of(0, 1), tasks);
        }
    }

    public record Event(String tenant, String id, int sequence, boolean tombstone, boolean manualReset)
            implements Tombstone {
        @Override public boolean isTombstone() { return tombstone; }
    }

    private static final class TagSubtask extends RichMapFunction<MembershipResult<Event>,
            Tuple2<MembershipResult<Event>, Integer>> {
        private static final long serialVersionUID = 1L;

        @Override
        public Tuple2<MembershipResult<Event>, Integer> map(MembershipResult<Event> result) {
            return Tuple2.of(result, getRuntimeContext().getTaskInfo().getIndexOfThisSubtask());
        }
    }
}
