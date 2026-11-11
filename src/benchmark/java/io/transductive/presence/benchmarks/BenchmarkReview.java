package io.transductive.presence.benchmarks;

import static io.transductive.presence.benchmarks.ReportJson.count;
import static io.transductive.presence.benchmarks.ReportJson.finite;
import static io.transductive.presence.benchmarks.ReportJson.integer;
import static io.transductive.presence.benchmarks.ReportJson.number;
import static io.transductive.presence.benchmarks.ReportJson.text;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Rebuilds standalone reviews from explicit local inputs, without running benchmarks. */
public final class BenchmarkReview {
    record Run(Path path, String prefix, String name, String mode, String settings, List<JsonObject> rows) { }
    record Workload(long n, long hits, String mode, long pool) implements Comparable<Workload> {
        @Override
        public int compareTo(Workload other) {
            return Comparator.comparingLong(Workload::n).thenComparingLong(Workload::hits)
                    .thenComparing(Workload::mode).thenComparingLong(Workload::pool).compare(this, other);
        }
    }

    private BenchmarkReview() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected review manifest JSON and output parent directory");
        }
        Path output = generate(Path.of(args[0]), Path.of(args[1]));
        System.out.println("Presence review is ready. Open this file in your browser:");
        System.out.println("  " + output.resolve("index.html").toUri().toASCIIString());
        System.out.println("Local path: " + output.resolve("index.html"));
    }

    static Path generate(Path manifestPath, Path outputParent) throws IOException {
        Path manifest = manifestPath.toAbsolutePath().normalize();
        JsonObject spec = ReportJson.read(manifest).getAsJsonObject();
        List<Path> inputs = paths(spec, "runs", manifest.getParent());
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("Review manifest requires at least one large-benchmark run directory");
        }
        List<Run> runs = new ArrayList<>();
        for (Path input : inputs) {
            runs.add(load(input, "runs/" + (runs.size() + 1) + "/", null));
        }
        String name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-review-" + UUID.randomUUID().toString().substring(0, 8);
        Files.createDirectories(outputParent);
        Path output = outputParent.toRealPath().resolve(name);
        for (Path input : inputs) {
            if (output.startsWith(input)) {
                throw new IllegalArgumentException("Review output must not be inside an input run");
            }
        }
        Map<String, Object> model = model(runs);
        model.put("title", spec.has("title") ? text(spec, "title") : "Membership, measured.");
        model.put("notes", spec.has("notes") ? spec.getAsJsonArray("notes").asList().stream()
                .map(value -> value.getAsString()).toList() : List.of());
        Map<Path, String> attachments = new LinkedHashMap<>();
        model.put("accuracy", ReviewEvidence.accuracy(paths(spec, "accuracy", manifest.getParent()), runs, attachments, false));
        model.put("calibrations", ReviewEvidence.accuracy(paths(spec, "calibrations", manifest.getParent()), runs, attachments, true));
        model.put("pools", ReviewEvidence.pools(paths(spec, "queryPools", manifest.getParent()), attachments));
        Files.createDirectory(output);
        for (Run run : runs) {
            copyDirectory(run.path(), output.resolve(run.prefix()));
        }
        for (var attachment : attachments.entrySet()) {
            Path target = output.resolve(attachment.getValue());
            Files.createDirectories(target.getParent());
            Files.copy(attachment.getKey(), target);
        }
        ReportJson.write(output.resolve("review-input.json"), spec);
        model.put("manifest", true);
        // HTML is written last: incomplete imports never appear to be completed reviews.
        ReportTemplate.write(output.resolve("index.html"), "review.peb", model);
        return output;
    }

    static void writeSingle(Path directory, String mode) throws IOException {
        ReportTemplate.write(directory.resolve("index.html"), "review.peb",
                model(List.of(load(directory.toAbsolutePath(), "", mode))));
    }

    private static List<Path> paths(JsonObject spec, String field, Path base) throws IOException {
        List<Path> paths = new ArrayList<>();
        if (spec.has(field)) {
            for (var item : spec.getAsJsonArray(field)) {
                Path path = base.resolve(item.getAsString()).normalize().toRealPath();
                if (paths.contains(path)) {
                    throw new IllegalArgumentException("Duplicate " + field + " input: " + path);
                }
                paths.add(path);
            }
        }
        return paths;
    }

    private static Run load(Path path, String prefix, String suppliedMode) throws IOException {
        String settings = Files.readString(path.resolve("run.txt"));
        var match = java.util.regex.Pattern.compile("^mode=(full|smoke|profile);").matcher(settings);
        String mode = suppliedMode != null ? suppliedMode : match.find() ? match.group(1) : "";
        if (!List.of("full", "smoke", "profile").contains(mode)) {
            throw new IllegalArgumentException("Cannot identify large benchmark mode: " + path);
        }
        List<JsonObject> rows = new ArrayList<>();
        var identities = new java.util.HashSet<String>();
        Map<Workload, JsonObject> configurations = new TreeMap<>();
        for (var item : ReportJson.read(path.resolve("results.json")).getAsJsonArray()) {
            JsonObject row = item.getAsJsonObject();
            if (!text(row, "benchmark").startsWith("io.transductive.presence.benchmarks.LargeMembershipBenchmark.")
                    || !text(row, "mode").equals("avgt")
                    || !text(row.getAsJsonObject("primaryMetric"), "scoreUnit").equals("ns/op")) {
                throw new IllegalArgumentException("Review requires large membership average-time results in ns/op: " + path);
            }
            Workload workload = workload(row);
            if (workload.n() < 1_000 || !List.of("FRESH", "CACHED").contains(workload.mode()) || workload.pool() < 1) {
                throw new IllegalArgumentException("Invalid workload in " + path);
            }
            if (!identities.add(method(row) + workload) || finite(row.getAsJsonObject("primaryMetric"), "score") < 0) {
                throw new IllegalArgumentException("Duplicate case or negative timing in " + path);
            }
            JsonObject configuration = new JsonObject();
            for (String field : List.of("params", "jmhVersion", "jdkVersion", "vmName", "vmVersion", "jvmArgs", "threads",
                    "forks", "warmupIterations", "measurementIterations", "warmupTime", "measurementTime")) {
                configuration.add(field, row.get(field));
            }
            JsonObject previous = configurations.putIfAbsent(workload, configuration);
            if (previous != null && !previous.equals(configuration)) {
                throw new IllegalArgumentException("Unequal settings within a comparison panel: " + path + " / " + workload);
            }
            rows.add(row);
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("No completed cases in " + path);
        }
        return new Run(path, prefix, path.getFileName().toString(), mode, settings, rows);
    }

    static Workload workload(JsonObject row) {
        JsonObject params = row.getAsJsonObject("params");
        return new Workload(count(params, "N"), count(params, "hitPercent"), text(params, "queryMode"), count(params, "queryCount"));
    }

    private static Map<String, Object> model(List<Run> runs) throws IOException {
        List<Map<String, Object>> primary = new ArrayList<>(), supplementary = new ArrayList<>();
        List<Map<String, Object>> sources = new ArrayList<>(), profiles = new ArrayList<>();
        List<List<String>> heap = new ArrayList<>();
        long cases = 0, profileCases = 0, smokeCases = 0;
        for (Run run : runs) {
            sources.add(Map.of("name", run.name(), "mode", run.mode(), "count", run.rows().size(),
                    "json", run.prefix() + "results.json", "settingsLink", run.prefix() + "run.txt", "settings", run.settings()));
            if (run.mode().equals("profile")) {
                profileCases += run.rows().size();
            } else {
                cases += run.rows().size();
                if (run.mode().equals("smoke")) {
                    smokeCases += run.rows().size();
                }
                Map<Workload, List<JsonObject>> groups = new TreeMap<>();
                for (JsonObject row : run.rows()) {
                    groups.computeIfAbsent(workload(row), ignored -> new ArrayList<>()).add(row);
                }
                for (var group : groups.entrySet()) {
                    var destination = group.getKey().mode().equals("FRESH") && group.getKey().hits() == 0 ? primary : supplementary;
                    destination.add(panel(run, group.getKey(), group.getValue()));
                }
            }
            Path setup = run.path().resolve("setup");
            if (Files.isDirectory(setup)) {
                try (var files = Files.list(setup)) {
                    for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                        JsonObject data = ReportJson.read(file).getAsJsonObject();
                        heap.add(List.of(run.name(), label(text(data, "method")), integer(count(data, "N")),
                                queryLabel(text(data, "queryMode")), text(data, "hitPercent") + "%",
                                number(count(data, "postGcUsedHeapBytes") / 1048576.0),
                                number(count(data, "bitArrayPayloadBytes") / 1048576.0), number(count(data, "buildNanos") / 1e9)));
                    }
                }
            }
            profiles.addAll(profiles(run));
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("title", "Membership, measured.");
        context.put("caseCount", cases);
        context.put("profileCount", profileCases);
        context.put("smokeCount", smokeCases);
        context.put("sizeCount", runs.stream().flatMap(r -> r.rows().stream()).map(r -> workload(r).n()).distinct().count());
        context.put("primary", primary);
        context.put("overview", overview(runs));
        context.put("supplementary", supplementary);
        context.put("sources", sources);
        context.put("heap", heap);
        context.put("profiles", profiles);
        context.put("accuracy", List.of());
        context.put("calibrations", List.of());
        context.put("pools", List.of());
        context.put("notes", List.of());
        context.put("manifest", false);
        return context;
    }

    private static List<Map<String, Object>> overview(List<Run> runs) {
        long largest = runs.stream().filter(run -> run.mode().equals("full")).flatMap(run -> run.rows().stream())
                .filter(row -> workload(row).mode().equals("FRESH") && workload(row).hits() == 0)
                .mapToLong(row -> workload(row).n()).max().orElse(0);
        List<Map<String, Object>> overview = new ArrayList<>();
        for (Run run : runs) {
            if (!run.mode().equals("full")) {
                continue;
            }
            Map<Workload, Map<String, JsonObject>> groups = new TreeMap<>();
            for (JsonObject row : run.rows()) {
                var key = workload(row);
                if (key.n() == largest && key.mode().equals("FRESH") && key.hits() == 0) {
                    groups.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).put(method(row), row);
                }
            }
            for (var group : groups.entrySet()) {
                List<List<String>> rows = new ArrayList<>();
                for (String strategy : List.of("Murmur3", "Rapidhash")) {
                    var standard = group.getValue().get("standard" + strategy);
                    var blocked = group.getValue().get("blocked" + strategy);
                    if (standard != null && blocked != null) {
                        var before = standard.getAsJsonObject("primaryMetric");
                        var after = blocked.getAsJsonObject("primaryMetric");
                        double first = finite(before, "score"), second = finite(after, "score");
                        rows.add(List.of(strategy.equals("Rapidhash") ? "rapidhash" : strategy,
                                number(first) + " ± " + number(before.get("scoreError").getAsDouble()),
                                number(second) + " ± " + number(after.get("scoreError").getAsDouble()),
                                first > 0 ? String.format(java.util.Locale.ROOT, "%+.1f%%", 100 * (second / first - 1)) : "n/a"));
                    }
                }
                if (!rows.isEmpty()) {
                    overview.add(Map.of("n", integer(largest), "pool", integer(group.getKey().pool()), "run", run.name(), "rows", rows));
                }
            }
        }
        return overview;
    }

    private static Map<String, Object> panel(Run run, Workload workload, List<JsonObject> rows) {
        rows.sort(Comparator.comparing(BenchmarkReview::method));
        double maximum = rows.stream().mapToDouble(row -> {
            JsonObject metric = row.getAsJsonObject("primaryMetric");
            double error = metric.get("scoreError").getAsDouble();
            return finite(metric, "score") + (Double.isFinite(error) ? error : 0);
        }).max().orElse(1);
        maximum = Math.max(1, maximum);
        List<Map<String, Object>> bars = new ArrayList<>();
        List<List<String>> table = new ArrayList<>();
        for (JsonObject row : rows) {
            String method = method(row);
            JsonObject metric = row.getAsJsonObject("primaryMetric");
            double score = finite(metric, "score"), error = metric.get("scoreError").getAsDouble();
            JsonObject allocation = row.getAsJsonObject("secondaryMetrics").getAsJsonObject("gc.alloc.rate.norm");
            List<String> means = new ArrayList<>();
            for (var fork : metric.getAsJsonArray("rawData")) {
                means.add(number(fork.getAsJsonArray().asList().stream().mapToDouble(v -> v.getAsDouble()).average().orElse(Double.NaN)));
            }
            table.add(List.of(label(method), number(score), number(error),
                    allocation == null ? "n/a" : number(allocation.get("score").getAsDouble()), String.join(" / ", means)));
            double interval = Double.isFinite(error) ? error : 0;
            bars.add(Map.of("label", label(method), "y", bars.size() * 40 + 10,
                    "width", decimal(500 * score / maximum), "low", decimal(250 + 500 * Math.max(0, score - interval) / maximum),
                    "high", decimal(250 + 500 * (score + interval) / maximum), "score", number(score),
                    "shade", method.startsWith("blocked") ? "#707070" : method.startsWith("standard") ? "#202020" : "#bdbdbd"));
        }
        return Map.of("title", "N = " + integer(workload.n()) + " · " + queryLabel(workload.mode()) + " · " + workload.hits() + "% true hits",
                "run", run.name(), "smoke", run.mode().equals("smoke"), "pool", integer(workload.pool()),
                "fresh", workload.mode().equals("FRESH"), "reference", workload.n() == 1_000,
                "bars", bars, "height", bars.size() * 40 + 15, "rows", table);
    }

    private static List<Map<String, Object>> profiles(Run run) throws IOException {
        List<Map<String, Object>> profiles = new ArrayList<>();
        Path jfr = run.path().resolve("jfr");
        if (Files.isDirectory(jfr)) {
            try (var files = Files.walk(jfr)) {
                for (Path flame : files.filter(p -> p.getFileName().toString().equals("flamegraph.html")).sorted().toList()) {
                    Path stacks = flame.resolveSibling("stacks.collapsed"), recording = flame.resolveSibling("profile.jfr");
                    if (!Files.isRegularFile(recording)) {
                        throw new IOException("Missing profile recording: " + recording);
                    }
                    long samples = ProfileSamples.convertedCount(stacks, Files.readString(flame), 0);
                    profiles.add(Map.of("label", run.name() + " / " + jfr.relativize(flame.getParent()), "samples", integer(samples),
                            "flame", run.prefix() + link(run.path(), flame), "stacks", run.prefix() + link(run.path(), stacks),
                            "recording", run.prefix() + link(run.path(), recording)));
                }
            }
        }
        return profiles;
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (var files = Files.walk(source)) {
            for (Path file : files.toList()) {
                if (Files.isSymbolicLink(file)) {
                    throw new IOException("Report inputs must contain ordinary files, not symbolic links: " + file);
                }
                Path output = target.resolve(source.relativize(file));
                if (Files.isDirectory(file)) {
                    Files.createDirectories(output);
                } else {
                    Files.copy(file, output);
                }
            }
        }
    }

    private static String method(JsonObject row) {
        String value = text(row, "benchmark");
        return value.substring(value.lastIndexOf('.') + 1);
    }

    private static String label(String method) {
        return switch (method) {
            case "standardMurmur3" -> "Standard Bloom · Murmur3";
            case "standardRapidhash" -> "Standard Bloom · rapidhash";
            case "blockedMurmur3" -> "Blocked Bloom · Murmur3";
            case "blockedRapidhash" -> "Blocked Bloom · rapidhash";
            case "hashSet" -> "JDK HashSet";
            case "hashMap" -> "JDK HashMap";
            default -> method;
        };
    }

    static String queryLabel(String mode) {
        return mode.equals("FRESH") ? "fresh inputs" : "reused, prehashed queries";
    }

    private static String decimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static String link(Path root, Path file) {
        try {
            return new URI(null, null, root.relativize(file).toString().replace('\\', '/'), null).toASCIIString();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Cannot encode local report link", exception);
        }
    }
}
