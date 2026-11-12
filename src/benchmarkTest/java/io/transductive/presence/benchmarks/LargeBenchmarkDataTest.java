package io.transductive.presence.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class LargeBenchmarkDataTest {
    @Test
    void randomAccessKeysAreUniqueAndHeldoutDomainsStayDisjoint() {
        var keys = new HashSet<String>();
        for (int i = 0; i < 10_000; i++) {
            assertTrue(keys.add(LargeBenchmarkData.key(i)));
            for (int domain = 1; domain <= 5; domain++) {
                long id = LargeBenchmarkData.absentId(domain, 50_000_000, i);
                assertTrue(id < 0);
                assertTrue(keys.add(LargeBenchmarkData.key(id)));
            }
        }
        assertEquals(60_000, keys.size());
        assertEquals(LargeBenchmarkData.key(500), LargeBenchmarkData.key(500));
        assertNotSame(LargeBenchmarkData.key(500), LargeBenchmarkData.key(500));
    }

    @Test
    void querySchedulesHaveExactHitCountsWithoutStoredObjectIdentity() {
        var stored = new HashMap<String, String>();
        for (int i = 0; i < 1_000; i++) {
            String key = LargeBenchmarkData.key(i);
            stored.put(key, key);
        }
        for (int hitPercent : new int[] {0, 50, 100}) {
            String[] queries = LargeBenchmarkData.queries(1_000, hitPercent, 1_024);
            int hits = 0;
            for (String query : queries) {
                String original = stored.get(query);
                if (original != null) {
                    hits++;
                    assertNotSame(original, query);
                }
            }
            assertEquals(1_024 * hitPercent / 100, hits);
            assertTrue(Arrays.equals(queries, LargeBenchmarkData.queries(1_000, hitPercent, 1_024)));
        }
    }

    @Test
    void rejectsInvalidSizesDomainsAndPoolBounds() {
        assertThrows(IllegalArgumentException.class, () -> LargeBenchmarkData.queries(999, 0, 1_024));
        assertThrows(IllegalArgumentException.class, () -> LargeBenchmarkData.queries(1_000, 20, 1_024));
        assertThrows(IllegalArgumentException.class, () -> LargeBenchmarkData.requireQueryCount(2));
        assertThrows(IllegalArgumentException.class, () -> LargeBenchmarkData.requireQueryCount(3_000));
        assertThrows(IllegalArgumentException.class, () -> LargeBenchmarkData.requireQueryCount(8_388_608));
        assertThrows(IllegalArgumentException.class, () -> LargeBenchmarkData.absentId(128, 1_000, 0));
        assertThrows(IllegalArgumentException.class, () -> LargeBenchmarkData.absentId(1, 1_000, LargeBenchmarkData.MAX_MISSES));
        assertThrows(IllegalArgumentException.class, () -> LargeBenchmarkData.requireMissCount(1_999_999));
    }

    @Test
    void calibrationRoundsNeverReuseObservedOrValidationDomains() {
        var used = new HashSet<Integer>(java.util.List.of(1, 2, 3, 4, 5));
        for (int round = 0; round <= 60; round++) {
            var domains = LargeAccuracyRunner.calibrationDomains(Integer.toString(round));
            assertEquals(round, domains.round());
            assertEquals(6 + 2 * round, domains.training());
            assertEquals(7 + 2 * round, domains.heldout());
            assertTrue(used.add(domains.training()));
            assertTrue(used.add(domains.heldout()));
            assertTrue(LargeBenchmarkData.absentId(domains.heldout(), 1_000_000, 7_999_999) < 0);
        }
        assertEquals(127, used.size());
        assertThrows(IllegalArgumentException.class, () -> LargeAccuracyRunner.calibrationDomains("-1"));
        assertThrows(IllegalArgumentException.class, () -> LargeAccuracyRunner.calibrationDomains("61"));
        assertThrows(IllegalArgumentException.class, () -> LargeAccuracyRunner.calibrationDomains("unknown"));
    }
}
