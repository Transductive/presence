package io.transductive.presence.benchmarks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.profile.JavaFlightRecorderProfiler;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/** Runs the bounded-query, one-contender-at-a-time large Bloom experiment. */
public final class LargeBenchmarkRunner {
    private LargeBenchmarkRunner() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 11) {
            throw new IllegalArgumentException("Expected mode, output directory, sizes, hits, query modes, query count, "
                    + "Murmur blocked bits/key, Murmur probes, rapid blocked bits/key, rapid probes, include regex");
        }
        String mode = args[0];
        if (!List.of("full", "profile", "smoke").contains(mode)) {
            throw new IllegalArgumentException("Large mode must be full, profile, or smoke");
        }
        String[] sizes = choices(args[2], "N", 1_000, Integer.MAX_VALUE);
        String[] hits = choices(args[3], "hits", 0, 100);
        for (String hit : hits) {
            if (!List.of("0", "50", "100").contains(hit)) {
                throw new IllegalArgumentException("Hits must be 0, 50, or 100");
            }
        }
        String[] queryModes = args[4].split(",", -1);
        if (!List.of("CACHED", "FRESH").containsAll(Arrays.asList(queryModes))) {
            throw new IllegalArgumentException("Query modes must be CACHED or FRESH");
        }
        int queryCount = Integer.parseInt(args[5]);
        LargeBenchmarkData.requireQueryCount(queryCount);
        for (int index : new int[] {6, 8}) {
            double bits = Double.parseDouble(args[index]);
            if (!Double.isFinite(bits) || bits <= 0) {
                throw new IllegalArgumentException("Blocked bits/key must be finite and positive");
            }
        }
        for (int index : new int[] {7, 9}) {
            int probes = Integer.parseInt(args[index]);
            if (probes < 1 || probes > 512) {
                throw new IllegalArgumentException("Blocked probes must be between 1 and 512");
            }
        }
        java.util.regex.Pattern.compile(args[10]);
        String maximumHeap = BenchmarkRunner.maximumHeap(System.getProperty("presence.benchmark.maxHeap", "10g"));
        String runName = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-large-" + mode + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path directory = Path.of(args[1]).toAbsolutePath().resolve(runName);
        Path setupDirectory = directory.resolve("setup");
        Files.createDirectories(setupDirectory);
        boolean smoke = mode.equals("smoke");
        boolean profile = mode.equals("profile");
        String settings = "mode=" + mode + "; N=" + Arrays.toString(sizes) + "; hits=" + Arrays.toString(hits)
                + "; query modes=" + Arrays.toString(queryModes) + "; query count=" + queryCount
                + "; Murmur blocked bits/key=" + args[6] + "; Murmur blocked probes=" + args[7]
                + "; rapid blocked bits/key=" + args[8] + "; rapid blocked probes=" + args[9]
                + "; include=" + args[10] + System.lineSeparator()
                + "One selected data structure per trial. Streaming construction; fixed bounded query pool."
                + System.lineSeparator() + BenchmarkEnvironment.describe()
                + "Fork JVM arguments: -Xms512m -Xmx" + maximumHeap + System.lineSeparator();
        Files.writeString(directory.resolve("run.txt"), settings);
        System.out.println(settings);
        System.out.println("Results directory: " + directory);
        var options = new OptionsBuilder().include(args[10])
                .param("N", sizes).param("hitPercent", hits).param("queryMode", queryModes)
                .param("queryCount", args[5])
                // JMH includes parameter names in JFR directory components. Keep
                // those components below Windows' 255-character filename limit.
                .param("mBpk", args[6]).param("mK", args[7])
                .param("rBpk", args[8]).param("rK", args[9])
                .threads(1).forks(smoke || profile ? 1 : 2)
                .warmupIterations(smoke ? 1 : 3).measurementIterations(smoke ? 1 : 5)
                .warmupTime(smoke ? TimeValue.milliseconds(100) : TimeValue.seconds(1))
                .measurementTime(smoke ? TimeValue.milliseconds(200) : TimeValue.seconds(1))
                .jvmArgs("-Xms512m", "-Xmx" + maximumHeap,
                        "-Dpresence.large.setupDirectory=" + setupDirectory)
                .shouldFailOnError(true).resultFormat(ResultFormatType.JSON)
                .result(directory.resolve("results.json").toString()).addProfiler(GCProfiler.class);
        if (profile) {
            options.addProfiler(JavaFlightRecorderProfiler.class,
                    "dir=" + directory.resolve("jfr") + ";configName=profile;stackDepth=128");
        }
        var results = new Runner(options.build()).run();
        if (results.isEmpty()) {
            throw new IllegalStateException("No large benchmarks matched");
        }
        var profiles = profile ? BenchmarkRunner.convertProfiles(directory) : List.<BenchmarkReport.Profile>of();
        LargeBenchmarkReport.write(directory, mode, results, profiles);
        System.out.println("Presence large-filter review is ready. Open this file in your browser:");
        System.out.println("  " + directory.resolve("index.html").toUri().toASCIIString());
        System.out.println("Local path: " + directory.resolve("index.html"));
    }

    static String[] choices(String text, String name, int minimum, int maximum) {
        int[] values = Arrays.stream(text.split(",", -1)).map(String::trim).mapToInt(Integer::parseInt)
                .distinct().sorted().toArray();
        for (int value : values) {
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(name + " is outside the supported range");
            }
        }
        return Arrays.stream(values).mapToObj(String::valueOf).toArray(String[]::new);
    }
}
