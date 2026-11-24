package io.transductive.presence.flink.smoke;

import io.transductive.presence.flink.BloomBackend;
import io.transductive.presence.flink.BloomDeduplicator;
import io.transductive.presence.flink.BloomMetrics;
import io.transductive.presence.flink.Decision;
import io.transductive.presence.flink.MembershipResult;
import io.transductive.presence.flink.ResetPolicy;
import io.transductive.presence.flink.Tombstone;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.configuration.CheckpointingOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.configuration.RestartStrategyOptions;
import org.apache.flink.configuration.StateBackendOptions;
import org.apache.flink.runtime.minicluster.MiniCluster;
import org.apache.flink.runtime.minicluster.MiniClusterConfiguration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.legacy.RichSinkFunction;
import org.apache.flink.streaming.api.functions.source.legacy.RichParallelSourceFunction;

/** A paced, bounded job for observing real Flink metrics; not a throughput or recovery benchmark. */
@SuppressWarnings({"deprecation", "try"}) // Small local-only generator/sink; production library uses KeyedProcessFunction.
public final class FlinkTelemetrySmoke {
    static final int KEYS = 16;
    static final int CAPACITY = 1_000;
    static final String[] BRANCHES = {"fixed-heap", "scalable-heap", "fixed-managed", "scalable-managed"};

