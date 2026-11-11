package io.transductive.presence.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.results.AverageTimeResult;
import org.openjdk.jmh.results.BenchmarkResult;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.IterationResultMetaData;
import org.openjdk.jmh.results.ResultRole;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.IterationType;
import org.openjdk.jmh.runner.WorkloadParams;
import org.openjdk.jmh.runner.options.TimeValue;

class LargeBenchmarkReportTest {
    @TempDir
    Path directory;

    @Test
    void rendersLiteralPercentSignsAndOnlyActualSmokeCases() throws Exception {
        Files.writeString(directory.resolve("run.txt"),
                "mode=smoke; N=[1000,10000000,50000000]; include=selected methods");
        var results = List.of(
                lookup("hashSet", 50_000_000, 0, "FRESH", 2_048, 57),
                lookup("blockedRapidhash", 1_000, 0, "CACHED", 1_048_576, 42));

        LargeBenchmarkReport.write(directory, "smoke", results, List.of());

        String html = Files.readString(directory.resolve("index.html"));
        assertTrue(html.contains("width:100%;"));
        assertTrue(html.contains("± error (99.9%)"));
        assertTrue(html.contains("<strong>2</strong><span>unprofiled cases</span>"));
        assertEquals(results.size(), html.split("<svg ", -1).length - 1);
        assertTrue(html.contains("<td>Blocked Bloom · rapidhash</td><td>42.000</td>"));
        assertTrue(html.contains("<td>JDK HashSet</td><td>57.000</td>"));
        assertTrue(html.indexOf("<h2>N = 50,000,000") < html.indexOf("Supplementary: reused queries"));
        assertTrue(html.indexOf("<h2>N = 1,000") > html.indexOf("Supplementary: reused queries"));
        assertFalse(html.contains("<h2>N = 10,000,000"));
        assertFalse(html.contains("JDK HashMap"));
        assertTrue(html.contains("Smoke run: harness wiring only; not performance evidence."));
        assertFalse(html.contains("Full run:"));
        assertFalse(html.contains("two forks"));
        assertTrue(html.contains("No JFR profiles requested for this run or supplied in this review."));
        assertTrue(html.contains("Independent accuracy evidence was not attached."));
    }

    @Test
    void savedRunsKeepSeparatePanelsAndEscapeTextWithoutEvaluatingIt() throws Exception {
        Path first = Files.createDirectory(directory.resolve("run one"));
        Path second = Files.createDirectory(directory.resolve("run two"));
        for (Path run : List.of(first, second)) {
            Files.writeString(run.resolve("run.txt"), "mode=full; <script>settings</script>");
            LargeBenchmarkReport.write(run, "full", List.of(lookup("hashSet", 1000, 0, "FRESH", 1024, 19)), List.of());
        }
        Path manifest = directory.resolve("review.json");
        ReportJson.write(manifest, Map.of("runs", List.of("run one", "run two"),
                "title", "<script>title</script>", "notes", List.of("{{ 7 * 7 }} <script>note</script>")));
        Path output = BenchmarkReview.generate(manifest, directory.resolve("reviews"));
        String html = Files.readString(output.resolve("index.html"));
        assertEquals(2, html.split("<svg ", -1).length - 1);
        assertTrue(html.contains("&lt;script&gt;title&lt;/script&gt;"));
        assertTrue(html.contains("{{ 7 * 7 }} &lt;script&gt;note&lt;/script&gt;"));
        assertFalse(html.contains("<script>"));
        assertTrue(Files.isRegularFile(output.resolve("runs/1/results.json")));
        assertTrue(Files.isRegularFile(output.resolve("runs/2/run.txt")));
        assertTrue(html.contains("Query-pool graph measurements were not attached."));
    }

    @Test
    void profilesAreLinkedButTheirTimingsAreExcluded() throws Exception {
        Files.writeString(directory.resolve("run.txt"), "mode=profile; N=[1000]");
        Path profile = Files.createDirectories(directory.resolve("jfr/a & b"));
        Files.writeString(profile.resolve("profile.jfr"), "fixture placeholder");
        Files.writeString(profile.resolve("stacks.collapsed"), "io.transductive.presence.benchmarks.LargeMembershipBenchmark.hashSet 3\n");
        Files.writeString(profile.resolve("flamegraph.html"), "unpack(cpool);\nn(0,3)\n");
        LargeBenchmarkReport.write(directory, "profile", List.of(lookup("hashSet", 1000, 0, "FRESH", 1024, 99)), List.of());
        String html = Files.readString(directory.resolve("index.html"));
        assertFalse(html.contains("<svg "));
        assertTrue(html.contains("<strong>1</strong><span>profile cases"));
        assertTrue(html.contains("jfr/a%20&amp;%20b/flamegraph.html"));
        assertTrue(html.contains("3 rendered samples"));
    }

    @Test
    void invalidUnitsAndDuplicateInputRunsAreRejected() throws Exception {
        Files.writeString(directory.resolve("run.txt"), "mode=full; N=[1000]");
        LargeBenchmarkReport.write(directory, "full", List.of(lookup("hashSet", 1000, 0, "FRESH", 1024, 99)), List.of());
        var rows = ReportJson.read(directory.resolve("results.json")).getAsJsonArray();
        rows.get(0).getAsJsonObject().getAsJsonObject("primaryMetric").addProperty("scoreUnit", "ops/s");
        ReportJson.write(directory.resolve("results.json"), rows);
        assertThrows(IllegalArgumentException.class, () -> BenchmarkReview.writeSingle(directory, "full"));
        Path manifest = directory.resolve("duplicate.json");
        ReportJson.write(manifest, Map.of("runs", List.of(".", ".")));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkReview.generate(manifest, directory.resolve("reviews")));
    }

    static RunResult lookup(String method, int size, int hitPercent, String queryMode,
            int queryCount, long nanoseconds) {
        var workload = new WorkloadParams();
        workload.put("N", Integer.toString(size), 0);
        workload.put("hitPercent", Integer.toString(hitPercent), 0);
        workload.put("queryMode", queryMode, 0);
        workload.put("queryCount", Integer.toString(queryCount), 0);
        workload.put("mBpk", "9.89453125", 0);
        workload.put("mK", "6", 0);
        workload.put("rBpk", "9.904296875", 0);
        workload.put("rK", "6", 0);
        var measurement = new IterationParams(IterationType.MEASUREMENT, 1, TimeValue.seconds(1), 1);
        var warmup = new IterationParams(IterationType.WARMUP, 0, TimeValue.seconds(1), 1);
        var params = new BenchmarkParams("io.transductive.presence.benchmarks.LargeMembershipBenchmark." + method,
                "generated", true, 1, new int[] {1}, List.of(), 1, 0, warmup, measurement,
                Mode.AverageTime, workload, TimeUnit.NANOSECONDS, 1, "java", List.of(),
                "21", "test JVM", "21", "1.37", TimeValue.seconds(10));
        var iteration = new IterationResult(params, measurement, new IterationResultMetaData(1, 1));
        iteration.addResult(new AverageTimeResult(ResultRole.PRIMARY, method, 1, nanoseconds, TimeUnit.NANOSECONDS));
        return new RunResult(params, List.of(new BenchmarkResult(params, List.of(iteration))));
    }
}
