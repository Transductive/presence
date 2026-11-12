package io.transductive.presence.benchmarks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import jdk.jfr.consumer.RecordingFile;

/** Converts saved JFR recordings into namespace-focused flamegraphs without rerunning JMH. */
public final class ProfileRenderer {
    private ProfileRenderer() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || args[0].isBlank()) {
            throw new IllegalArgumentException("Supply a benchmark run directory with -PprofileRun=build/reports/benchmarks/<run-id>");
        }
        Path directory = Path.of(args[0]).toAbsolutePath();
        List<Path> recordings;
        try (var files = Files.walk(directory.resolve("jfr"))) {
            recordings = files.filter(path -> path.toString().endsWith(".jfr")).sorted().toList();
        }
        if (recordings.isEmpty()) {
            throw new IllegalArgumentException("No JFR recordings found in " + directory);
        }
        for (Path recording : recordings) {
            Path labelFile = recording.resolveSibling("benchmark.txt");
            String label = Files.isRegularFile(labelFile) ? Files.readString(labelFile).strip() : recording.getParent().getFileName().toString();
            var profile = render(recording, label);
            System.out.println("Open flamegraph: " + profile.flamegraph().toUri().toASCIIString());
            System.out.println("Local path: " + profile.flamegraph());
        }
        System.out.println("Rendered " + recordings.size() + " profiles with io.transductive frames only. Full JFR recordings are unchanged.");
    }

    static BenchmarkReport.Profile render(Path recording, String label) throws Exception {
        Path flame = recording.resolveSibling("flamegraph.html");
        Path full = recording.resolveSibling("stacks.full.collapsed");
        Path collapsed = recording.resolveSibling("stacks.collapsed");
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        long sourceSamples = benchmarkSamples(recording);
        // JDK execution samples use STATE_RUNNABLE; --cpu would drop them.
        Process converter = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                "one.convert.Main", "--state", "RUNNABLE", "--include",
                ".*(Membership|Construction|Prefilter)Benchmark.*", "--output", "collapsed",
                recording.toString(), full.toString()).inheritIO().start();
        if (converter.waitFor() != 0 || !Files.isRegularFile(full)) {
            throw new IllegalStateException("Stack conversion failed for " + recording);
        }
        long focusedSamples = ProfileFrames.transductiveOnly(full, collapsed);
        converter = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                "one.convert.Main", "--title", BenchmarkReport.escape(label + " · io.transductive frames only"),
                collapsed.toString(), flame.toString()).inheritIO().start();
        if (converter.waitFor() != 0 || !Files.isRegularFile(flame)) {
            throw new IllegalStateException("Flamegraph conversion failed for " + recording);
        }
        String html = Files.readString(flame);
        if (!html.contains("</head>") || !html.contains("</header>")) {
            throw new IllegalStateException("Unexpected flamegraph HTML for " + recording);
        }
        long samples = ProfileSamples.convertedCount(collapsed, html, sourceSamples);
        if (samples != focusedSamples) {
            throw new IllegalStateException("Focused sample count differs from the rendered graph");
        }
        html = html.replace("</head>", "<style>html { filter: grayscale(1); } .scope { font-size:12px; margin:8px 0; text-align:center; }</style>\n</head>")
                .replace("</header>", "</header><div class=\"scope\">Only io.transductive frames are shown. Hidden callees still contribute to visible callers' sample widths. "
                        + "<a href=\"profile.jfr\">Full JFR</a> · <a href=\"stacks.full.collapsed\">Full benchmark stacks</a></div>");
        Files.writeString(flame, html);
        return new BenchmarkReport.Profile(label, recording, flame, collapsed, samples);
    }

    private static long benchmarkSamples(Path recording) throws Exception {
        long samples = 0;
        try (var file = new RecordingFile(recording)) {
            while (file.hasMoreEvents()) {
                var event = file.readEvent();
                if (event.getEventType().getName().equals("jdk.ExecutionSample")
                        && event.getStackTrace() != null
                        && event.getStackTrace().getFrames().stream().anyMatch(frame ->
                        frame.getMethod().getType().getName().contains("MembershipBenchmark")
                                || frame.getMethod().getType().getName().contains("ConstructionBenchmark")
                                || frame.getMethod().getType().getName().contains("PrefilterBenchmark"))) {
                    samples++;
                }
            }
        }
        return samples;
    }
}
