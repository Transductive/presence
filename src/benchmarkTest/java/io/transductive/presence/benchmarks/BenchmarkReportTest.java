package io.transductive.presence.benchmarks;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

class BenchmarkReportTest {
    @TempDir
    Path directory;

    @Test
    void scalableReportIncludesOnlyMeasuredCasesAndLabelsPayloadSeparately() throws Exception {
        var settings = BenchmarkSettings.parse("scalable", "1000,10000", "0,100", ".*");
        BenchmarkReport.write(directory, settings, List.of(
                result("ScalableMembershipBenchmark", "fixedRapidhash", 1_000, 0, 30, "ASCII", "BROAD", "CACHED", 0),
                result("ScalableMembershipBenchmark", "scalableRapidhash", 1_000, 0, 40, "ASCII", "BROAD", "CACHED", 0)), List.of());
        String html = Files.readString(directory.resolve("index.html"));
        assertTrue(html.contains("Focused run"));
        assertTrue(html.contains("Fixed Bloom · rapidhash v3"));
        assertTrue(html.contains("Scalable Bloom · rapidhash v3"));
        assertTrue(html.contains("Payload bytes"));
        assertTrue(html.contains("Measured false-positive rates and memory are not matched"));
        assertFalse(html.contains("N=10,000"));
        assertFalse(html.contains("Scalable Bloom · Murmur3"));
        assertFalse(html.contains("{{"));
        var csv = Files.readAllLines(directory.resolve("scalable-evidence.csv"));
        assertEquals(3, csv.size());
        assertTrue(csv.get(1).startsWith("1000,ASCII,fixedRapidhash,1,"));
        assertTrue(csv.get(2).startsWith("1000,ASCII,scalableRapidhash,1,"));
    }

    @Test
    void singleSizeReportUsesActualResultsEvenWhenSettingsListMoreSizes() throws Exception {
        var settings = BenchmarkSettings.parse("full", "1000,10000,100000,500000,1000000", "0,50,100", ".*");
        BenchmarkReport.write(directory, settings, List.of(lookup("bloom", 1_000, 50, 42)), List.of());
        String html = Files.readString(directory.resolve("index.html"));
        String scaling = html.substring(html.indexOf("<h2>Lookup Scaling</h2>"),
                html.indexOf("</section>", html.indexOf("<h2>Lookup Scaling</h2>")));
        assertTrue(scaling.contains("<tr><th>1,000</th><td>42.00</td></tr>"));
        assertTrue(scaling.contains("Presence Bloom · Murmur3"));
        assertFalse(scaling.contains("JDK HashSet"));
        assertFalse(scaling.contains("SHA-256"));
        assertFalse(html.contains("N=10,000"));
        assertFalse(html.contains("N=1,000,000"));
        assertFalse(html.contains("<h3>0% Hits"));
        assertFalse(html.contains("<h3>100% Hits"));
        assertFalse(html.contains("{{"));
    }

    @Test
    void multipleSizesAreNumericallyOrderedAndIncludeBothHashingStrategies() throws Exception {
        var settings = BenchmarkSettings.parse("full", "500000,1000000", "50", ".*");
        BenchmarkReport.write(directory, settings, List.of(
                lookup("bloomSha256", 1_000_000, 50, 300),
                lookup("bloom", 500_000, 50, 40),
                lookup("bloom", 1_000_000, 50, 45)), List.of());
        String html = Files.readString(directory.resolve("index.html"));
        assertTrue(html.indexOf("<tr><th>500,000</th>") < html.indexOf("<tr><th>1,000,000</th>"));
        assertTrue(html.indexOf("<h2>Lookup · N=500,000") < html.indexOf("<h2>Lookup · N=1,000,000"));
        assertTrue(html.contains("<tr><th>1,000,000</th><td>45.00</td><td>300.00</td></tr>"));
        assertTrue(html.contains("Presence Bloom · SHA-256"));
        assertFalse(html.contains("<h2>Lookup · N=1,000 ·"));
    }

    @Test
    void filteredNewStrategiesDetermineColumnsWithoutInventingBaselineResults() throws Exception {
        var settings = BenchmarkSettings.parse("full", "1000,10000", "0,50,100", ".*");
        BenchmarkReport.write(directory, settings, List.of(
                lookup("bloomRapidhash", 1_000, 50, 30),
                lookup("bloomKomihash", 1_000, 50, 35),
                lookup("bloomRapidhashNano", 1_000, 50, 31)), List.of());
        String html = Files.readString(directory.resolve("index.html"));
        String scaling = html.substring(html.indexOf("<h2>Lookup Scaling</h2>"),
                html.indexOf("</section>", html.indexOf("<h2>Lookup Scaling</h2>")));
        assertTrue(scaling.contains("Presence Bloom · rapidhash v3"));
        assertTrue(scaling.contains("Presence Bloom · rapidhash Nano v3"));
        assertTrue(scaling.contains("Presence Bloom · Komihash 5.34"));
        assertTrue(scaling.contains("<tr><th>1,000</th><td>35.00</td><td>30.00</td><td>31.00</td></tr>"));
        assertFalse(scaling.contains("Murmur3"));
        assertFalse(scaling.contains("SHA-256"));
        assertFalse(scaling.contains("JDK Hash"));
        assertFalse(html.contains("N=10,000"));
        assertFalse(html.contains("{{"));
    }

