package io.transductive.presence.benchmarks;

import io.transductive.presence.bloom.BloomFilter;
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

/** One operation allocates a fresh structure and populates it with all N keys. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ConstructionBenchmark {
    @Param({"1000", "10000", "100000", "500000", "1000000"})
    public int N;

    private String[] keys;

    @Setup
    public void setup() {
        keys = BenchmarkData.keys(N);
    }

    @Benchmark
    public BloomFilter<String> bloom() {
        var filter = BloomFilter.forStrings(N, 0.01);
        for (String key : keys) {
            filter.put(key);
        }
        return filter;
    }

    @Benchmark
    public HashSet<String> hashSet() {
        var set = HashSet.<String>newHashSet(N);
        for (String key : keys) {
            set.add(key);
        }
        return set;
    }

    @Benchmark
    public HashMap<String, Boolean> hashMap() {
        var map = HashMap.<String, Boolean>newHashMap(N);
        for (String key : keys) {
            map.put(key, Boolean.TRUE);
        }
        return map;
    }
}
