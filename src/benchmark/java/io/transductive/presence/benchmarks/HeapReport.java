package io.transductive.presence.benchmarks;

import io.transductive.presence.bloom.BloomFilter;
import io.transductive.presence.bloom.HashingStrategy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.openjdk.jol.info.GraphLayout;
import org.openjdk.jol.vm.VM;

/** Graph-based live footprints, measured separately from timed JMH runs. */
public final class HeapReport {
    record Footprint(int size, String input, String structure, long reachableBytes, long incrementalBytes,
            long keyPayloadBytes, int bitSize, int probes) { }

    private HeapReport() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected output directory, sizes, input kinds");
        }
        var settings = BenchmarkSettings.parse("full", args[1], "0", ".*", args[2]);
        String runName = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-heap-" + UUID.randomUUID().toString().substring(0, 8);
        Path directory = Path.of(args[0]).toAbsolutePath().resolve(runName);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("run.txt"), settings + System.lineSeparator()
                + BenchmarkEnvironment.describe() + sizingMethod() + System.lineSeparator() + VM.current().details());
        var rows = new ArrayList<Footprint>();
        for (String input : settings.inputs()) {
            for (String size : settings.sizes()) {
                System.out.println("Measuring reachable heap: N=" + size + ", " + input);
                rows.addAll(measure(Integer.parseInt(size), input));
            }
        }
        write(directory, rows);
        System.out.println("Presence heap review is ready. Open this file in your browser:");
        System.out.println("  " + directory.resolve("index.html").toUri().toASCIIString());
        System.out.println("Local path: " + directory.resolve("index.html"));
    }

    static List<Footprint> measure(int size, String input) {
        sizingMethod();
        String[] keys = BenchmarkData.keys(size, input);
        var bloom = BloomFilter.forStrings(size, 0.01, HashingStrategy.RAPIDHASH_V3);
        var set = HashSet.<String>newHashSet(size);
        var map = HashMap.<String, Boolean>newHashMap(size);
        for (String key : keys) {
            bloom.put(key);
            set.add(key);
            map.put(key, Boolean.TRUE);
        }
        long keyGraph = graphSize((Object) keys);
        long keyPayload = keyGraph - VM.current().sizeOf(keys);
        var rows = new ArrayList<Footprint>();
        add(rows, size, input, "Bloom · rapidhash v3", keys, keyGraph, keyPayload, bloom.bitSize(),
                bloom.hashFunctionCount(), bloom);
        add(rows, size, input, "HashSet", keys, keyGraph, keyPayload, 0, 0, set);
        add(rows, size, input, "HashMap", keys, keyGraph, keyPayload, 0, 0, map);
        add(rows, size, input, "Bloom + HashMap", keys, keyGraph, keyPayload, bloom.bitSize(),
                bloom.hashFunctionCount(), bloom, map);
        return rows;
    }

    private static void add(List<Footprint> rows, int size, String input, String name, String[] keys,
            long keyGraph, long keyPayload, int bits, int probes, Object... structures) {
        Object[] union = new Object[structures.length + 1];
        union[0] = keys;
        System.arraycopy(structures, 0, union, 1, structures.length);
        long reachable = graphSize(structures);
        // Subtract total sizes, not object addresses: GC can relocate objects between traversals.
        long incremental = graphSize(union) - keyGraph;
        if (incremental < 0) {
            throw new IllegalStateException("Negative graph-size difference");
        }
        rows.add(new Footprint(size, input, name, reachable, incremental, keyPayload, bits, probes));
    }

    private static long graphSize(Object... roots) {
        return GraphLayout.parseInstance(roots).totalSize();
    }

    static String sizingMethod() {
        // JOL 0.17 exposes no public instrumentation-status API. Check its pinned implementation
        // before reporting bytes, rather than silently accepting the layout-estimation fallback.
        var vm = VM.current();
        try {
            var field = vm.getClass().getDeclaredField("instrumentation");
            field.setAccessible(true);
            if (field.get(vm) instanceof java.lang.instrument.Instrumentation) {
                return "Object sizing: Instrumentation.getObjectSize via JOL 0.17; identity-based graph traversal";
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot verify JOL 0.17 instrumentation support", exception);
        }
        throw new IllegalStateException("JOL instrumentation unavailable. Use the benchmarkHeap Gradle task, or run Java 21 "
                + "with -Djdk.attach.allowAttachSelf=true -XX:+EnableDynamicAgentLoading");
    }

    private static void write(Path directory, List<Footprint> rows) throws Exception {
        String template;
        try (var stream = HeapReport.class.getResourceAsStream("/heap-report.html")) {
            if (stream == null) {
                throw new IllegalStateException("Missing heap report template");
            }
            template = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var html = new StringBuilder();
        var csv = new StringBuilder("N,input,structure,reachable_bytes,incremental_bytes,key_payload_bytes,bloom_bits,probes\n");
        for (Footprint row : rows) {
            html.append("<tr><td>").append(String.format(Locale.ROOT, "%,d", row.size())).append("</td><td>")
                    .append(BenchmarkReport.escape(row.input())).append("</td><th>")
                    .append(BenchmarkReport.escape(row.structure())).append("</th><td>")
                    .append(bytes(row.reachableBytes())).append("</td><td>").append(bytes(row.incrementalBytes()))
                    .append("</td><td>").append(String.format(Locale.ROOT, "%.2f", (double) row.incrementalBytes() / row.size()))
                    .append("</td><td>").append(bytes(row.keyPayloadBytes())).append("</td></tr>");
            csv.append(row.size()).append(',').append(row.input()).append(',').append(row.structure()).append(',')
                    .append(row.reachableBytes()).append(',').append(row.incrementalBytes()).append(',')
                    .append(row.keyPayloadBytes()).append(',').append(row.bitSize()).append(',').append(row.probes()).append('\n');
        }
        Files.writeString(directory.resolve("heap.csv"), csv);
        Files.writeString(directory.resolve("index.html"), template.replace("{{ENVIRONMENT}}",
                BenchmarkReport.escape("Java " + Runtime.version() + " · " + System.getProperty("os.name")
                        + " / " + System.getProperty("os.arch"))).replace("{{ROWS}}", html));
    }

    private static String bytes(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
