package io.transductive.presence.benchmarks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.profile.JavaFlightRecorderProfiler;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/** Runs the JMH harness and produces local, offline review artifacts. */
public final class BenchmarkRunner {
    private BenchmarkRunner() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 6 && args.length != 10) {
            throw new IllegalArgumentException("Expected mode, output directory, sizes, hits, include regex, input kinds, "
                    + "and optionally working sets, query modes, downstream tokens, strategies");
        }
        var settings = args.length == 6
                ? BenchmarkSettings.parse(args[0], args[2], args[3], args[4], args[5])
                : BenchmarkSettings.parse(args[0], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9]);
        String maximumHeap = maximumHeap(System.getProperty("presence.benchmark.maxHeap", "512m"));
        String runName = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-" + settings.mode() + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path directory = Path.of(args[1]).toAbsolutePath().resolve(runName);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("run.txt"), settings + System.lineSeparator() + BenchmarkEnvironment.describe()
                + "Fork JVM arguments: -Xms256m -Xmx" + maximumHeap + System.lineSeparator());

        System.out.println("Presence benchmarks: " + settings);
        System.out.println("Results directory: " + directory);
        if (settings.mode().equals("allocation")) {
            AllocationCheck.run(directory);
        }
        if (settings.smoke()) {
            System.out.println("SMOKE RUN: checks the harness; do not use these scores for performance conclusions.");
        }
        var options = new OptionsBuilder()
                .include(settings.include())
                .param("N", settings.sizes().toArray(String[]::new))
                .param("hitPercent", settings.hits().toArray(String[]::new))
                .param("inputKind", settings.inputs().toArray(String[]::new))
                .param("workingSet", settings.workingSets().toArray(String[]::new))
                .param("queryMode", settings.queryModes().toArray(String[]::new))
                .param("downstreamTokens", settings.downstreamTokens().toArray(String[]::new))
                .param("strategy", settings.strategies().toArray(String[]::new))
                .threads(1)
                .forks(settings.smoke() || settings.profile() ? 1 : 2)
                .warmupIterations(settings.smoke() ? 1 : 3)
                .measurementIterations(settings.smoke() ? 1 : 5)
                .warmupTime(settings.smoke() ? TimeValue.milliseconds(100) : TimeValue.seconds(1))
                .measurementTime(settings.smoke() ? TimeValue.milliseconds(200) : TimeValue.seconds(1))
                .jvmArgs("-Xms256m", "-Xmx" + maximumHeap)
                .shouldFailOnError(true)
                .resultFormat(ResultFormatType.JSON)
                .result(directory.resolve("results.json").toString())
                .addProfiler(GCProfiler.class);
        if (settings.profile()) {
            // One fork prevents JMH's JFR profiler from overwriting the previous fork's recording.
            options.addProfiler(JavaFlightRecorderProfiler.class,
                    "dir=" + directory.resolve("jfr") + ";configName=profile;stackDepth=128");
        }
        var results = new Runner(options.build()).run();
        if (results.isEmpty()) {
            throw new IllegalStateException("No benchmarks matched; no report was generated");
        }
        List<BenchmarkReport.Profile> profiles = settings.profile() ? convertProfiles(directory) : List.of();
        BenchmarkReport.write(directory, settings, results, profiles);
        System.out.println();
        System.out.println("Presence benchmark review is ready.");
        System.out.println("Open this file in your browser (Ctrl+click if your terminal supports file links):");
        System.out.println("  " + directory.resolve("index.html").toUri().toASCIIString());
        System.out.println("JMH raw results: " + directory.resolve("results.json").toUri().toASCIIString());
        if (!profiles.isEmpty()) {
            System.out.println("The report links to " + profiles.size() + " interactive flamegraphs and their JFR recordings.");
        }
        System.out.println("Local path: " + directory.resolve("index.html"));
    }

    static String maximumHeap(String value) {
        if (value == null || !value.matches("[1-9][0-9]*[mMgG]")) {
            throw new IllegalArgumentException("benchmarkMaxHeap must be a positive integer followed by m or g");
        }
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        long amount = Long.parseLong(normalized.substring(0, normalized.length() - 1));
        long bytes;
        try {
            bytes = Math.multiplyExact(amount, normalized.endsWith("g") ? 1L << 30 : 1L << 20);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("benchmarkMaxHeap is too large", exception);
        }
        if (bytes < 256L * (1L << 20)) {
            throw new IllegalArgumentException("benchmarkMaxHeap must be at least the 256m initial fork heap");
        }
        return normalized;
    }

    static List<BenchmarkReport.Profile> convertProfiles(Path directory) throws Exception {
        List<Path> recordings;
        try (var files = Files.walk(directory.resolve("jfr"))) {
            recordings = files.filter(path -> path.toString().endsWith(".jfr")).sorted().toList();
        }
        if (recordings.isEmpty()) {
            throw new IllegalStateException("JFR profiling completed without producing recordings");
        }
        var profiles = new ArrayList<BenchmarkReport.Profile>();
        int sequence = 0;
        for (Path originalRecording : recordings) {
            var prepared = ProfileFiles.shorten(directory, originalRecording, ++sequence);
            profiles.add(ProfileRenderer.render(prepared.recording(), prepared.label()));
        }
        return profiles;
    }

}
