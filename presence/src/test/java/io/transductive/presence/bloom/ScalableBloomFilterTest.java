package io.transductive.presence.bloom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ScalableBloomFilterTest {
    @Test
    void emptyFilterHasOneLayerAndQueriesDoNotChangeIt() {
        var filter = ScalableBloomFilter.forStrings(1_000, 0.01);
        long bits = filter.bitSize();
        assertEquals(HashingStrategy.MURMUR3_128, filter.hashingStrategy());
        assertEquals(0.01, filter.targetFalsePositiveProbability());
        for (int i = 0; i < 10_000; i++) assertFalse(filter.mightContain("absent-" + i));
        assertFalse(filter.mightContain(""));
        assertEquals(1, filter.layerCount());
        assertEquals(bits, filter.bitSize());
        assertEquals(0, filter.estimatedFalsePositiveProbability());
    }

    @ParameterizedTest
    @EnumSource(HashingStrategy.class)
    void retainsEveryValueThroughMultipleGrowths(HashingStrategy strategy) {
        var filter = ScalableBloomFilter.forStrings(1, 0.01, strategy);
        filter.put("");
        int lastLayerCount = 1;
        for (int i = 0; i < 3_000; i++) {
            filter.put("member-" + i);
            assertTrue(filter.mightContain("member-" + i));
            if (filter.layerCount() != lastLayerCount) {
                for (int previous = 0; previous <= i; previous++) {
                    assertTrue(filter.mightContain("member-" + previous), "lost member at growth " + i);
                }
                lastLayerCount = filter.layerCount();
            }
            assertTrue(filter.estimatedFalsePositiveProbability() <= 0.01);
        }
        assertTrue(filter.layerCount() >= 6);
        assertTrue(filter.layerCount() <= 10);
        assertTrue(filter.mightContain(""));
        for (int i = 0; i < 3_000; i++) assertTrue(filter.mightContain("member-" + i));
    }

    @Test
    void repeatedInsertsIntoOldAndNewLayersDoNotConsumeCapacity() {
        var filter = ScalableBloomFilter.forStrings(1, 0.01);
        for (int i = 0; i < 2_000; i++) filter.put("member-" + i);
        int layers = filter.layerCount();
        long bits = filter.bitSize();
        double probability = filter.estimatedFalsePositiveProbability();
        for (int i = 0; i < 20_000; i++) filter.put("member-" + (i % 2_000));
        assertEquals(layers, filter.layerCount());
        assertEquals(bits, filter.bitSize());
        assertEquals(probability, filter.estimatedFalsePositiveProbability());
    }

    @Test
    void aFalsePositiveAcceptedByPutRemainsDiscoverableAfterGrowth() {
        var filter = ScalableBloomFilter.forStrings(1, 0.1);
        for (int i = 0; i < 200; i++) filter.put("member-" + i);
        String collision = null;
        for (int i = 0; i < 100_000; i++) {
            String candidate = "never-inserted-" + i;
            if (filter.mightContain(candidate)) {
                collision = candidate;
                break;
            }
        }
        assertTrue(collision != null, "deterministic fixture must contain a false positive");
        long bits = filter.bitSize();
        double probability = filter.estimatedFalsePositiveProbability();
        filter.put(collision);
        assertEquals(bits, filter.bitSize());
        assertEquals(probability, filter.estimatedFalsePositiveProbability());
        int layers = filter.layerCount();
        for (int i = 200; i < 10_000; i++) filter.put("member-" + i);
        assertTrue(filter.layerCount() > layers);
        assertTrue(filter.mightContain(collision));
    }

    @ParameterizedTest
    @CsvSource({"2,0.5", "2,0.9", "4,0.5", "4,0.9"})
    void preservesTheGlobalBudgetAtEveryGrowthBoundary(int growth, double tightening) {
        double target = 0.01;
        var filter = ScalableBloomFilter.forStrings(1, target, HashingStrategy.MURMUR3_128, growth, tightening);
        long previousBits = filter.bitSize();
        int previousLayers = 1;
        for (int i = 0; i < 20_000; i++) {
            filter.put("value-" + i);
            assertTrue(filter.estimatedFalsePositiveProbability() <= target, "global budget exceeded at " + i);
            assertTrue(filter.layerCount() >= previousLayers);
            if (filter.layerCount() > previousLayers) {
                assertEquals(previousLayers + 1, filter.layerCount());
                assertTrue(filter.bitSize() > previousBits);
                previousBits = filter.bitSize();
                previousLayers = filter.layerCount();
            }
        }
        assertTrue(filter.layerCount() > 3);
        assertTrue(filter.layerCount() < 12);
        for (int i = 0; i < 20_000; i++) assertTrue(filter.mightContain("value-" + i));
    }

    @ParameterizedTest
    @CsvSource({"MURMUR3_128,0.1", "MURMUR3_128,0.01", "MURMUR3_128,0.001",
            "RAPIDHASH_V3,0.1", "RAPIDHASH_V3,0.01", "RAPIDHASH_V3,0.001",
            "RAPIDHASH_NANO_V3,0.1", "RAPIDHASH_NANO_V3,0.01", "RAPIDHASH_NANO_V3,0.001",
            "KOMIHASH_5,0.1", "KOMIHASH_5,0.01", "KOMIHASH_5,0.001",
            "SHA_256,0.1", "SHA_256,0.01", "SHA_256,0.001"})
    void observedFalsePositivesStayWithinTheWholeFilterTarget(HashingStrategy strategy, double target) {
        var filter = ScalableBloomFilter.forStrings(32, target, strategy);
        for (int i = 0; i < 5_000; i++) filter.put("member-" + i);
        assertTrue(filter.layerCount() >= 5);
        int queries = 50_000;
        int positives = 0;
        var random = new Random(420);
        for (int i = 0; i < queries; i++) {
            if (filter.mightContain("absent-" + random.nextLong())) positives++;
        }
        // Fixed disjoint data; six binomial standard deviations above the target.
        // Detects per-layer budgeting mistakes, without asserting an exact sample rate.
        double allowance = target + 6 * Math.sqrt(target * (1 - target) / queries) + 1.0 / queries;
        double rate = (double) positives / queries;
        assertTrue(rate < allowance, () -> strategy + " target=" + target + " observed=" + rate);
    }

    @ParameterizedTest
    @EnumSource(HashingStrategy.class)
    void stringAndByteFactoriesAgreeAcrossGrowthAndUtf8Boundaries(HashingStrategy strategy) {
        var strings = ScalableBloomFilter.forStrings(1, 0.01, strategy);
        var bytes = ScalableBloomFilter.<byte[]>create(1, 0.01, Function.identity(), strategy);
        String[] prefixes = {"", "ascii", "é", "世界🌱", "\ud800x", "\udc00", "a".repeat(300), "λ🌱".repeat(100)};
        for (int i = 0; i < 500; i++) {
            String value = prefixes[i % prefixes.length] + i;
            strings.put(value);
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            bytes.put(encoded);
            java.util.Arrays.fill(encoded, (byte) 0); // No retained caller-owned arrays.
        }
        assertTrue(strings.layerCount() > 1);
        assertEquals(strings.layerCount(), bytes.layerCount());
        assertEquals(strings.bitSize(), bytes.bitSize());
        for (int i = 0; i < 1_500; i++) {
            String value = prefixes[i % prefixes.length] + i;
            boolean actual = strings.mightContain(value);
            if (i < 500) assertTrue(actual);
            assertEquals(bytes.mightContain(value.getBytes(StandardCharsets.UTF_8)), actual);
        }
    }

    @Test
    void encoderIsCalledOnceEvenWhenManyLayersAreSearched() {
        var calls = new AtomicInteger();
        record Member(int id, String name) { }
        var filter = ScalableBloomFilter.<Member>create(1, 0.01, member -> {
            calls.incrementAndGet();
            return ByteBuffer.allocate(4).putInt(member.id()).array();
        });
        for (int i = 0; i < 1_000; i++) filter.put(new Member(i, "inserted"));
        assertEquals(1_000, calls.get());
        assertTrue(filter.layerCount() > 1);
        assertTrue(filter.mightContain(new Member(0, "different display name")));
        assertEquals(1_001, calls.get());
        filter.mightContain(new Member(-1, "absent"));
        assertEquals(1_002, calls.get());
    }

    @Test
    void queriesAndInstancesDoNotShareOrMutateMembership() {
        var first = ScalableBloomFilter.forStrings(1, 0.01);
        var second = ScalableBloomFilter.forStrings(1, 0.01);
        for (int i = 0; i < 1_000; i++) first.put("member-" + i);
        int layers = first.layerCount();
        long bits = first.bitSize();
        double probability = first.estimatedFalsePositiveProbability();
        for (int i = 0; i < 10_000; i++) first.mightContain("query-" + i);
        assertEquals(layers, first.layerCount());
        assertEquals(bits, first.bitSize());
        assertEquals(probability, first.estimatedFalsePositiveProbability());
        assertFalse(second.mightContain("member-0"));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE, Long.MAX_VALUE, 100_000_000, 1_000_000_000})
    void rejectsInvalidOrUnsupportedInitialCapacity(long capacity) {
        assertThrows(IllegalArgumentException.class, () -> ScalableBloomFilter.forStrings(capacity, 0.01));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, -0.1, 1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsInvalidProbabilitiesAndRatios(double probability) {
        assertThrows(IllegalArgumentException.class, () -> ScalableBloomFilter.forStrings(1, probability));
        assertThrows(IllegalArgumentException.class,
                () -> ScalableBloomFilter.forStrings(1, 0.01, HashingStrategy.MURMUR3_128, 2, probability));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 3, Integer.MAX_VALUE})
    void rejectsUnsupportedGrowthFactors(int growth) {
        assertThrows(IllegalArgumentException.class,
                () -> ScalableBloomFilter.forStrings(1, 0.01, HashingStrategy.MURMUR3_128, growth, 0.9));
    }

    @Test
    void extremeValidProbabilitiesDoNotUnderflowSizing() {
        for (double probability : new double[]{Double.MIN_VALUE, Double.MIN_NORMAL, Math.nextDown(1.0)}) {
            var filter = ScalableBloomFilter.forStrings(1, probability);
            for (int i = 0; i < 100; i++) filter.put("member-" + i);
            assertTrue(filter.layerCount() > 1);
            for (int i = 0; i < 100; i++) assertTrue(filter.mightContain("member-" + i));
            assertTrue(filter.estimatedFalsePositiveProbability() <= probability);
        }
        for (double ratio : new double[]{Double.MIN_VALUE, Math.nextDown(1.0)}) {
            var filter = ScalableBloomFilter.forStrings(1, 0.01, HashingStrategy.MURMUR3_128, 2, ratio);
            for (int i = 0; i < 100; i++) filter.put("member-" + i);
            for (int i = 0; i < 100; i++) assertTrue(filter.mightContain("member-" + i));
            assertTrue(filter.estimatedFalsePositiveProbability() <= 0.01);
        }
    }

    @Test
    void rejectsNullInputsAndPreservesStateAfterEncoderFailures() {
        assertThrows(NullPointerException.class, () -> ScalableBloomFilter.create(1, 0.01, null));
        assertThrows(NullPointerException.class, () -> ScalableBloomFilter.forStrings(1, 0.01, null));
        var calls = new AtomicInteger();
        var filter = ScalableBloomFilter.<String>create(1, 0.01, value -> {
            calls.incrementAndGet();
            if (value.equals("null")) return null;
            if (value.equals("throw")) throw new IllegalArgumentException("bad encoding");
            return value.getBytes(StandardCharsets.UTF_8);
        });
        assertThrows(NullPointerException.class, () -> filter.put(null));
        assertThrows(NullPointerException.class, () -> filter.mightContain(null));
        assertEquals(0, calls.get());
        filter.put("valid");
        double before = filter.estimatedFalsePositiveProbability();
        assertThrows(NullPointerException.class, () -> filter.put("null"));
        assertThrows(NullPointerException.class, () -> filter.mightContain("null"));
        assertThrows(IllegalArgumentException.class, () -> filter.put("throw"));
        assertThrows(IllegalArgumentException.class, () -> filter.mightContain("throw"));
        assertEquals(before, filter.estimatedFalsePositiveProbability());
        assertTrue(filter.mightContain("valid"));
    }
}
