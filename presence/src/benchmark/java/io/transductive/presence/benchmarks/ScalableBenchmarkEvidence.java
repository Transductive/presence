package io.transductive.presence.benchmarks;

import io.transductive.presence.bloom.HashingStrategy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import org.openjdk.jmh.results.RunResult;

/** Untimed storage/accuracy evidence for exactly the scalable comparison cases that ran. */
final class ScalableBenchmarkEvidence {
    private static final int ABSENT_QUERIES = 262_144;
    private record Case(int size, String input, String method) { }

    private ScalableBenchmarkEvidence() { }

    static List<List<String>> write(Path directory, Collection<RunResult> results) throws IOException {
        var cases = new TreeSet<>(Comparator.comparingInt(Case::size).thenComparing(Case::input).thenComparing(Case::method));
        for (var result : results) {
            var parameters = result.getParams();
            if (!parameters.getBenchmark().contains(".ScalableMembershipBenchmark.")) continue;
            String benchmark = parameters.getBenchmark();
            cases.add(new Case(Integer.parseInt(parameters.getParam("N")), parameters.getParam("inputKind"),
                    benchmark.substring(benchmark.lastIndexOf('.') + 1)));
        }
        var rows = new ArrayList<List<String>>();
        var csv = new StringBuilder("N,inputKind,method,layers,payloadBytes,falsePositives,absentQueries\n");
        for (Case workload : cases) {
            var strategy = workload.method().endsWith("Rapidhash") ? HashingStrategy.RAPIDHASH_V3 : HashingStrategy.MURMUR3_128;
            String[] keys = BenchmarkData.keys(workload.size(), workload.input());
            var filters = ScalableMembershipBenchmark.filters(keys, strategy);
            boolean scalable = workload.method().startsWith("scalable");
            for (String key : keys) {
                if (!(scalable ? filters.scalable().mightContain(key) : filters.fixed().mightContain(key))) {
                    throw new IllegalStateException("False negative in " + workload);
                }
            }
            int positives = 0;
            // Separate untimed pass over disjoint absent keys; no tuning based on these results.
            for (String query : BenchmarkData.queries(keys, 0, workload.input(), ABSENT_QUERIES)) {
                if (scalable ? filters.scalable().mightContain(query) : filters.fixed().mightContain(query)) positives++;
            }
            int layers = scalable ? filters.scalable().layerCount() : 1;
            long bytes = scalable ? filters.scalable().bitSize() / 8 : ((filters.fixed().bitSize() + 63L) / 64) * 8;
            var interval = LargeAccuracyStatistics.wilson(positives, ABSENT_QUERIES);
            rows.add(List.of(String.format(Locale.ROOT, "%,d", workload.size()), workload.input(),
                    BenchmarkReport.label(workload.method()), Integer.toString(layers),
                    String.format(Locale.ROOT, "%,d", bytes),
                    String.format(Locale.ROOT, "%.4f%%", 100.0 * positives / ABSENT_QUERIES),
                    String.format(Locale.ROOT, "%.4f–%.4f%%", 100 * interval.lower(), 100 * interval.upper())));
            csv.append(workload.size()).append(',').append(workload.input()).append(',').append(workload.method())
                    .append(',').append(layers).append(',').append(bytes).append(',').append(positives)
                    .append(',').append(ABSENT_QUERIES).append('\n');
        }
        if (!cases.isEmpty()) Files.writeString(directory.resolve("scalable-evidence.csv"), csv);
        return rows;
    }
}
