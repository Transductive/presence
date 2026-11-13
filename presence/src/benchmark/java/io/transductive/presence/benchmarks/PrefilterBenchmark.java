package io.transductive.presence.benchmarks;

import io.transductive.presence.bloom.BloomFilter;
import io.transductive.presence.bloom.HashingStrategy;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/** Exact membership with an optional Bloom guard before the same downstream work. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class PrefilterBenchmark {
    @Param({"1000", "10000", "100000", "500000", "1000000"})
    public int N;

    @Param({"0", "50"})
    public int hitPercent;

    @Param({"ASCII"})
    public String inputKind = "ASCII";

    @Param({"HOT"})
    public String workingSet = "HOT";

    @Param({"CACHED"})
    public String queryMode = "CACHED";

    @Param({"0", "256"})
    public int downstreamTokens;

    @Param({"RAPIDHASH_V3"})
    public String strategy = "RAPIDHASH_V3";

    private BloomFilter<String> filter;
    private HashMap<String, Boolean> exact;
    private String[] queries;
    private char[][] freshQueries;
    private int queryMask;
    private int cursor;

    @Setup
    public void setup() {
        if (downstreamTokens < 0) {
            throw new IllegalArgumentException("downstreamTokens must be nonnegative");
        }
        String[] keys = BenchmarkData.keys(N, inputKind);
        queries = BenchmarkData.queries(keys, hitPercent, inputKind, BenchmarkData.queryCount(N, workingSet));
        freshQueries = QueryPreparation.characters(queries, queryMode);
        queryMask = queries.length - 1;
        if (freshQueries != null) {
            queries = null;
        }
        filter = BloomFilter.forStrings(N, 0.01, HashingStrategy.valueOf(strategy));
        exact = HashMap.newHashMap(N);
        for (String key : keys) {
            filter.put(key);
            exact.put(key, Boolean.TRUE);
        }
        cursor = 0;
    }

    private String nextQuery() {
        String query = freshQueries == null ? queries[cursor] : new String(freshQueries[cursor]);
        cursor = (cursor + 1) & queryMask;
        return query;
    }

    private boolean downstream(String query) {
        // Tokens express CPU work, not nanoseconds or simulated network latency.
        if (downstreamTokens != 0) {
            Blackhole.consumeCPU(downstreamTokens);
        }
        return exact.containsKey(query);
    }

    @Benchmark
    public boolean direct(Counters counters) {
        String query = nextQuery();
        counters.queries++;
        counters.downstreamCalls++;
        boolean found = downstream(query);
        if (!found) {
            counters.absentQueries++;
        }
        return found;
    }

    @Benchmark
    public boolean bloomThenExact(Counters counters) {
        String query = nextQuery();
        counters.queries++;
        if (!filter.mightContain(query)) {
            counters.absentQueries++;
            return false;
        }
        // False positives pay the full downstream cost before returning false.
        counters.downstreamCalls++;
        boolean found = downstream(query);
        if (!found) {
            counters.absentQueries++;
            counters.falsePositives++;
        }
        return found;
    }

    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class Counters {
        public long queries;
        public long absentQueries;
        public long downstreamCalls;
        public long falsePositives;

        @Setup(Level.Iteration)
        public void reset() {
            queries = 0;
            absentQueries = 0;
            downstreamCalls = 0;
            falsePositives = 0;
        }
    }
}
