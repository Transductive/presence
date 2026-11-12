package io.transductive.presence.benchmarks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.openjdk.jmh.results.RunResult;

final class BenchmarkReport {
    record Profile(String label, Path recording, Path flamegraph, Path stacks, long samples) { }

    private record Workload(boolean lookup, boolean prefilter, int size, int hitPercent, String inputKind,
            String scenario, String workingSet) {
        String title() {
            return (prefilter ? "Prefilter" : lookup ? "Lookup" : "Construction") + " · N="
                    + String.format(Locale.ROOT, "%,d", size)
                    + (lookup ? " · hits=" + hitPercent + "%" : " · all N inserts")
                    + (lookup && !inputKind.equals("ASCII") ? " · input=" + inputKind : "")
                    + (scenario.isEmpty() ? "" : " · " + scenario)
                    + (lookup ? " · " + String.format(Locale.ROOT, "%,d", BenchmarkData.queryCount(size, workingSet))
                            + " queries" : "");
        }

        String scalingTitle() {
            return (prefilter ? "Prefilter scaling" : "Lookup scaling")
                    + (inputKind.equals("ASCII") ? "" : " · " + inputKind)
                    + (scenario.isEmpty() ? "" : " · " + scenario);
        }
    }

    private BenchmarkReport() { }

