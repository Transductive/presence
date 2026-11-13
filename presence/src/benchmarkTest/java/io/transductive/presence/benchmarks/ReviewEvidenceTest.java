package io.transductive.presence.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewEvidenceTest {
    @TempDir
    Path directory;

    @Test
    void suppliedAccuracyIsRenderedAndMismatchedParametersAreRejected() throws Exception {
        Path run = Files.createDirectory(directory.resolve("run"));
        Files.writeString(run.resolve("run.txt"), "mode=full; N=[1000]");
        LargeBenchmarkReport.write(run, "full", List.of(LargeBenchmarkReportTest.lookup(
                "blockedMurmur3", 1000, 0, "FRESH", 1024, 42)), List.of());
        Path accuracy = directory.resolve("accuracy.json");
        ReportJson.write(accuracy, Map.of("N", 1000, "margin", .0005, "referenceOnly", true,
                "results", List.of(Map.of("strategy", "MURMUR3_128", "standard", filter(9586, 7),
                        "blocked", filter(10240, 6), "difference", LargeAccuracyStatistics.difference(20000, 20000, 2000000)))));
        Path manifest = directory.resolve("review.json");
        ReportJson.write(manifest, Map.of("runs", List.of("run"), "accuracy", List.of("accuracy.json")));
        Path output = BenchmarkReview.generate(manifest, directory.resolve("reviews"));
        String html = Files.readString(output.resolve("index.html"));
        assertTrue(html.contains("Independent False-Positive Validation · N = 1,000"));
        assertTrue(html.contains("<td>Matched</td>"));
        assertTrue(html.contains("Reference only:"));
        assertTrue(Files.isRegularFile(output.resolve("evidence/1.json")));

        var data = ReportJson.read(accuracy).getAsJsonObject();
        data.getAsJsonArray("results").get(0).getAsJsonObject().getAsJsonObject("blocked").addProperty("hashCount", 5);
        ReportJson.write(accuracy, data);
        assertThrows(IllegalArgumentException.class, () -> BenchmarkReview.generate(manifest, directory.resolve("reviews")));
        try (var children = Files.list(directory.resolve("reviews"))) {
            assertEquals(1, children.count(), "Invalid evidence must not publish a new review");
        }
    }

    @Test
    void inaccurateConfidenceIntervalsAreRejected() throws Exception {
        Path accuracy = directory.resolve("accuracy.json");
        var incorrect = new LinkedHashMap<>(filter(10240, 6));
        incorrect.put("wilsonLower", 0);
        ReportJson.write(accuracy, Map.of("N", 1000, "margin", .0005,
                "results", List.of(Map.of("strategy", "MURMUR3_128", "standard", filter(9586, 7),
                        "blocked", incorrect, "difference", LargeAccuracyStatistics.difference(20000, 20000, 2000000)))));
        assertThrows(IllegalArgumentException.class,
                () -> ReviewEvidence.accuracy(List.of(accuracy), List.of(), new LinkedHashMap<>(), true));
    }

    @Test
    void queryGraphIncludesPayloadObjectsAndExcludesDroppedStrings() {
        var fresh = LargeQueryPoolReport.measure(1000, 0, 1024, "FRESH");
        var reused = LargeQueryPoolReport.measure(1000, 0, 1024, "CACHED");
        assertEquals(1025L, fresh.get("objectCount"));
        assertEquals(2049L, reused.get("objectCount"));
        assertTrue((long) fresh.get("reachableBytes") > 1024 * 50);
        assertTrue((long) reused.get("reachableBytes") > 1024 * 50);
        assertThrows(IllegalArgumentException.class, () -> LargeQueryPoolReport.measure(1000, 0, 1024, "UNKNOWN"));
    }

    private static Map<String, Object> filter(long bits, int probes) {
        var interval = LargeAccuracyStatistics.wilson(20000, 2000000);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bitSize", bits);
        result.put("storageBytes", ((bits + 63) / 64) * 8);
        result.put("hashCount", probes);
        result.put("misses", 2000000);
        result.put("falsePositives", 20000);
        result.put("rate", .01);
        result.put("wilsonLower", interval.lower());
        result.put("wilsonUpper", interval.upper());
        result.put("insertedChecked", 1000);
        result.put("falseNegatives", 0);
        result.put("allocationLookups", 100000);
        result.put("allocatedBytes", 0);
        return result;
    }
}
