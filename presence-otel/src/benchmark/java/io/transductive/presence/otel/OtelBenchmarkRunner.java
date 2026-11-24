package io.transductive.presence.otel;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.loader.ClasspathLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/** Local benchmark entry point with an offline, parameter-driven Pebble report. */
public final class OtelBenchmarkRunner {
    private OtelBenchmarkRunner() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !(args[0].equals("full") || args[0].equals("smoke"))) {
            throw new IllegalArgumentException("Expected full|smoke, output directory, comma-separated sizes");
        }
        boolean smoke = args[0].equals("smoke");
        String[] sizes = sizes(args[2]);
        String started = Instant.now().toString();
        Path directory = Path.of(args[1]).toAbsolutePath().resolve(
                started.replace(':', '-') + "-otel-" + args[0] + "-" + UUID.randomUUID().toString().substring(0, 8));
        Files.createDirectories(directory);
        var fixtures = fixtures(sizes);
        String environment = "Started (UTC): " + started + "\n"
                + "Java: " + System.getProperty("java.runtime.version") + " / " + System.getProperty("java.vm.name") + "\n"
                + "OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " / " + System.getProperty("os.arch") + "\n"
                + "Available processors: " + Runtime.getRuntime().availableProcessors() + "\n"
                + "Mode: " + args[0] + "; sizes: " + Arrays.toString(sizes) + "\n"
                + "Forks: " + (smoke ? 1 : 2) + "; warmup: " + (smoke ? "1 x 100ms" : "3 x 500ms")
                + "; measurement: " + (smoke ? "1 x 100ms" : "5 x 500ms") + "\n"
                + "One owner thread; fork heap: -Xms512m -Xmx512m; JMH 1.37; OTel SDK 1.68.0\n"
                + "MURMUR3_128; 1% target; fixed capacity=N; scalable initial capacity=1000.\n"
                + "8192 prebuilt ASCII queries per fixture; no String construction timed.\n"
                + "SDK reader registered; no exporter, network, or concurrent collection.\n"
                + "Forced refresh uses a logical 30s clock advance per operation plus real nanoTime reads.\n"
                + "Refresh scores are warm-cache average cost, not real-world p99 or cold-cache latency.\n";
        Files.writeString(directory.resolve("run.txt"), environment + "\nFixtures: " + fixtures + "\n");
        System.out.println("Presence OTel benchmark: " + args[0] + " / N=" + String.join(",", sizes));
        if (smoke) System.out.println("SMOKE: harness validation only; do not interpret timings.");
        System.out.println("Progress log: " + directory.resolve("jmh.log"));
        System.out.println("Browser report will be written to: " + directory.resolve("index.html"));
        var options = new OptionsBuilder()
                .include("^" + OtelOverheadBenchmark.class.getName().replace(".", "\\.") + "\\..*$")
                .param("N", sizes)
                .threads(1).forks(smoke ? 1 : 2)
                .warmupIterations(smoke ? 1 : 3).measurementIterations(smoke ? 1 : 5)
                .warmupTime(TimeValue.milliseconds(smoke ? 100 : 500))
                .measurementTime(TimeValue.milliseconds(smoke ? 100 : 500))
                .jvmArgs("-Xms512m", "-Xmx512m")
                .shouldFailOnError(true)
                .addProfiler(GCProfiler.class)
                .resultFormat(ResultFormatType.JSON)
                .result(directory.resolve("results.json").toString())
                .output(directory.resolve("jmh.log").toString());
        var results = new Runner(options.build()).run();
        if (results.isEmpty()) throw new IllegalStateException("JMH returned no results");
        writeReport(directory, results, fixtures, environment, smoke);
        System.out.println("\nOpen this report in your browser:");
        System.out.println(directory.resolve("index.html").toUri().toASCIIString());
        System.out.println("Raw JMH results: " + directory.resolve("results.json").toUri().toASCIIString());
    }

    static String[] sizes(String value) {
        String[] values = value.split(",", -1);
        for (String size : values) {
            if (!size.matches("[0-9]+") || Long.parseLong(size) < 1_000 || Long.parseLong(size) > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Each benchmark size must be an integer from 1000 to 2147483647");
            }
        }
        return Arrays.stream(values).map(Integer::parseInt).distinct().sorted().map(String::valueOf).toArray(String[]::new);
    }

    private static List<Map<String, Object>> fixtures(String[] sizes) {
        var fixtures = new ArrayList<Map<String, Object>>();
        for (var kind : OtelOverheadBenchmark.Kind.values()) {
            for (String size : sizes) {
                var fixture = new OtelOverheadBenchmark();
                fixture.N = Integer.parseInt(size);
                fixture.kind = kind;
                fixture.operation = OtelOverheadBenchmark.Operation.MISS;
                try {
                    fixture.setup();
                    int positives = 0;
                    for (int i = 0; i < OtelOverheadBenchmark.QUERY_COUNT; i++) if (fixture.raw()) positives++;
                    fixtures.add(Map.of("kind", kind.toString(), "n", String.format(Locale.ROOT, "%,d", fixture.N),
                            "layers", fixture.layers(), "bytes", String.format(Locale.ROOT, "%,d", fixture.arrayBytes()),
                            "falsePositives", positives, "queries", OtelOverheadBenchmark.QUERY_COUNT));
                } finally {
                    fixture.close();
                }
            }
        }
        return fixtures;
    }

    static void writeReport(Path directory, Collection<RunResult> results, List<Map<String, Object>> fixtures,
            String environment, boolean smoke) throws Exception {
        var sections = new ArrayList<Map<String, Object>>();
        for (var kind : OtelOverheadBenchmark.Kind.values()) {
            for (var operation : OtelOverheadBenchmark.Operation.values()) {
                var rows = new java.util.TreeMap<Integer, Map<String, Object>>();
                for (var result : results) {
                    var params = result.getParams();
                    if (!params.getParam("kind").equals(kind.toString())
                            || !params.getParam("operation").equals(operation.toString())) continue;
                    int n = Integer.parseInt(params.getParam("N"));
                    var row = rows.computeIfAbsent(n, ignored -> {
                        var value = new LinkedHashMap<String, Object>();
                        value.put("n", String.format(Locale.ROOT, "%,d", n));
                        return value;
                    });
                    String name = params.getBenchmark().substring(params.getBenchmark().lastIndexOf('.') + 1);
                    var primary = result.getPrimaryResult();
                    var allocation = result.getSecondaryResults().get("gc.alloc.rate.norm");
                    row.put(name, Map.of("score", number(primary.getScore()),
                            "error", number(primary.getScoreError()),
                            "allocation", allocation == null ? "n/a" : allocation.getScore() < 0.01 ? "<0.01" : number(allocation.getScore())));
                }
                if (!rows.isEmpty()) sections.add(Map.of("title", (kind == OtelOverheadBenchmark.Kind.FIXED ? "Fixed Bloom" : "Scalable Bloom")
                        + " · " + switch (operation) {
                            case HIT -> "Present Queries";
                            case MISS -> "Absent Queries";
                            case REPEATED_PUT -> "Repeated Puts";
                        }, "rows", new ArrayList<>(rows.values())));
            }
        }
        var engine = new PebbleEngine.Builder().loader(new ClasspathLoader()).strictVariables(true).autoEscaping(true).build();
        try (var writer = Files.newBufferedWriter(directory.resolve("index.html"))) {
            engine.getTemplate("otel-report.peb").evaluate(writer, Map.of("sections", sections, "fixtures", fixtures,
                    "environment", environment, "smoke", smoke, "caseCount", results.size()));
        }
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%,.2f", value) : "n/a";
    }
}
