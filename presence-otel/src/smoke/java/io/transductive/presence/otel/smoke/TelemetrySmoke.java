package io.transductive.presence.otel.smoke;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.BatchCallback;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.transductive.presence.bloom.BloomFilter;
import io.transductive.presence.bloom.ScalableBloomFilter;
import io.transductive.presence.otel.OtelBloomFilter;
import java.nio.file.Files;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.ToLongFunction;
import org.openjdk.jol.info.GraphLayout;
import org.openjdk.jol.vm.VM;

/** Bounded, paced telemetry exercise; deliberately not a throughput benchmark. */
public final class TelemetrySmoke {
    private TelemetrySmoke() {}

    @SuppressWarnings("try") // JVM metrics registration is scoped to SDK lifetime, including final flush.
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected minutes, run UUID, and result path");
        Duration duration = duration(args[0]);
        String runId = UUID.fromString(args[1]).toString();
        Path resultFile = Path.of(args[2]).toAbsolutePath();
        var exporter = OtlpHttpMetricExporter.builder()
                .setEndpoint("http://127.0.0.1:4318/v1/metrics")
                .setTimeout(Duration.ofSeconds(5)).build();
        var reader = PeriodicMetricReader.builder(exporter).setInterval(Duration.ofSeconds(1)).build();
        var resource = Resource.create(Attributes.builder()
                .put("service.name", "presence-smoke").put("service.instance.id", runId).build());
        long startedMillis = System.currentTimeMillis();
        Properties result;
        try (var provider = SdkMeterProvider.builder().setResource(resource).registerMetricReader(reader).build();
                var jvmMetrics = JvmMetrics.register(provider.get("io.transductive.presence.smoke.jvm"));
                var workload = new Workload(provider.get("io.transductive.presence.smoke"), TelemetrySmoke::heapSize)) {
            System.out.println("Presence telemetry smoke: " + duration.toMillis() / 1_000.0 + "s, run " + runId);
            System.out.println("N=1000, 1% target; fixed and scalable; ~200 cycles/s. This is not a benchmark.");
            long started = System.nanoTime();
            long nextProgress = started + Duration.ofSeconds(10).toNanos();
            long nextSample = started + Duration.ofSeconds(1).toNanos();
            while (System.nanoTime() - started < duration.toNanos()) {
                long batchStart = System.nanoTime();
                for (int i = 0; i < 20 && System.nanoTime() - started < duration.toNanos(); i++) workload.cycle();
                if (System.nanoTime() >= nextSample) {
                    workload.sample();
                    nextSample = System.nanoTime() + Duration.ofSeconds(1).toNanos();
                }
                if (System.nanoTime() >= nextProgress) {
                    System.out.printf("%,d cycles; scalable layers=%d; fixed/scalable false positives=%,d/%,d%n",
                            workload.cycles, workload.scalableCore.layerCount(), workload.fixedFalsePositives,
                            workload.scalableFalsePositives);
                    nextProgress = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                }
                long sleep = Math.min(Duration.ofMillis(100).toNanos() - (System.nanoTime() - batchStart),
                        duration.toNanos() - (System.nanoTime() - started));
                if (sleep > 0) TimeUnit.NANOSECONDS.sleep(sleep);
            }
            // Flush while footprint callbacks are still registered, before closing wrappers.
            workload.refreshStatistics();
            workload.sample();
            var flush = provider.forceFlush().join(15, TimeUnit.SECONDS);
            if (!flush.isSuccess()) throw new IllegalStateException("Final OTLP export failed or timed out");
            result = workload.result();
        }
        result.setProperty("runId", runId);
        result.setProperty("startedMillis", Long.toString(startedMillis));
        result.setProperty("finishedMillis", Long.toString(System.currentTimeMillis()));
        Files.createDirectories(resultFile.getParent());
        try (var output = Files.newOutputStream(resultFile)) { result.store(output, "Presence telemetry smoke"); }
        System.out.println("Workload complete. Expected puts=" + result.getProperty("puts")
                + ", lookups=" + result.getProperty("lookups") + ". Results: " + resultFile);
    }

    static Duration duration(String minutes) {
        double value = Double.parseDouble(minutes);
        if (!Double.isFinite(value) || value <= 0 || value > 1_440) {
            throw new IllegalArgumentException("minutes must be greater than zero and at most 1440");
        }
        long nanos = (long) (value * Duration.ofMinutes(1).toNanos());
        if (nanos < 1) throw new IllegalArgumentException("duration must be at least one nanosecond");
        return Duration.ofNanos(nanos);
    }

    private static long heapSize(Object root) {
        // Like the heap benchmark, require real shallow object sizing rather than JOL's layout fallback.
        var vm = VM.current();
        try {
            var field = vm.getClass().getDeclaredField("instrumentation");
            field.setAccessible(true);
            if (!(field.get(vm) instanceof java.lang.instrument.Instrumentation)) {
                throw new IllegalStateException("Run through telemetrySmoke, which enables JOL's Java 21 agent.");
            }
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot verify JOL 0.17 object sizing", error);
        }
        return GraphLayout.parseInstance(root).totalSize();
    }

    static final class Workload implements AutoCloseable {
        private static final Attributes FIXED = Attributes.builder().put("presence.filter.type", "fixed").build();
        private static final Attributes SCALABLE = Attributes.builder().put("presence.filter.type", "scalable").build();
        final ScalableBloomFilter<String> scalableCore = ScalableBloomFilter.forStrings(1_000, 0.01);
        final BloomFilter<String> fixedCore = BloomFilter.forStrings(1_000, 0.01);
        final OtelBloomFilter<String> fixed;
        final OtelBloomFilter<String> scalable;
        private final LongCounter absentQueries;
        private final LongCounter falsePositives;
        private final ToLongFunction<Object> heapSizer;
        private final PublishedSample published = new PublishedSample();
        private final BatchCallback callback;
        long cycles;
        long fixedFalsePositives;
        long scalableFalsePositives;

        Workload(Meter meter, ToLongFunction<Object> heapSizer) {
            this.heapSizer = heapSizer;
            // Override the library's 30s default for live smoke charts.
            fixed = OtelBloomFilter.wrap(fixedCore, meter, "smoke-fixed", Duration.ofSeconds(1));
            scalable = OtelBloomFilter.wrap(scalableCore, meter, "smoke-scalable", Duration.ofSeconds(1));
            absentQueries = meter.counterBuilder("presence.smoke.absent_queries").setUnit("{query}")
                    .setDescription("Queries of IDs never inserted by this workload.").build();
            falsePositives = meter.counterBuilder("presence.smoke.false_positives").setUnit("{query}")
                    .setDescription("Possible positives for known-absent IDs in this controlled workload.").build();
            for (var attributes : new Attributes[] {FIXED, SCALABLE}) {
                absentQueries.add(0, attributes);
                falsePositives.add(0, attributes);
            }
            for (int i = 0; i < 1_000; i++) {
                String value = "seed-" + i;
                fixed.put(value);
                scalable.put(value);
            }
            refreshStatistics();
            sample();
            callback = registerOverview(meter, published);
        }

        void sample() {
            var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            published.value = new Sample(1_000 + cycles, heapSizer.applyAsLong(fixedCore),
                    heapSizer.applyAsLong(scalableCore), heap.getUsed(), heap.getCommitted(), heap.getMax());
        }

        void refreshStatistics() {
            fixed.refreshStatistics();
            scalable.refreshStatistics();
        }

        private static BatchCallback registerOverview(Meter meter, PublishedSample published) {
            var distinct = meter.gaugeBuilder("presence.smoke.distinct_inputs").ofLongs().setUnit("{element}")
                    .setDescription("Exact distinct input IDs generated for each filter, including seed IDs.").buildObserver();
            var reachable = meter.gaugeBuilder("presence.smoke.filter.reachable_heap.bytes").ofLongs().setUnit("By")
                    .setDescription("JOL reachable core object graph, including headers and shared reachable objects; not retained heap.").buildObserver();
            var initialCapacity = meter.gaugeBuilder("presence.smoke.initial_capacity").ofLongs().setUnit("{element}").buildObserver();
            var target = meter.gaugeBuilder("presence.smoke.false_positive_probability.target").setUnit("1").buildObserver();
            var used = meter.gaugeBuilder("presence.smoke.jvm.heap.used").ofLongs().setUnit("By").buildObserver();
            var committed = meter.gaugeBuilder("presence.smoke.jvm.heap.committed").ofLongs().setUnit("By").buildObserver();
            var limit = meter.gaugeBuilder("presence.smoke.jvm.heap.limit").ofLongs().setUnit("By").buildObserver();
            return meter.batchCallback(() -> {
                Sample sample = published.value;
                for (var attributes : new Attributes[] {FIXED, SCALABLE}) {
                    distinct.record(sample.distinct(), attributes);
                    initialCapacity.record(1_000, attributes);
                    target.record(0.01, attributes);
                }
                reachable.record(sample.fixedHeap(), FIXED);
                reachable.record(sample.scalableHeap(), SCALABLE);
                used.record(sample.jvmUsed());
                committed.record(sample.jvmCommitted());
                if (sample.jvmMax() >= 0) limit.record(sample.jvmMax());
            }, distinct, reachable, initialCapacity, target, used, committed, limit);
        }

        private record Sample(long distinct, long fixedHeap, long scalableHeap, long jvmUsed, long jvmCommitted, long jvmMax) {}
        private static final class PublishedSample { private volatile Sample value; }

        void cycle() {
            // Disjoint prefixes provide ground truth without retaining a growing exact set.
            String inserted = "event-" + cycles;
            String absent = "absent-" + cycles;
            if (exercise(fixed, FIXED, inserted, absent)) fixedFalsePositives++;
            if (exercise(scalable, SCALABLE, inserted, absent)) scalableFalsePositives++;
            cycles++;
        }

        private boolean exercise(OtelBloomFilter<String> filter, Attributes attributes, String inserted, String absent) {
            filter.put(inserted);
            filter.put("seed-42"); // A repeated put still counts as an operation.
            if (!filter.mightContain(inserted)) throw new IllegalStateException("False negative for " + inserted);
            boolean positive = filter.mightContain(absent);
            absentQueries.add(1, attributes);
            if (positive) falsePositives.add(1, attributes);
            return positive;
        }

        Properties result() {
            var result = new Properties();
            result.setProperty("cycles", Long.toString(cycles));
            result.setProperty("puts", Long.toString(2_000 + 4 * cycles));
            result.setProperty("lookups", Long.toString(4 * cycles));
            result.setProperty("absentQueries", Long.toString(2 * cycles));
            result.setProperty("fixedFalsePositives", Long.toString(fixedFalsePositives));
            result.setProperty("scalableFalsePositives", Long.toString(scalableFalsePositives));
            result.setProperty("scalableLayers", Integer.toString(scalableCore.layerCount()));
            result.setProperty("distinctInputsPerFilter", Long.toString(1_000 + cycles));
            result.setProperty("scalableReachableHeapBytes", Long.toString(published.value.scalableHeap()));
            result.setProperty("fixedReachableHeapBytes", Long.toString(published.value.fixedHeap()));
            return result;
        }

        @Override public void close() { callback.close(); scalable.close(); fixed.close(); }
    }
}