    static void write(Path directory, BenchmarkSettings settings, Collection<RunResult> results,
            List<Profile> profiles) throws IOException {
        String warning = settings.smoke()
                ? "Smoke run — harness validation only. These short measurements are not performance evidence."
                : settings.profile()
                ? "Profile run — JFR adds overhead. Use an unprofiled benchmark run for timing comparisons."
                : "Full run — compare matching workloads and N. Lower time and allocation are better.";
        String environment = "Java " + Runtime.version() + " · " + System.getProperty("os.name")
                + " / " + System.getProperty("os.arch") + " · "
                + Runtime.getRuntime().availableProcessors() + " logical processors · 1 benchmark thread";
        StringBuilder sections = new StringBuilder();
        Map<Workload, List<RunResult>> groups = new TreeMap<>(Comparator
                .comparingInt((Workload workload) -> workload.lookup() ? 0 : 1)
                .thenComparing(Workload::prefilter)
                .thenComparing(Workload::inputKind)
                .thenComparing(Workload::scenario)
                .thenComparingInt(Workload::size).thenComparingInt(Workload::hitPercent));
        for (RunResult result : results) {
            var params = result.getParams();
            boolean prefilter = params.getBenchmark().contains("PrefilterBenchmark");
            boolean lookup = params.getBenchmark().contains("MembershipBenchmark") || prefilter;
            String workingSet = parameter(result, "workingSet", "HOT");
            String queryMode = parameter(result, "queryMode", "CACHED");
            String scenario = workingSet.equals("HOT") && queryMode.equals("CACHED") ? ""
                    : workingSet + " · " + queryMode;
            if (prefilter) {
                scenario += (scenario.isEmpty() ? "" : " · ") + parameter(result, "strategy", "RAPIDHASH_V3")
                        + " · downstream tokens=" + parameter(result, "downstreamTokens", "0");
            }
            var key = new Workload(lookup, prefilter, Integer.parseInt(params.getParam("N")),
                    lookup ? Integer.parseInt(params.getParam("hitPercent")) : 0,
                    lookup && params.getParamsKeys().contains("inputKind") ? params.getParam("inputKind") : "ASCII",
                    scenario, workingSet);
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(result);
        }
        for (var group : groups.entrySet()) {
            boolean lookup = group.getKey().lookup();
            String operation = lookup ? "lookup" : "build";
            sections.append("<section><h2>").append(escape(group.getKey().title())).append("</h2>")
                    .append("<div class='table-wrap'><table><thead><tr><th>Implementation</th><th>ns/")
                    .append(operation).append("</th><th>± error (99.9%)</th><th>Allocated B/")
                    .append(operation).append("</th>");
            if (group.getKey().prefilter()) {
                sections.append("<th>Downstream calls</th><th>False positives / absent</th>");
            }
            sections.append("<th>Relative time</th></tr></thead><tbody>");
            double maximum = group.getValue().stream().mapToDouble(r -> r.getPrimaryResult().getScore()).max().orElse(1);
            group.getValue().sort(Comparator.comparing(r -> r.getParams().getBenchmark()));
            for (RunResult result : group.getValue()) {
                String label = label(method(result));
                double score = result.getPrimaryResult().getScore();
                var allocation = result.getSecondaryResults().get("gc.alloc.rate.norm");
                double width = Math.max(0.5, 100 * score / maximum);
                sections.append("<tr><th>").append(escape(label)).append("</th><td>").append(number(score))
                        .append("</td><td>").append(number(result.getPrimaryResult().getScoreError()))
                        .append("</td><td>").append(allocation == null ? "n/a" : number(allocation.getScore())).append("</td>");
                if (group.getKey().prefilter()) {
                    sections.append("<td>").append(percentage(result, "downstreamCalls", "queries"))
                            .append("</td><td>").append(method(result).equals("direct") ? "—"
                                    : percentage(result, "falsePositives", "absentQueries")).append("</td>");
                }
                sections.append("<td class='bar-cell'><span class='bar' style='width:")
                        .append(String.format(Locale.ROOT, "%.2f", width)).append("%'></span></td></tr>");
            }
            sections.append("</tbody></table></div></section>");
        }
        StringBuilder links = new StringBuilder();
        if (!profiles.isEmpty()) {
            links.append("<section><h2>Flamegraphs</h2><p>Click a frame to zoom; use the graph's search to locate a method. "
                    + "Widths show the share of sampled execution stacks, not elapsed time. "
                    + "Only io.transductive frames are shown; hidden callees still contribute to visible callers' sample widths. "
                    + "The full JFR recordings remain available.</p><ul class='profiles'>");
            for (Profile profile : profiles) {
                links.append("<li><strong>").append(escape(profile.label())).append("</strong><br><a href='")
                        .append(relative(directory, profile.flamegraph())).append("'>Open flamegraph</a> · <a href='")
                        .append(relative(directory, profile.recording())).append("'>JFR recording</a> · <a href='")
                        .append(relative(directory, profile.stacks())).append("'>Converted stacks</a> · ")
                        .append(profile.samples()).append(" rendered benchmark stack samples");
                if (profile.samples() == 0) {
                    links.append(" <em>— insufficient samples; no hotspots can be inferred from this graph.</em>");
                }
                links.append("</li>");
            }
            links.append("</ul></section>");
        }
        ReportTemplate.write(directory.resolve("index.html"), "benchmark.peb", Map.of(
                "MODE", settings.mode(), "WARNING", warning, "ENVIRONMENT", environment,
                "ALLOCATION_AUDIT", allocationAudit(directory), "SCALING", scaling(groups),
                "PROFILES", links.toString(), "RESULTS", sections.toString()));
    }

    private static String scaling(Map<Workload, List<RunResult>> groups) {
        var html = new StringBuilder();
        var slices = groups.keySet().stream().filter(Workload::lookup).map(Workload::scalingTitle).distinct().sorted().toList();
        for (String slice : slices) {
            var matching = new java.util.LinkedHashMap<Workload, List<RunResult>>();
            groups.forEach((key, value) -> {
                if (key.lookup() && key.scalingTitle().equals(slice)) {
                    matching.put(key, value);
                }
            });
            html.append(scalingForInput(matching, slice));
        }
        return html.toString();
    }

