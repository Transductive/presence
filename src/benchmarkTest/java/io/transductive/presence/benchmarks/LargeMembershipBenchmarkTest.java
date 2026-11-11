package io.transductive.presence.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class LargeMembershipBenchmarkTest {
    private static final List<String> METHODS = List.of("standardMurmur3", "standardRapidhash", "blockedMurmur3",
            "blockedRapidhash", "hashSet", "hashMap");

    @Test
    void eachMethodRetainsOnlyItsSelectedContenderAndFindsAllHits() throws Exception {
        for (String mode : List.of("CACHED", "FRESH")) {
            for (String method : METHODS) {
                var fixture = new LargeMembershipBenchmark();
                fixture.N = 1_000;
                fixture.queryCount = 1_024;
                fixture.hitPercent = 100;
                fixture.queryMode = mode;
                fixture.setupForMethod(method);
                int retained = 0;
                for (String name : List.of("standard", "blocked", "set", "map")) {
                    if (field(fixture, name) != null) { retained++; }
                }
                assertEquals(1, retained, method);
                assertEquals(mode.equals("CACHED"), field(fixture, "queries") != null);
                assertEquals(mode.equals("FRESH"), field(fixture, "freshQueries") != null);
                BooleanSupplier lookup = lookup(fixture, method);
                for (int i = 0; i < 2_048; i++) { assertTrue(lookup.getAsBoolean(), method); }
            }
        }
    }

    @Test
    void exactCollectionsRejectAllDisjointMissesAndMatchMixedSchedules() throws Exception {
        for (String method : List.of("hashSet", "hashMap")) {
            var fixture = new LargeMembershipBenchmark();
            fixture.N = 1_000;
            fixture.queryCount = 1_024;
            fixture.hitPercent = 0;
            fixture.setupForMethod(method);
            BooleanSupplier lookup = lookup(fixture, method);
            for (int i = 0; i < 1_024; i++) { assertFalse(lookup.getAsBoolean()); }
            fixture.hitPercent = 50;
            fixture.setupForMethod(method);
            int hits = 0;
            for (int i = 0; i < 1_024; i++) { hits += lookup.getAsBoolean() ? 1 : 0; }
            assertEquals(512, hits);
        }
    }

    private static Object field(LargeMembershipBenchmark fixture, String name) throws Exception {
        Field field = LargeMembershipBenchmark.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(fixture);
    }

    private static BooleanSupplier lookup(LargeMembershipBenchmark fixture, String method) {
        return switch (method) {
            case "standardMurmur3" -> fixture::standardMurmur3;
            case "standardRapidhash" -> fixture::standardRapidhash;
            case "blockedMurmur3" -> fixture::blockedMurmur3;
            case "blockedRapidhash" -> fixture::blockedRapidhash;
            case "hashSet" -> fixture::hashSet;
            case "hashMap" -> fixture::hashMap;
            default -> throw new AssertionError(method);
        };
    }
}
