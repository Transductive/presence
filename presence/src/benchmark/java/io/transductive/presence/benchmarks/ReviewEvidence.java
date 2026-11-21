package io.transductive.presence.benchmarks;

import static io.transductive.presence.benchmarks.ReportJson.count;
import static io.transductive.presence.benchmarks.ReportJson.finite;
import static io.transductive.presence.benchmarks.ReportJson.integer;
import static io.transductive.presence.benchmarks.ReportJson.number;
import static io.transductive.presence.benchmarks.ReportJson.text;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Adapts explicitly supplied accuracy and memory evidence to the review template. */
final class ReviewEvidence {
    private ReviewEvidence() { }

    static List<Map<String, Object>> accuracy(List<Path> files, List<BenchmarkReview.Run> runs,
            Map<Path, String> attachments, boolean calibration) throws IOException {
        List<Map<String, Object>> sections = new ArrayList<>();
        for (Path file : files) {
            JsonObject data = ReportJson.read(file).getAsJsonObject();
            long n = count(data, "N");
            double margin = finite(data, "margin");
            if (n < 1_000 || margin <= 0 || margin >= 1) {
                throw new IllegalArgumentException("Invalid accuracy size or equivalence margin: " + file);
            }
            List<List<String>> rates = new ArrayList<>(), differences = new ArrayList<>(), audits = new ArrayList<>();
            for (var item : data.getAsJsonArray("results")) {
                JsonObject pair = item.getAsJsonObject();
                String strategy = text(pair, "strategy");
                if (!calibration) {
                    verifyDimensions(runs, n, strategy, pair.getAsJsonObject("standard"), pair.getAsJsonObject("blocked"));
                }
                for (String layout : List.of("standard", "blocked")) {
                    JsonObject value = pair.getAsJsonObject(layout);
                    double observed = (double) count(value, "falsePositives") / count(value, "misses");
                    if (!Double.isFinite(observed) || observed > 1 || Math.abs(observed - finite(value, "rate")) > 1e-12) {
                        throw new IllegalArgumentException("Inconsistent false-positive rate: " + file);
                    }
                    var wilson = LargeAccuracyStatistics.wilson(count(value, "falsePositives"), count(value, "misses"));
                    close(finite(value, "wilsonLower"), wilson.lower());
                    close(finite(value, "wilsonUpper"), wilson.upper());
                    rates.add(List.of(strategy, layout, number(count(value, "bitSize") / (double) n), text(value, "hashCount"),
                            number(count(value, "storageBytes") / 1048576.0), percent(observed),
                            interval(value, "wilsonLower", "wilsonUpper"),
                            integer(count(value, "falsePositives")) + " / " + integer(count(value, "misses"))));
                    audits.add(List.of(strategy, layout, integer(count(value, "falseNegatives")) + " / " + integer(count(value, "insertedChecked")),
                            integer(count(value, "allocatedBytes")), integer(count(value, "allocationLookups"))));
                }
                JsonObject diff = pair.getAsJsonObject("difference");
                JsonObject standard = pair.getAsJsonObject("standard"), blocked = pair.getAsJsonObject("blocked");
                if (count(standard, "misses") != count(blocked, "misses")) {
                    throw new IllegalArgumentException("Accuracy layouts must use equal sample counts");
                }
                var computed = LargeAccuracyStatistics.difference(count(standard, "falsePositives"),
                        count(blocked, "falsePositives"), count(standard, "misses"));
                close(finite(diff, "estimate"), computed.estimate());
                close(finite(diff, "lower"), computed.lower());
                close(finite(diff, "upper"), computed.upper());
                boolean matched = finite(diff, "lower") >= -margin && finite(diff, "upper") <= margin;
                differences.add(List.of(strategy, percent(finite(diff, "estimate")) + " pp",
                        interval(diff, "lower", "upper") + " pp", matched ? "Matched" : "Not established"));
            }
            sections.add(Map.of("n", integer(n), "margin", percent(margin), "rates", rates, "differences", differences,
                    "audits", audits, "link", attach(file, attachments),
                    "reference", data.has("referenceOnly") && data.get("referenceOnly").getAsBoolean(),
                    "rule", data.has("selectionRule") ? text(data, "selectionRule") : "Independent held-out validation"));
        }
        return sections;
    }

    private static void close(double actual, double expected) {
        if (Math.abs(actual - expected) > 1e-12) {
            throw new IllegalArgumentException("Accuracy interval does not match the recorded counts");
        }
    }

    private static void verifyDimensions(List<BenchmarkReview.Run> runs, long n, String strategy,
            JsonObject standard, JsonObject blocked) {
        String prefix = strategy.equals("MURMUR3_128") ? "m" : strategy.equals("RAPIDHASH_V3") ? "r" : "";
        if (prefix.isEmpty()) {
            throw new IllegalArgumentException("Unknown accuracy strategy: " + strategy);
        }
        for (var run : runs) {
            for (JsonObject row : run.rows()) {
                if (BenchmarkReview.workload(row).n() == n) {
                    long standardBits = (long) Math.ceil(-n * Math.log(.01) / (Math.log(2) * Math.log(2)));
                    if (count(standard, "bitSize") != standardBits || count(standard, "hashCount") != 7) {
                        throw new IllegalArgumentException("Accuracy baseline differs from the timed 1% standard filter at N=" + n);
                    }
                    JsonObject params = row.getAsJsonObject("params");
                    long expectedBits = (long) Math.ceil(n * finite(params, prefix + "Bpk") / 512) * 512;
                    if (expectedBits != count(blocked, "bitSize") || count(params, prefix + "K") != count(blocked, "hashCount")) {
                        throw new IllegalArgumentException("Accuracy dimensions differ from timed parameters at N=" + n);
                    }
                }
            }
        }
    }

    static List<Map<String, Object>> pools(List<Path> files, Map<Path, String> attachments) throws IOException {
        List<Map<String, Object>> pools = new ArrayList<>();
        for (Path file : files) {
            JsonObject data = ReportJson.read(file).getAsJsonObject();
            pools.add(Map.of("method", text(data, "sizingMethod"), "rows", poolRows(data), "link", attach(file, attachments)));
        }
        return pools;
    }

    static List<List<String>> poolRows(JsonObject data) {
        List<List<String>> rows = new ArrayList<>();
        for (var item : data.getAsJsonArray("results")) {
            JsonObject row = item.getAsJsonObject();
            rows.add(List.of(integer(count(row, "N")), BenchmarkReview.queryLabel(text(row, "queryMode")),
                    text(row, "hitPercent") + "%", integer(count(row, "queryCount")),
                    number(count(row, "reachableBytes") / 1048576.0), integer(count(row, "objectCount"))));
        }
        return rows;
    }

    private static String attach(Path file, Map<Path, String> attachments) {
        return attachments.computeIfAbsent(file, ignored -> "evidence/" + (attachments.size() + 1) + ".json");
    }

    private static String percent(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value * 100);
    }

    private static String interval(JsonObject value, String lower, String upper) {
        if (finite(value, lower) > finite(value, upper)) {
            throw new IllegalArgumentException("Reversed confidence interval");
        }
        return "[" + percent(finite(value, lower)) + ", " + percent(finite(value, upper)) + "]";
    }
}