    private FlinkTelemetrySmoke() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 && args.length != 5) throw new IllegalArgumentException("Expected minutes, run UUID, result path, and optional backend/OTLP endpoint");
        Duration duration = duration(args[0]);
        String runId = UUID.fromString(args[1]).toString();
        Path resultFile = Path.of(args[2]).toAbsolutePath();
        Files.createDirectories(resultFile.getParent());
        String backend = args.length == 5 ? args[3] : "hashmap";
        String endpoint = args.length == 5 ? args[4] : "http://127.0.0.1:4317";
        var config = configuration(runId, resultFile.getParent().resolve("checkpoints"), backend, endpoint);
        var summary = new Properties();
        summary.setProperty("runId", runId);
        summary.setProperty("stateBackend", backend);
        summary.setProperty("startedMillis", Long.toString(System.currentTimeMillis()));
        System.out.println("Presence Flink smoke: " + duration.toSeconds() + "s; run " + runId);
        System.out.println("1 embedded JobManager / 1 TaskManager / 2 slots; 4 branches at parallelism 2.");
        System.out.println("16 keys per branch, capacity 1000 per key, 1% target. Seed 1000/key, then ~200 fresh/repeat pairs/s.");
        System.out.println("Detailed statistics and OTLP export: 1s; " + backend + " checkpoints: 2s. This is not a benchmark.");
        var cluster = new MiniCluster(new MiniClusterConfiguration.Builder().setConfiguration(config)
                .setNumTaskManagers(1).setNumSlotsPerTaskManager(2).build());
        try (var environment = StreamExecutionEnvironment.getExecutionEnvironment(config)) {
            cluster.start();
            System.out.println("Local Flink REST endpoint: " + cluster.getRestAddress().get(30, TimeUnit.SECONDS));
            environment.setRuntimeMode(RuntimeExecutionMode.STREAMING);
            environment.setParallelism(2);
            environment.enableCheckpointing(2_000);
            environment.getCheckpointConfig().setCheckpointTimeout(30_000);
            var events = environment.addSource(new PacedSource(duration.toNanos()), Types.POJO(Event.class))
                    .name("paced-input").uid("paced-input").setParallelism(1);
            for (String branch : BRANCHES) {
                var builder = BloomDeduplicator.<Event>by(Event::id)
                        .backend(branch.endsWith("managed") ? BloomBackend.MANAGED_STATE : BloomBackend.HEAP)
                        .metrics(BloomMetrics.DETAILED).metricsRefreshInterval(Duration.ofSeconds(1))
                        .beforeLookup(ResetPolicy.ON_TOMBSTONE)
                        .beforeLookup((event, membership) -> {
                            if (event.kind() == Kind.SAMPLE) membership.refreshStatistics();
                        })
                        .onPossibleDuplicate(FlinkTelemetrySmoke::decision);
                if (branch.startsWith("fixed")) builder.fixed(CAPACITY);
                else builder.scalable(CAPACITY);
                builder.build().applyToWithMembership(events.keyBy(Event::tenant))
                        .name(branch).uid(branch).setParallelism(2)
                        .addSink(new VerifySink(branch)).name("verify-" + branch).uid("verify-" + branch).setParallelism(2);
            }
            var graph = environment.getStreamGraph();
            graph.setJobName("Presence Flink Smoke " + runId);
            var job = graph.getJobGraph();
            cluster.submitJob(job).get(30, TimeUnit.SECONDS);
            var execution = cluster.requestJobResult(job.getJobID())
                    .get(duration.toSeconds() + 120, TimeUnit.SECONDS).toJobExecutionResult(FlinkTelemetrySmoke.class.getClassLoader());
            long cycles = execution.getAccumulatorResult("smoke.cycles");
            summary.setProperty("jobId", job.getJobID().toString());
            summary.setProperty("cycles", Long.toString(cycles));
            summary.setProperty("resetMarkers", execution.<Long>getAccumulatorResult("smoke.resets").toString());
            for (String branch : BRANCHES) {
                long fresh = execution.getAccumulatorResult(branch + ".fresh");
                long repeats = execution.getAccumulatorResult(branch + ".repeats");
                long falsePositives = execution.getAccumulatorResult(branch + ".falsePositives");
                if (fresh != (long) KEYS * CAPACITY + cycles || repeats != (cycles + 3) / 4) {
                    throw new IllegalStateException("Unexpected membership output counts for " + branch);
                }
                summary.setProperty(branch + ".fresh", Long.toString(fresh));
                summary.setProperty(branch + ".forwardedRepeats", Long.toString(repeats));
                summary.setProperty(branch + ".falsePositives", Long.toString(falsePositives));
                System.out.println(branch + ": " + fresh + " fresh, " + repeats + " forwarded repeats; " + falsePositives + " known-absent positives.");
            }
        } finally {
            cluster.closeAsync().get(60, TimeUnit.SECONDS);
        }
        summary.setProperty("finishedMillis", Long.toString(System.currentTimeMillis()));
        try (var output = Files.newOutputStream(resultFile)) { summary.store(output, "Presence Flink smoke (local only)"); }
        System.out.println("Flink job completed; MiniCluster stopped. Grafana history stays in the telemetry cluster.");
    }

    static Configuration configuration(String runId, Path checkpoints) {
        return configuration(runId, checkpoints, "hashmap", "http://127.0.0.1:4317");
    }

    static Configuration configuration(String runId, Path checkpoints, String backend, String endpoint) {
        if (!List.of("hashmap", "rocksdb").contains(backend)) throw new IllegalArgumentException("Unknown state backend: " + backend);
        var config = new Configuration();
        config.set(RestOptions.BIND_ADDRESS, "127.0.0.1");
        config.set(RestOptions.ADDRESS, "127.0.0.1");
        config.set(RestOptions.BIND_PORT, "0");
        config.set(RestartStrategyOptions.RESTART_STRATEGY, "none");
        config.set(StateBackendOptions.STATE_BACKEND, backend);
        config.set(CheckpointingOptions.CHECKPOINTS_DIRECTORY, checkpoints.toUri().toString());
        if (backend.equals("rocksdb")) {
            config.set(CheckpointingOptions.INCREMENTAL_CHECKPOINTS, true);
            config.setString("taskmanager.memory.managed.size", "256 mb");
            config.setString("state.backend.rocksdb.memory.managed", "true");
            // Local diagnostics only: native property/statistics collection has overhead.
            // Column-family labels preserve stable metric names across the three state descriptors.
            for (String metric : List.of("column-family-as-variable", "size-all-mem-tables",
                    "block-cache-usage", "block-cache-capacity", "block-cache-pinned-usage",
                    "estimate-table-readers-mem", "live-sst-files-size", "estimate-num-keys",
                    "mem-table-flush-pending", "num-running-flushes", "compaction-pending",
                    "num-running-compactions", "estimate-pending-compaction-bytes", "background-errors",
                    "is-write-stopped", "bytes-read", "bytes-written", "block-cache-hit", "block-cache-miss",
                    "compaction-read-bytes", "compaction-write-bytes", "stall-micros")) {
                config.setString("state.backend.rocksdb.metrics." + metric, "true");
            }
        }
        config.setString("metrics.reporters", "otel");
        config.setString("metrics.reporter.otel.factory.class", "org.apache.flink.metrics.otel.OpenTelemetryMetricReporterFactory");
        config.setString("metrics.reporter.otel.exporter.endpoint", endpoint);
        config.setString("metrics.reporter.otel.exporter.timeout", "5s");
        config.setString("metrics.reporter.otel.service.name", "presence-flink-smoke");
        config.setString("metrics.reporter.otel.scope.variables.additional", "presence_run_id:" + runId + ",presence_state_backend:" + backend);
        config.setString("metrics.reporter.otel.interval", "1 SECONDS");
        return config;
    }

    static Duration duration(String minutes) {
        double value = Double.parseDouble(minutes);
        if (!Double.isFinite(value) || value <= 0 || value > 1_440) throw new IllegalArgumentException("minutes must be positive and at most 1440");
        long nanos = (long) (value * Duration.ofMinutes(1).toNanos());
        if (nanos < 1) throw new IllegalArgumentException("duration must be at least one nanosecond");
        return Duration.ofNanos(nanos);
    }

    static Decision decision(Event event) {
        return event.kind() == Kind.REPEAT && event.sequence() % 4 != 0 ? Decision.DROP : Decision.FORWARD_WITH_MEMBERSHIP;
    }

    static Event fresh(long cycle) { return new Event("tenant-" + cycle % KEYS, "event-" + cycle, Kind.NEW, cycle); }
    static Event repeat(Event fresh) { return new Event(fresh.tenant(), fresh.id(), Kind.REPEAT, fresh.sequence()); }

    public enum Kind { SEED, NEW, REPEAT, RESET, SAMPLE }
    public record Event(String tenant, String id, Kind kind, long sequence) implements Tombstone {
        @Override public boolean isTombstone() { return kind == Kind.RESET; }
    }

    private static final class PacedSource extends RichParallelSourceFunction<Event> {
        private static final long serialVersionUID = 1L;
        private final long durationNanos;
        private volatile boolean running = true;
        private PacedSource(long durationNanos) { this.durationNanos = durationNanos; }

        @Override public void run(SourceContext<Event> context) throws Exception {
            var cycles = getRuntimeContext().getLongCounter("smoke.cycles");
            var resets = getRuntimeContext().getLongCounter("smoke.resets");
            for (int key = 0; key < KEYS && running; key++) {
                synchronized (context.getCheckpointLock()) {
                    for (int id = 0; id < CAPACITY; id++) context.collect(new Event("tenant-" + key, "seed-" + id, Kind.SEED, id));
                }
            }
            long started = System.nanoTime();
            long nextProgress = Duration.ofSeconds(10).toNanos();
            while (running && System.nanoTime() - started < durationNanos) {
                long batchStarted = System.nanoTime();
                for (int i = 0; i < 20 && running && System.nanoTime() - started < durationNanos; i++) {
                    synchronized (context.getCheckpointLock()) {
                        long cycle = cycles.getLocalValue();
                        Event event = fresh(cycle);
                        context.collect(event);
                        context.collect(repeat(event));
                        cycles.add(1);
                        if (cycles.getLocalValue() % 3_000 == 0) {
                            long reset = resets.getLocalValue();
                            context.collect(new Event("tenant-" + reset % KEYS, "reset-" + reset, Kind.RESET, cycle));
                            resets.add(1);
                        }
                    }
                }
                long elapsed = System.nanoTime() - started;
                if (elapsed >= nextProgress) {
                    System.out.println("Flink smoke: " + cycles.getLocalValue() + " source cycles; " + resets.getLocalValue() + " reset markers.");
                    nextProgress = elapsed + Duration.ofSeconds(10).toNanos();
                }
                long wait = Math.min(Duration.ofMillis(100).toNanos() - (System.nanoTime() - batchStarted), durationNanos - elapsed);
                if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
            }
            if (running) {
                synchronized (context.getCheckpointLock()) {
                    for (int key = 0; key < KEYS; key++) context.collect(new Event("tenant-" + key, "sample", Kind.SAMPLE, 0));
                }
                // Keep metrics registered for a few reporter/collector ticks before bounded-job teardown.
                for (int i = 0; i < 30 && running; i++) Thread.sleep(100);
            }
        }
        @Override public void cancel() { running = false; }
    }

    private static final class VerifySink extends RichSinkFunction<MembershipResult<Event>> {
        private static final long serialVersionUID = 1L;
        private final String branch;
        private VerifySink(String branch) { this.branch = branch; }

        @Override public void open(OpenContext context) {
            getRuntimeContext().getLongCounter(branch + ".fresh");
            getRuntimeContext().getLongCounter(branch + ".falsePositives");
            getRuntimeContext().getLongCounter(branch + ".repeats");
        }

        @Override public void invoke(MembershipResult<Event> result, Context context) {
            Event event = result.value();
            if (event.kind() == Kind.NEW || event.kind() == Kind.SEED) {
                getRuntimeContext().getLongCounter(branch + ".fresh").add(1);
                getRuntimeContext().getLongCounter(branch + ".falsePositives").add(result.maybePresent() ? 1 : 0);
            } else if (event.kind() == Kind.REPEAT) {
                if (!result.maybePresent() || decision(event) == Decision.DROP) {
                    throw new IllegalStateException("Unexpected repeated-ID result in " + branch);
                }
                getRuntimeContext().getLongCounter(branch + ".repeats").add(1);
            }
        }
    }
}