    private static RunResult lookup(String method, int size, int hitPercent, long nanoseconds) {
        return lookup(method, size, hitPercent, nanoseconds, "ASCII");
    }

    @Test
    void inputKindsHaveSeparateRowsAndScalingTables() throws Exception {
        var settings = BenchmarkSettings.parse("allocation", "1000", "50", ".*", "ASCII,UNICODE");
        BenchmarkReport.write(directory, settings, List.of(
                lookup("bloom", 1_000, 50, 30, "ASCII"),
                lookup("bloom", 1_000, 50, 60, "UNICODE")), List.of());
        String html = Files.readString(directory.resolve("index.html"));
        assertTrue(html.contains("<h2>Lookup Scaling · UNICODE</h2>"));
        assertTrue(html.contains("<h2>Lookup · N=1,000 · Hits=50% · Input=UNICODE · 4,096 Queries</h2>"));
        assertTrue(html.contains("<tr><th>1,000</th><td>30.00</td></tr>"));
        assertTrue(html.contains("<tr><th>1,000</th><td>60.00</td></tr>"));
    }

    private static RunResult lookup(String method, int size, int hitPercent, long nanoseconds, String inputKind) {
        return result("MembershipBenchmark", method, size, hitPercent, nanoseconds, inputKind, "HOT", "CACHED", 0);
    }

    @Test
    void workingSetsFreshQueriesAndDownstreamCostsNeverShareScalingRows() throws Exception {
        var settings = BenchmarkSettings.parse("prefilter", "1000", "0", ".*");
        BenchmarkReport.write(directory, settings, List.of(
                result("MembershipBenchmark", "bloomRapidhash", 1_000, 0, 10, "ASCII", "HOT", "CACHED", 0),
                result("MembershipBenchmark", "bloomRapidhash", 1_000, 0, 20, "ASCII", "BROAD", "CACHED", 0),
                result("MembershipBenchmark", "bloomRapidhash", 1_000, 0, 30, "ASCII", "BROAD", "FRESH", 0),
                result("PrefilterBenchmark", "direct", 1_000, 0, 40, "ASCII", "HOT", "CACHED", 0),
                result("PrefilterBenchmark", "direct", 1_000, 0, 50, "ASCII", "HOT", "CACHED", 256)), List.of());
        String html = Files.readString(directory.resolve("index.html"));
        assertTrue(html.contains("<h2>Lookup Scaling · BROAD · CACHED</h2>"));
        assertTrue(html.contains("<h2>Lookup Scaling · BROAD · FRESH</h2>"));
        assertTrue(html.contains("<h2>Prefilter Scaling · RAPIDHASH_V3 · Downstream Tokens=0</h2>"));
        assertTrue(html.contains("<h2>Prefilter Scaling · RAPIDHASH_V3 · Downstream Tokens=256</h2>"));
        assertTrue(html.contains("65,536 Queries</h2>"));
        for (int score : List.of(10, 20, 30, 40, 50)) {
            assertTrue(html.contains("<tr><th>1,000</th><td>" + score + ".00</td></tr>"));
        }
    }

    private static RunResult result(String benchmark, String method, int size, int hitPercent, long nanoseconds,
            String inputKind, String workingSet, String queryMode, int downstreamTokens) {
        var workload = new WorkloadParams();
        workload.put("N", Integer.toString(size), 0);
        workload.put("hitPercent", Integer.toString(hitPercent), 0);
        workload.put("inputKind", inputKind, 0);
        workload.put("workingSet", workingSet, 0);
        workload.put("queryMode", queryMode, 0);
        workload.put("downstreamTokens", Integer.toString(downstreamTokens), 0);
        workload.put("strategy", "RAPIDHASH_V3", 0);
        var measurement = new IterationParams(IterationType.MEASUREMENT, 1, TimeValue.seconds(1), 1);
        var warmup = new IterationParams(IterationType.WARMUP, 0, TimeValue.seconds(1), 1);
        var params = new BenchmarkParams("io.transductive.presence.benchmarks." + benchmark + "." + method,
                "generated", true, 1, new int[] {1}, List.of(), 1, 0, warmup, measurement,
                Mode.AverageTime, workload, TimeUnit.NANOSECONDS, 1, "java", List.of(),
                "21", "test JVM", "21", "1.37", TimeValue.seconds(10));
        var iteration = new IterationResult(params, measurement, new IterationResultMetaData(1, 1));
        iteration.addResult(new AverageTimeResult(ResultRole.PRIMARY, method, 1, nanoseconds, TimeUnit.NANOSECONDS));
        return new RunResult(params, List.of(new BenchmarkResult(params, List.of(iteration))));
    }
}
