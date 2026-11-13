package io.transductive.presence.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BenchmarkFixturesTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 50, 100})
    void queriesHaveExactHitRatioAndDifferentObjectIdentity(int hitPercent) {
        String[] keys = BenchmarkData.keys(1_000);
        var originals = new HashMap<String, String>();
        for (String key : keys) {
            originals.put(key, key);
        }
        assertEquals(1_000, originals.size());
        String[] queries = BenchmarkData.queries(keys, hitPercent);
        int hits = 0;
        for (String query : queries) {
            String original = originals.get(query);
            if (original != null) {
                hits++;
                assertNotSame(original, query);
            }
        }
        assertEquals(BenchmarkData.QUERY_COUNT * hitPercent / 100, hits);
        assertTrue(Arrays.equals(queries, BenchmarkData.queries(keys, hitPercent)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ASCII", "LATIN1", "UNICODE", "MALFORMED", "LONG_UNICODE"})
    void everyInsertedQueryIsFoundByAllImplementations(String inputKind) {
        var benchmark = new MembershipBenchmark();
        benchmark.N = 1_000;
        benchmark.hitPercent = 100;
        benchmark.inputKind = inputKind;
        benchmark.setup();
        for (int i = 0; i < BenchmarkData.QUERY_COUNT * 2; i++) {
            assertTrue(benchmark.bloom());
            assertTrue(benchmark.bloomRapidhash());
            assertTrue(benchmark.bloomRapidhashNano());
            assertTrue(benchmark.bloomKomihash());
            assertTrue(benchmark.bloomSha256());
            assertTrue(benchmark.hashMap());
            assertTrue(benchmark.hashSet());
        }
    }

    @Test
    void disjointQueriesAreAbsentFromExactCollections() {
        var benchmark = new MembershipBenchmark();
        benchmark.N = 1_000;
        benchmark.hitPercent = 0;
        benchmark.setup();
        for (int i = 0; i < BenchmarkData.QUERY_COUNT; i++) {
            assertFalse(benchmark.hashMap());
            assertFalse(benchmark.hashSet());
        }
    }

    @Test
    void constructionsContainAllKeysAndReturnFreshStructures() {
        var benchmark = new ConstructionBenchmark();
        benchmark.N = 1_000;
        benchmark.setup();
        var bloom = benchmark.bloom();
        var set = benchmark.hashSet();
        var map = benchmark.hashMap();
        assertEquals(1_000, set.size());
        assertEquals(1_000, map.size());
        assertNotSame(set, benchmark.hashSet());
        for (String key : BenchmarkData.keys(1_000)) {
            assertTrue(bloom.mightContain(key));
            assertTrue(set.contains(key));
            assertTrue(map.containsKey(key));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 999})
    void sizeFloorAppliesEvenWhenBypassingTheRunner(int size) {
        assertThrows(IllegalArgumentException.class, () -> BenchmarkData.keys(size));
    }

    @Test
    void settingsAlwaysIncludeSortedBaselineAndRejectInvalidParameters() {
        var settings = BenchmarkSettings.parse("full", "1000000,100000,500000,10000,10000", "100,0,50", ".*");
        assertEquals(List.of("1000", "10000", "100000", "500000", "1000000"), settings.sizes());
        assertEquals(List.of("0", "50", "100"), settings.hits());
        assertThrows(IllegalArgumentException.class, () -> BenchmarkSettings.parse("full", "999", "50", ".*"));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkSettings.parse("full", "1000", "25", ".*"));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkSettings.parse("unknown", "1000", "50", ".*"));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkSettings.parse("allocation", "1000", "50", ".*", ""));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkSettings.parse("allocation", "1000", "50", ".*", "UTF16"));
    }

    @Test
    void reportEscapesValuesForHtmlTextAndAttributes() {
        assertEquals("&lt;a &amp; &quot;b&quot; &#39;c&#39;&gt;", BenchmarkReport.escape("<a & \"b\" 'c'>"));
    }

    @Test
    void broadQueryPoolsScaleAndPreserveExactHitRatios() {
        assertEquals(4_096, BenchmarkData.queryCount(1_000_000, "HOT"));
        assertEquals(65_536, BenchmarkData.queryCount(1_000, "BROAD"));
        assertEquals(131_072, BenchmarkData.queryCount(100_000, "BROAD"));
        assertEquals(1_048_576, BenchmarkData.queryCount(1_000_000, "BROAD"));
        var keys = BenchmarkData.keys(1_000);
        var set = new java.util.HashSet<>(List.of(keys));
        var queries = BenchmarkData.queries(keys, 50, "ASCII", BenchmarkData.queryCount(1_000, "BROAD"));
        assertEquals(32_768, Arrays.stream(queries).filter(set::contains).count());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 50, 100})
    void prefilterReturnsExactAnswersAndChargesEveryPositive(int hitPercent) {
        for (String mode : List.of("CACHED", "FRESH")) {
            var direct = new PrefilterBenchmark();
            direct.N = 1_000;
            direct.hitPercent = hitPercent;
            direct.queryMode = mode;
            direct.setup();
            var filtered = new PrefilterBenchmark();
            filtered.N = 1_000;
            filtered.hitPercent = hitPercent;
            filtered.queryMode = mode;
            filtered.setup();
            var directCounts = new PrefilterBenchmark.Counters();
            var filteredCounts = new PrefilterBenchmark.Counters();
            long hits = 0;
            for (int i = 0; i < BenchmarkData.QUERY_COUNT; i++) {
                boolean expected = direct.direct(directCounts);
                assertEquals(expected, filtered.bloomThenExact(filteredCounts));
                hits += expected ? 1 : 0;
            }
            assertEquals(BenchmarkData.QUERY_COUNT * hitPercent / 100, hits);
            assertEquals(BenchmarkData.QUERY_COUNT, directCounts.downstreamCalls);
            assertEquals(hits + filteredCounts.falsePositives, filteredCounts.downstreamCalls);
            assertEquals(BenchmarkData.QUERY_COUNT - hits, filteredCounts.absentQueries);
            if (hitPercent == 0) {
                assertTrue(filteredCounts.falsePositives > 0);
                assertTrue(filteredCounts.downstreamCalls < BenchmarkData.QUERY_COUNT / 10);
            }
        }
    }

    @Test
    void settingsRejectUnknownQueryDimensions() {
        assertThrows(IllegalArgumentException.class, () -> BenchmarkSettings.parse("misses", "1000", "0", ".*",
                "ASCII", "COLD", "CACHED", "0", "RAPIDHASH_V3"));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkSettings.parse("misses", "1000", "0", ".*",
                "ASCII", "HOT", "UNKNOWN", "0", "RAPIDHASH_V3"));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkSettings.parse("prefilter", "1000", "0", ".*",
                "ASCII", "HOT", "CACHED", "-1", "RAPIDHASH_V3"));
    }
}
