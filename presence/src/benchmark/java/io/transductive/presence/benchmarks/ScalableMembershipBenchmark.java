package io.transductive.presence.benchmarks;

import io.transductive.presence.bloom.BloomFilter;
import io.transductive.presence.bloom.HashingStrategy;
import io.transductive.presence.bloom.ScalableBloomFilter;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Known-final-size Bloom versus growth from an initial estimate of 1,000 keys. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ScalableMembershipBenchmark {
    static final int INITIAL_CAPACITY = 1_000;
    static final double TARGET = 0.01;

    @Param({"1000", "10000", "100000"})
    public int N;
    @Param({"0", "100"})
    public int hitPercent;
    @Param({"ASCII"})
    public String inputKind = "ASCII";
    @Param({"BROAD"})
    public String workingSet = "BROAD";
    @Param({"CACHED"})
    public String queryMode = "CACHED";

    private Filters murmur;
    private Filters rapid;
    private String[] queries;
    private char[][] freshQueries;
    private int queryMask;
    private int cursor;

    record Filters(BloomFilter<String> fixed, ScalableBloomFilter<String> scalable) { }

    static Filters filters(String[] keys, HashingStrategy strategy) {
        var fixed = BloomFilter.forStrings(keys.length, TARGET, strategy);
        var scalable = ScalableBloomFilter.forStrings(INITIAL_CAPACITY, TARGET, strategy);
        for (String key : keys) {
            fixed.put(key);
            scalable.put(key);
        }
        return new Filters(fixed, scalable);
    }

    @Setup
    public void setup() {
        String[] keys = BenchmarkData.keys(N, inputKind);
        murmur = filters(keys, HashingStrategy.MURMUR3_128);
        rapid = filters(keys, HashingStrategy.RAPIDHASH_V3);
        queries = BenchmarkData.queries(keys, hitPercent, inputKind, BenchmarkData.queryCount(N, workingSet));
        freshQueries = QueryPreparation.characters(queries, queryMode);
        queryMask = queries.length - 1;
        if (freshQueries != null) queries = null;
        cursor = 0;
    }

    private String nextQuery() {
        String query = freshQueries == null ? queries[cursor] : new String(freshQueries[cursor]);
        cursor = (cursor + 1) & queryMask;
        return query;
    }

    @Benchmark
    public boolean fixedMurmur() { return murmur.fixed().mightContain(nextQuery()); }

    @Benchmark
    public boolean fixedRapidhash() { return rapid.fixed().mightContain(nextQuery()); }

    @Benchmark
    public boolean scalableMurmur() { return murmur.scalable().mightContain(nextQuery()); }

    @Benchmark
    public boolean scalableRapidhash() { return rapid.scalable().mightContain(nextQuery()); }
}