    private static String allocationAudit(Path directory) throws IOException {
        Path file = directory.resolve("allocation-check.txt");
        if (!Files.isRegularFile(file)) {
            return "";
        }
        var match = java.util.regex.Pattern.compile("Total: (\\d+) allocated bytes across (\\d+) lookups\\.")
                .matcher(Files.readString(file));
        if (!match.find()) {
            throw new IOException("Missing allocation audit totals");
        }
        return "<p class='notice'><a href='allocation-check.txt'>Lookup-thread allocation audit</a>: "
                + String.format(Locale.ROOT, "%,d", Long.parseLong(match.group(1))) + " allocated bytes across "
                + String.format(Locale.ROOT, "%,d", Long.parseLong(match.group(2)))
                + " lookups. See the audit for input types, strategies, and warmup. JMH's GC metric below also "
                + "includes harness/background allocation, which can appear as small fractional B/op.</p>";
    }

    private static String scalingForInput(Map<Workload, List<RunResult>> groups, String title) {
        var hitRates = groups.keySet().stream().filter(Workload::lookup).map(Workload::hitPercent)
                .distinct().sorted().toList();
        if (hitRates.isEmpty()) {
            return "";
        }
        var methods = groups.entrySet().stream().filter(group -> group.getKey().lookup())
                .flatMap(group -> group.getValue().stream()).map(BenchmarkReport::method).distinct().sorted().toList();
        var html = new StringBuilder("<section><h2>" + escape(title) + "</h2><p>Nanoseconds per lookup across stored-key counts. "
                + "Lower is better. Compare within the same hit ratio; see the detailed tables for uncertainty "
                + "and allocation. A missing result for an included implementation is shown as n/a.</p>");
        for (int hitPercent : hitRates) {
            html.append("<h3>").append(hitPercent).append("% hits · ns/lookup</h3>")
                    .append("<div class='table-wrap'><table class='scaling-table'><thead><tr><th>N · stored keys</th>");
            for (String method : methods) {
                html.append("<th>").append(label(method)).append("</th>");
            }
            html.append("</tr></thead><tbody>");
            for (var group : groups.entrySet()) {
                Workload workload = group.getKey();
                if (!workload.lookup() || workload.hitPercent() != hitPercent) {
                    continue;
                }
                html.append("<tr><th>").append(String.format(Locale.ROOT, "%,d", workload.size())).append("</th>");
                for (String method : methods) {
                    String score = group.getValue().stream().filter(result -> method(result).equals(method))
                            .map(result -> number(result.getPrimaryResult().getScore())).findFirst().orElse("n/a");
                    html.append("<td>").append(score).append("</td>");
                }
                html.append("</tr>");
            }
            html.append("</tbody></table></div>");
        }
        return html.append("</section>").toString();
    }

    private static String method(RunResult result) {
        String benchmark = result.getParams().getBenchmark();
        return benchmark.substring(benchmark.lastIndexOf('.') + 1);
    }

    private static String parameter(RunResult result, String name, String fallback) {
        return result.getParams().getParamsKeys().contains(name) ? result.getParams().getParam(name) : fallback;
    }

    private static String percentage(RunResult result, String numerator, String denominator) {
        var top = result.getSecondaryResults().get(numerator);
        var bottom = result.getSecondaryResults().get(denominator);
        return top == null || bottom == null || bottom.getScore() == 0 ? "n/a"
                : number(100 * top.getScore() / bottom.getScore()) + "%";
    }

    private static String label(String method) {
        return switch (method) {
            case "bloom" -> "Presence Bloom · Murmur3";
            case "bloomRapidhash" -> "Presence Bloom · rapidhash v3";
            case "bloomRapidhashNano" -> "Presence Bloom · rapidhash Nano v3";
            case "bloomKomihash" -> "Presence Bloom · Komihash 5.34";
            case "bloomSha256" -> "Presence Bloom · SHA-256";
            case "hashSet" -> "JDK HashSet";
            case "hashMap" -> "JDK HashMap";
            case "direct" -> "Direct exact lookup";
            case "bloomThenExact" -> "Bloom → exact lookup";
            default -> method;
        };
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%,.2f", value) : "n/a";
    }

    private static String relative(Path directory, Path file) {
        return escape(directory.toUri().relativize(file.toUri()).toASCIIString());
    }

    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
