package io.transductive.presence.benchmarks;

import io.transductive.presence.bloom.BloomFilter;
import io.transductive.presence.bloom.HashingStrategy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Single-threaded membership checks against the same prebuilt data and query order. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class MembershipBenchmark {
    @Param({"1000", "10000", "100000", "500000", "1000000"})
    public int N;

    @Param({"0", "50", "100"})
    public int hitPercent;

    @Param({"ASCII"})
    public String inputKind = "ASCII";

    @Param({"HOT"})
    public String workingSet = "HOT";

    @Param({"CACHED"})
    public String queryMode = "CACHED";

    private BloomFilter<String> bloom;
    private BloomFilter<String> bloomRapidhash;
    private BloomFilter<String> bloomRapidhashNano;
    private BloomFilter<String> bloomKomihash;
    private BloomFilter<String> bloomSha256;
    private HashSet<String> set;
    private HashMap<String, Boolean> map;
    private String[] queries;
    private char[][] freshQueries;
    private int queryMask;
    private int cursor;

    @Setup
    public void setup() {
        String[] keys = BenchmarkData.keys(N, inputKind);
        queries = BenchmarkData.queries(keys, hitPercent, inputKind, BenchmarkData.queryCount(N, workingSet));
        freshQueries = QueryPreparation.characters(queries, queryMode);
        queryMask = queries.length - 1;
        if (freshQueries != null) {
            queries = null;
        }
        bloom = BloomFilter.forStrings(N, 0.01, HashingStrategy.MURMUR3_128);
        bloomRapidhash = BloomFilter.forStrings(N, 0.01, HashingStrategy.RAPIDHASH_V3);
        bloomRapidhashNano = BloomFilter.forStrings(N, 0.01, HashingStrategy.RAPIDHASH_NANO_V3);
        bloomKomihash = BloomFilter.forStrings(N, 0.01, HashingStrategy.KOMIHASH_5);
        bloomSha256 = BloomFilter.forStrings(N, 0.01, HashingStrategy.SHA_256);
        set = HashSet.newHashSet(N);
        map = HashMap.newHashMap(N);
        for (String key : keys) {
            bloom.put(key);
            bloomRapidhash.put(key);
            bloomRapidhashNano.put(key);
            bloomKomihash.put(key);
            bloomSha256.put(key);
            set.add(key);
            map.put(key, Boolean.TRUE);
        }
        cursor = 0;
    }

    private String nextQuery() {
        String query = freshQueries == null ? queries[cursor] : new String(freshQueries[cursor]);
        cursor = (cursor + 1) & queryMask;
        return query;
    }

    @Benchmark
    public boolean bloom() {
        return bloom.mightContain(nextQuery());
    }

    @Benchmark
    public boolean bloomRapidhash() {
        return bloomRapidhash.mightContain(nextQuery());
    }

    @Benchmark
    public boolean bloomRapidhashNano() {
        return bloomRapidhashNano.mightContain(nextQuery());
    }

    @Benchmark
    public boolean bloomKomihash() {
        return bloomKomihash.mightContain(nextQuery());
    }

    @Benchmark
    public boolean bloomSha256() {
        return bloomSha256.mightContain(nextQuery());
    }

    @Benchmark
    public boolean hashSet() {
        return set.contains(nextQuery());
    }

    @Benchmark
    public boolean hashMap() {
        return map.containsKey(nextQuery());
    }
}
