package io.transductive.presence.flink;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.state.CheckpointListener;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.PipelineOptions;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.configuration.RestartStrategyOptions;
import org.apache.flink.configuration.StateBackendOptions;
import org.apache.flink.configuration.CheckpointingOptions;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.source.legacy.RichParallelSourceFunction;
import org.apache.flink.test.junit5.MiniClusterExtension;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@SuppressWarnings({"try", "deprecation"})
class TieredRecoveryJobTest {
    @RegisterExtension
    static final MiniClusterExtension CLUSTER = new MiniClusterExtension(
            new MiniClusterResourceConfiguration.Builder().setNumberTaskManagers(2)
                    .setNumberSlotsPerTaskManager(1)
                    .setConfiguration(new Configuration().set(RestOptions.BIND_PORT, "0")).build());

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    @Timeout(90)
    void restoresMembershipAfterACompletedCheckpointAndTaskFailure(boolean scalable, boolean rocks) throws Exception {
        Assumptions.assumeTrue(!rocks || OS.LINUX.isCurrentOs(), "RocksDB JNI integration runs on Linux / act");
        try (var environment = StreamExecutionEnvironment.getExecutionEnvironment()) {
            environment.configure(new Configuration()
                    .set(PipelineOptions.GENERIC_TYPES, false)
                    .set(StateBackendOptions.STATE_BACKEND, rocks ? "rocksdb" : "hashmap")
                    .set(CheckpointingOptions.INCREMENTAL_CHECKPOINTS, true)
                    .set(RestartStrategyOptions.RESTART_STRATEGY, "fixed-delay")
                    .set(RestartStrategyOptions.RESTART_STRATEGY_FIXED_DELAY_ATTEMPTS, 1)
                    .set(RestartStrategyOptions.RESTART_STRATEGY_FIXED_DELAY_DELAY, Duration.ofMillis(10)));
            environment.setRuntimeMode(RuntimeExecutionMode.STREAMING);
            environment.setParallelism(2);
            environment.enableCheckpointing(50);
            environment.getCheckpointConfig().setCheckpointTimeout(30_000);
            environment.getConfig().enableObjectReuse();
            var source = environment.addSource(new FailAfterCheckpoint(), Types.POJO(Event.class))
                    .setParallelism(1).uid("recovering-source");
            var builder = BloomDeduplicator.<Event>by(Event::id).backend(BloomBackend.MANAGED_STATE)
                    .onPossibleDuplicate(event -> Decision.FORWARD_WITH_MEMBERSHIP);
            if (scalable) builder.scalable(1);
            else builder.fixed(1_000);
            var checked = builder.build().applyToWithMembership(source.keyBy(Event::tenant))
                    .setParallelism(2).uid("recovering-membership");
            var results = checked.filter(value -> value.value().afterRestore()).map(value -> {
                if (!value.maybePresent()) throw new IllegalStateException("Membership lost after checkpoint recovery");
                return value.value().tenant();
            }).returns(Types.STRING);
            var actual = new ArrayList<String>();
            try (var iterator = results.executeAndCollect()) { iterator.forEachRemaining(actual::add); }
            Set<String> expected = new HashSet<>();
            for (int i = 0; i < 16; i++) expected.add("tenant-" + i);
            assertEquals(16, actual.size());
            assertEquals(expected, new HashSet<>(actual));
        }
    }

    public record Event(String tenant, String id, boolean afterRestore) {}

    /** Test source deliberately fails only after its complete first batch is checkpointed. */
    private static final class FailAfterCheckpoint extends RichParallelSourceFunction<Event>
            implements CheckpointedFunction, CheckpointListener {
        private static final long serialVersionUID = 1L;
        private volatile boolean running = true;
        private volatile boolean completed;
        private volatile long firstReadyCheckpoint = Long.MAX_VALUE;
        private volatile int phase;
        private transient ListState<Integer> progress;

        @Override
        public void run(SourceContext<Event> context) throws Exception {
            if (phase == 0) {
                synchronized (context.getCheckpointLock()) {
                    for (int i = 0; i < 200; i++) {
                        for (int tenant = 0; tenant < 16; tenant++) {
                            context.collect(new Event("tenant-" + tenant, "member-" + i, false));
                        }
                    }
                    phase = 1;
                }
                long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                while (running && !completed && System.nanoTime() < deadline) Thread.sleep(10);
                if (running) throw new IllegalStateException(completed
                        ? "Intentional task failure after checkpoint completion" : "Test checkpoint did not complete");
            } else {
                synchronized (context.getCheckpointLock()) {
                    for (int tenant = 0; tenant < 16; tenant++) {
                        context.collect(new Event("tenant-" + tenant, "member-0", true));
                    }
                }
            }
        }

        @Override public void cancel() { running = false; }

        @Override
        public void initializeState(FunctionInitializationContext context) throws Exception {
            progress = context.getOperatorStateStore().getListState(new ListStateDescriptor<>("source-phase", Types.INT));
            if (context.isRestored()) for (int value : progress.get()) phase = value;
        }

        @Override
        public void snapshotState(FunctionSnapshotContext context) throws Exception {
            progress.update(java.util.List.of(phase));
            if (phase == 1) firstReadyCheckpoint = Math.min(firstReadyCheckpoint, context.getCheckpointId());
        }

        @Override
        public void notifyCheckpointComplete(long checkpointId) {
            if (checkpointId >= firstReadyCheckpoint) completed = true;
        }
    }
}
