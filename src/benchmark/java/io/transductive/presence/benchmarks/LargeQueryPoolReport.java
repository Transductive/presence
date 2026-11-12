package io.transductive.presence.benchmarks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.openjdk.jol.info.GraphLayout;
import org.openjdk.jol.vm.VM;

/** Measures fixture query graphs outside timing and without building a membership structure. */
public final class LargeQueryPoolReport {
    private LargeQueryPoolReport() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Expected output directory, sizes, hit percentages, query count");
        }
        String[] sizes = LargeBenchmarkRunner.choices(args[1], "N", 1_000, Integer.MAX_VALUE);
        String[] hits = LargeBenchmarkRunner.choices(args[2], "hits", 0, 100);
        int queryCount = Integer.parseInt(args[3]);
        LargeBenchmarkData.requireQueryCount(queryCount);
        String sizing = HeapReport.sizingMethod();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String size : sizes) {
            for (String hit : hits) {
                for (String mode : List.of("FRESH", "CACHED")) {
                    rows.add(measure(Integer.parseInt(size), Integer.parseInt(hit), queryCount, mode));
                }
            }
        }
        String name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-query-pool-" + UUID.randomUUID().toString().substring(0, 8);
        Path output = Path.of(args[0]).toAbsolutePath().resolve(name);
        Files.createDirectories(output);
        Map<String, Object> data = Map.of("sizingMethod", sizing, "javaVersion", System.getProperty("java.version"),
                "vmDetails", VM.current().details(), "results", rows,
                "scope", "Reachable query-pool graph, including reference array and payload objects. "
                        + "Excludes membership structure, transient String construction, and measurement harness.");
        Path json = output.resolve("query-pool.json");
        ReportJson.write(json, data);
        ReportTemplate.write(output.resolve("index.html"), "query-pool.peb", Map.of("title", "Query memory, measured.",
                "method", sizing, "rows", ReviewEvidence.poolRows(ReportJson.read(json).getAsJsonObject())));
        System.out.println("Presence query-pool review is ready. Open this file in your browser:");
        System.out.println("  " + output.resolve("index.html").toUri().toASCIIString());
        System.out.println("Local path: " + output.resolve("index.html"));
        System.out.println("Add this file to your review manifest's queryPools array: " + json);
    }

    static Map<String, Object> measure(int n, int hits, int queryCount, String mode) {
        HeapReport.sizingMethod();
        Object root = pool(n, hits, queryCount, mode);
        GraphLayout graph = GraphLayout.parseInstance(root);
        return Map.of("N", n, "hitPercent", hits, "queryCount", queryCount, "queryMode", mode,
                "reachableBytes", graph.totalSize(), "objectCount", graph.totalCount());
    }

    private static Object pool(int n, int hits, int queryCount, String mode) {
        String[] strings = LargeBenchmarkData.queries(n, hits, queryCount);
        // No original String[] is reachable from the FRESH root.
        char[][] characters = QueryPreparation.characters(strings, mode);
        return characters == null ? strings : characters;
    }
}
