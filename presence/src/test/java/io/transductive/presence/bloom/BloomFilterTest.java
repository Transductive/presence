package io.transductive.presence.bloom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BloomFilterTest {
    private static final Function<String, byte[]> UTF_8 = value -> value.getBytes(StandardCharsets.UTF_8);

    @Test
    void emptyFilterReportsAbsence() {
        var filter = BloomFilter.forStrings(100, 0.01);

        assertFalse(filter.mightContain("presence"));
        assertFalse(filter.mightContain(""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "presence", "naïve", "東京", "🌱", "a\u0000b"})
    void insertedStringsHaveNoFalseNegatives(String value) {
        var filter = BloomFilter.forStrings(100, 0.01);

        filter.put(value);

        assertTrue(filter.mightContain(value));
    }

    @Test
    void duplicateInsertionPreservesMembershipBehavior() {
        var once = BloomFilter.forStrings(100, 0.01);
        var repeated = BloomFilter.forStrings(100, 0.01);
        once.put("presence");
        for (int i = 0; i < 100; i++) {
            repeated.put("presence");
        }

        assertTrue(repeated.mightContain("presence"));
        for (int i = 0; i < 1_000; i++) {
            String query = "query-" + i;
            assertEquals(once.mightContain(query), repeated.mightContain(query));
        }
    }

    @Test
    void saturatedFilterDemonstratesFalsePositive() {
        var filter = new BloomFilter<>(1, 3, UTF_8);
        assertFalse(filter.mightContain("never inserted"));

        filter.put("inserted");

        assertTrue(filter.mightContain("inserted"));
        assertTrue(filter.mightContain("never inserted"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 63, 64, 65, 127, 128, 129, 10_000})
    void preservesAllInsertedValuesAcrossStorageWordBoundaries(int bitSize) {
        var filter = new BloomFilter<byte[]>(bitSize, 7, Function.identity());
        var random = new Random(420);
        List<byte[]> values = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            byte[] value = new byte[i % 97];
            random.nextBytes(value);
            values.add(value);
            filter.put(value);
        }

        for (byte[] value : values) {
            assertTrue(filter.mightContain(value.clone()));
        }
        assertEquals(bitSize, filter.bitSize());
    }

    @Test
    void queryDoesNotInsertValue() {
        var filter = BloomFilter.forStrings(100, 0.01);
        for (int i = 0; i < 10; i++) {
            assertFalse(filter.mightContain("query only"));
        }
    }

    @Test
    void filtersDoNotShareState() {
        var first = BloomFilter.forStrings(100, 0.01);
        var second = BloomFilter.forStrings(100, 0.01);
        first.put("presence");

        assertTrue(first.mightContain("presence"));
        assertFalse(second.mightContain("presence"));
    }

    @Test
    void customEncoderDefinesLogicalIdentity() {
        record Member(long id, String displayName) { }
        var filter = BloomFilter.<Member>create(100, 0.01,
                member -> ByteBuffer.allocate(Long.BYTES).putLong(member.id()).array());
        filter.put(new Member(42, "original name"));

        assertTrue(filter.mightContain(new Member(42, "updated name")));
    }

    @Test
    void encoderRunsOncePerOperation() {
        var calls = new AtomicInteger();
        var filter = BloomFilter.<String>create(100, 0.01, value -> {
            calls.incrementAndGet();
            return UTF_8.apply(value);
        });

        filter.put("presence");
        assertEquals(1, calls.get());
        assertTrue(filter.mightContain("presence"));
        assertEquals(2, calls.get());
    }

    @Test
    void doesNotRetainInputArrays() {
        var filter = BloomFilter.<byte[]>create(100, 0.01, Function.identity());
        byte[] original = {1, 2, 3};
        filter.put(original);
        original[0] = 99;

        assertTrue(filter.mightContain(new byte[] {1, 2, 3}));
    }

    @Test
    void computesConventionalSizing() {
        var filter = BloomFilter.forStrings(1_000, 0.01);

        assertEquals(9_586, filter.bitSize());
        assertEquals(7, filter.hashFunctionCount());
    }

    @Test
    void nearOneProbabilityStillAllocatesAtLeastOneBitAndHash() {
        var filter = BloomFilter.forStrings(1, Math.nextDown(1.0));

        assertEquals(1, filter.bitSize());
        assertEquals(1, filter.hashFunctionCount());
        filter.put("value");
        assertTrue(filter.mightContain("value"));
    }

    @Test
    void verySmallValidProbabilityRemainsUsable() {
        var filter = BloomFilter.forStrings(1, Double.MIN_VALUE);
        filter.put("value");

        assertTrue(filter.mightContain("value"));
        assertTrue(filter.hashFunctionCount() > 1);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsNonpositiveExpectedInsertions(long count) {
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.forStrings(count, 0.01));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -0.1, 1.0, 1.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsInvalidProbabilities(double probability) {
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.forStrings(100, probability));
    }

    @Test
    void rejectsOversizedFiltersBeforeAllocating() {
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.forStrings(Long.MAX_VALUE, 0.01));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.forStrings(Integer.MAX_VALUE, 0.01));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rejectsNonpositiveExplicitDimensions(int dimension) {
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter<>(dimension, 3, UTF_8));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter<>(20, dimension, UTF_8));
    }

    @Test
    void rejectsNullEncoder() {
        assertThrows(NullPointerException.class, () -> new BloomFilter<>(20, 3, null));
        assertThrows(NullPointerException.class, () -> BloomFilter.create(100, 0.01, null));
    }

    @Test
    void rejectsNullValuesBeforeCallingEncoder() {
        var calls = new AtomicInteger();
        var filter = BloomFilter.<String>create(100, 0.01, value -> {
            calls.incrementAndGet();
            return new byte[0];
        });

        assertThrows(NullPointerException.class, () -> filter.put(null));
        assertThrows(NullPointerException.class, () -> filter.mightContain(null));
        assertEquals(0, calls.get());
    }

    @Test
    void rejectsNullEncodingAndPropagatesEncoderFailures() {
        var filter = BloomFilter.<String>create(100, 0.01, value -> {
            if (value.equals("null")) {
                return null;
            }
            if (value.equals("throw")) {
                throw new IllegalArgumentException("cannot encode");
            }
            return UTF_8.apply(value);
        });

        assertThrows(NullPointerException.class, () -> filter.put("null"));
        assertThrows(NullPointerException.class, () -> filter.mightContain("null"));
        assertThrows(IllegalArgumentException.class, () -> filter.put("throw"));
        assertThrows(IllegalArgumentException.class, () -> filter.mightContain("throw"));
        assertFalse(filter.mightContain("valid"));
        filter.put("valid");
        assertTrue(filter.mightContain("valid"));
    }

    @Test
    void falsePositiveRateIsReasonableOnFixedDisjointData() {
        int count = 1_000;
        var filter = BloomFilter.forStrings(count, 0.01);
        for (int i = 0; i < count; i++) {
            filter.put("member-" + i);
        }
        for (int i = 0; i < count; i++) {
            assertTrue(filter.mightContain("member-" + i));
        }

        int queries = 20_000;
        int falsePositives = 0;
        for (int i = 0; i < queries; i++) {
            if (filter.mightContain("nonmember-" + i)) {
                falsePositives++;
            }
        }
        double observedRate = (double) falsePositives / queries;
        // A deterministic regression check with generous bounds, not a proof of p.
        assertTrue(observedRate > 0.002 && observedRate < 0.02,
                () -> "Observed false-positive rate: " + observedRate);
    }
}
