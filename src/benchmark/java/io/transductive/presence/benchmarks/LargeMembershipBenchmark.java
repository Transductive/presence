package io.transductive.presence.benchmarks;

import io.transductive.presence.bloom.BlockedBloomFilter;
import io.transductive.presence.bloom.BloomFilter;
import io.transductive.presence.bloom.HashingStrategy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.BenchmarkParams;

/** Large resident structures with a bounded common query pool and one contender per trial. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class LargeMembershipBenchmark {
    @Param({"1000", "10000000", "50000000"})
    public int N;

    @Param({"0"})
    public int hitPercent;

    @Param({"1048576"})
    public int queryCount = LargeBenchmarkData.DEFAULT_QUERY_COUNT;

    @Param({"CACHED", "FRESH"})
    public String queryMode = "CACHED";

    @Param({"9.89453125"})
    public double mBpk = 9.89453125;

    @Param({"6"})
    public int mK = 6;

    @Param({"9.904296875"})
    public double rBpk = 9.904296875;

    @Param({"6"})
    public int rK = 6;

    private BloomFilter<String> standard;
    private BlockedBloomFilter blocked;
    private HashSet<String> set;
    private HashMap<String, Boolean> map;
    private String[] queries;
    private char[][] freshQueries;
    private int cursor;
    private int queryMask;

    @Setup
    public void setup(BenchmarkParams params) throws IOException {
        String benchmark = params.getBenchmark();
        setupForMethod(benchmark.substring(benchmark.lastIndexOf('.') + 1));
    }

    void setupForMethod(String method) throws IOException {
        LargeBenchmarkData.requireSize(N);
        LargeBenchmarkData.requireQueryCount(queryCount);
        if (!Double.isFinite(mBpk) || mBpk <= 0
                || !Double.isFinite(rBpk) || rBpk <= 0
                || mK < 1 || mK > 512 || rK < 1 || rK > 512) {
            throw new IllegalArgumentException("Blocked parameters must be finite positive bits/key and 1..512 probes");
        }
        long started = System.nanoTime();
        queries = LargeBenchmarkData.queries(N, hitPercent, queryCount);
        freshQueries = QueryPreparation.characters(queries, queryMode);
        queryMask = queryCount - 1;
        if (freshQueries != null) {
            queries = null;
        }
        // No shared inserted-key array, unused filters, or other exact collection is retained.
        standard = null;
        blocked = null;
        set = null;
        map = null;
        switch (method) {
            case "standardMurmur3" -> standard = BloomFilter.forStrings(N, 0.01, HashingStrategy.MURMUR3_128);
            case "standardRapidhash" -> standard = BloomFilter.forStrings(N, 0.01, HashingStrategy.RAPIDHASH_V3);
            case "blockedMurmur3" -> blocked = new BlockedBloomFilter(N, mBpk, mK, HashingStrategy.MURMUR3_128);
            case "blockedRapidhash" -> blocked = new BlockedBloomFilter(N, rBpk, rK, HashingStrategy.RAPIDHASH_V3);
            case "hashSet" -> set = HashSet.newHashSet(N);
            case "hashMap" -> map = HashMap.newHashMap(N);
            default -> throw new IllegalArgumentException("Unknown large benchmark method: " + method);
        }
        long buildStarted = System.nanoTime();
        for (int i = 0; i < N; i++) {
            String key = LargeBenchmarkData.key(i);
            if (standard != null) {
                standard.put(key);
            } else if (blocked != null) {
                blocked.put(key);
            } else if (set != null) {
                set.add(key);
            } else {
                map.put(key, Boolean.TRUE);
            }
        }
        long buildNanos = System.nanoTime() - buildStarted;
        cursor = 0;
        writeSetupMetadata(method, started, buildNanos);
    }

    private String nextQuery() {
        String query = freshQueries == null ? queries[cursor] : new String(freshQueries[cursor]);
        cursor = (cursor + 1) & queryMask;
        return query;
    }

    @Benchmark
    public boolean standardMurmur3() {
        return standard.mightContain(nextQuery());
    }

    @Benchmark
    public boolean standardRapidhash() {
        return standard.mightContain(nextQuery());
    }

    @Benchmark
    public boolean blockedMurmur3() {
        return blocked.mightContain(nextQuery());
    }

    @Benchmark
    public boolean blockedRapidhash() {
        return blocked.mightContain(nextQuery());
    }

    @Benchmark
    public boolean hashSet() {
        return set.contains(nextQuery());
    }

    @Benchmark
    public boolean hashMap() {
        return map.containsKey(nextQuery());
    }

    private void writeSetupMetadata(String method, long started, long buildNanos) throws IOException {
        String output = System.getProperty("presence.large.setupDirectory");
        if (output == null) {
            return;
        }
        Path directory = Path.of(output);
        if (!directory.isAbsolute()) {
            throw new IllegalArgumentException("presence.large.setupDirectory must be absolute");
        }
        Files.createDirectories(directory);
        long setupBeforeGcNanos = System.nanoTime() - started;
        System.gc(); // Trial setup only, before JMH warmup. This is not an exclusive-retention measurement.
        Runtime runtime = Runtime.getRuntime();
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        long bitSize = standard != null ? standard.bitSize() : blocked != null ? blocked.bitSize() : 0;
        long storageBytes = standard != null ? ((bitSize + 63) >>> 6) * Long.BYTES
                : blocked != null ? blocked.storageBytes() : 0;
        String json = "{\n  \"method\": " + LargeBenchmarkData.json(method) + ",\n  \"N\": " + N
                + ",\n  \"queryMode\": " + LargeBenchmarkData.json(queryMode) + ",\n  \"hitPercent\": " + hitPercent
                + ",\n  \"queryCount\": " + queryCount
                + ",\n  \"murmurBlockedBitsPerKey\": " + mBpk
                + ",\n  \"murmurBlockedHashCount\": " + mK
                + ",\n  \"rapidBlockedBitsPerKey\": " + rBpk
                + ",\n  \"rapidBlockedHashCount\": " + rK
                + ",\n  \"buildNanos\": " + buildNanos + ",\n  \"setupBeforeGcNanos\": " + setupBeforeGcNanos
                + ",\n  \"setupIncludingGcNanos\": " + (System.nanoTime() - started)
                + ",\n  \"maxHeapBytes\": " + runtime.maxMemory() + ",\n  \"postGcUsedHeapBytes\": " + heapUsed
                + ",\n  \"heapScope\": \"post-GC JVM heap including selected structure, query pool, and harness; GC is requested, not guaranteed\""
                + ",\n  \"logicalBits\": " + bitSize + ",\n  \"bitArrayPayloadBytes\": " + storageBytes
                + ",\n  \"blockCount\": " + (blocked == null ? 0 : blocked.blockCount())
                + ",\n  \"hashFunctionCount\": " + (standard != null ? standard.hashFunctionCount() : blocked != null ? blocked.hashFunctionCount() : 0)
                + ",\n  \"keyGenerator\": \"presence-key- + unsigned hex of bijective64(id); streamed inserted IDs0..N-1\"\n}\n";
        Files.writeString(directory.resolve("setup-" + UUID.randomUUID() + ".json"), json, StandardOpenOption.CREATE_NEW);
    }
}
