package io.transductive.presence.bloom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BlockedBloomFilterTest {
    private static final BigInteger TWO_64 = BigInteger.ONE.shiftLeft(64);
    private static final BigInteger MASK_64 = TWO_64.subtract(BigInteger.ONE);

    @Test
    void roundsStorageToWholeLogicalBlocks() {
        var filter = new BlockedBloomFilter(1_000, 10.0, 7, HashingStrategy.MURMUR3_128);
        assertEquals(20, filter.blockCount());
        assertEquals(10_240, filter.bitSize());
        assertEquals(1_280, filter.storageBytes());
        assertEquals(7, filter.hashFunctionCount());
        assertEquals(1, new BlockedBloomFilter(512, 1, 1, HashingStrategy.MURMUR3_128).blockCount());
        assertEquals(2, new BlockedBloomFilter(513, 1, 1, HashingStrategy.MURMUR3_128).blockCount());
        assertEquals(1, new BlockedBloomFilter(1, Double.MIN_VALUE, 1,
                HashingStrategy.MURMUR3_128).blockCount());
    }

    @Test
    void rejectsInvalidDimensionsAndUnsupportedStrategiesBeforeAllocatingStorage() {
        for (long count : new long[] {0, -1, Long.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new BlockedBloomFilter(count, 10, 7, HashingStrategy.MURMUR3_128));
        }
        for (double bits : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new BlockedBloomFilter(1_000, bits, 7, HashingStrategy.MURMUR3_128));
        }
        for (int count : new int[] {0, -1, 513, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new BlockedBloomFilter(1_000, 10, count, HashingStrategy.MURMUR3_128));
        }
        for (HashingStrategy strategy : new HashingStrategy[] {HashingStrategy.SHA_256,
                HashingStrategy.RAPIDHASH_NANO_V3, HashingStrategy.KOMIHASH_5}) {
            assertThrows(IllegalArgumentException.class, () -> new BlockedBloomFilter(1_000, 10, 7, strategy));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new BlockedBloomFilter(Long.MAX_VALUE, 10, 7, HashingStrategy.MURMUR3_128));
        assertThrows(NullPointerException.class, () -> new BlockedBloomFilter(1_000, 10, 7, null));
    }

    @ParameterizedTest
    @EnumSource(value = HashingStrategy.class, names = {"MURMUR3_128", "RAPIDHASH_V3"})
    void hasNoFalseNegativesAcrossUtf8KindsAndInputWindowBoundaries(HashingStrategy strategy) {
        var filter = new BlockedBloomFilter(10_000, 11.5, 7, strategy);
        for (int i = 0; i < 10_000; i++) {
            filter.put(key("insert", i));
        }
        for (int i = 0; i < 10_000; i++) {
            String value = key("insert", i);
            assertTrue(filter.mightContain(value), "Inserted key " + i);
            // Malformed surrogates identify the same bytes as their '?' replacement.
            String normalizedBytes = new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            assertTrue(filter.mightContain(normalizedBytes), "Equivalent UTF-8 key " + i);
        }
    }

    @ParameterizedTest
    @EnumSource(value = HashingStrategy.class, names = {"MURMUR3_128", "RAPIDHASH_V3"})
    void duplicateInsertionAndNullFailuresDoNotChangeMembership(HashingStrategy strategy) {
        var filter = new BlockedBloomFilter(100, 8, 7, strategy);
        assertFalse(filter.mightContain(""));
        assertThrows(NullPointerException.class, () -> filter.put(null));
        assertThrows(NullPointerException.class, () -> filter.mightContain(null));
        assertFalse(filter.mightContain(""));
        filter.put("");
        assertTrue(filter.mightContain(""));
        for (int i = 0; i < 100; i++) {
            filter.put(key("insert", i));
        }
        boolean[] answers = new boolean[2_000];
        for (int i = 0; i < answers.length; i++) {
            answers[i] = filter.mightContain(key("query", i));
        }
        for (int pass = 0; pass < 3; pass++) {
            for (int i = 0; i < 100; i++) {
                filter.put(key("insert", i));
            }
        }
        assertThrows(NullPointerException.class, () -> filter.put(null));
        assertThrows(NullPointerException.class, () -> filter.mightContain(null));
        for (int i = 0; i < answers.length; i++) {
            assertEquals(answers[i], filter.mightContain(key("query", i)));
        }
        assertTrue(filter.mightContain(key("insert", 99)));
        assertTrue(filter.mightContain(""));
    }

    @ParameterizedTest
    @EnumSource(value = HashingStrategy.class, names = {"MURMUR3_128", "RAPIDHASH_V3"})
    void maximumProbeCountHandlesRepeatedPositionsAndWrappedCounters(HashingStrategy strategy) {
        var filter = new BlockedBloomFilter(1, 512, 512, strategy);
        var reference = new BitSet(512);
        filter.put("");
        for (int bit : referencePositions("", 1, 512, strategy)) {
            reference.set(bit);
        }
        assertTrue(filter.mightContain(""));
        for (String value : new String[] {"", "different", "\u4f1a", "\ud800"}) {
            boolean expected = true;
            for (int bit : referencePositions(value, 1, 512, strategy)) {
                expected &= reference.get(bit);
            }
            assertEquals(expected, filter.mightContain(value));
        }
    }

    @ParameterizedTest
    @EnumSource(value = HashingStrategy.class, names = {"MURMUR3_128", "RAPIDHASH_V3"})
    void agreesWithUnsignedBigIntegerAndBitSetReference(HashingStrategy strategy) {
        for (int blocks : new int[] {1, 3, 19}) {
            for (int probes : new int[] {1, 7, 8}) {
                var filter = new BlockedBloomFilter(1, blocks * 512.0, probes, strategy);
                var reference = new BitSet(blocks * 512);
                for (int i = 0; i < blocks * 64; i++) {
                    String value = "insert/" + i;
                    filter.put(value);
                    for (int bit : referencePositions(value, blocks, probes, strategy)) {
                        reference.set(bit);
                    }
                }
                int positiveAnswers = 0;
                for (int i = 0; i < 1_000; i++) {
                    String value = "independent-query/" + i;
                    boolean expected = true;
                    for (int bit : referencePositions(value, blocks, probes, strategy)) {
                        expected &= reference.get(bit);
                    }
                    if (expected) {
                        positiveAnswers++;
                    }
                    assertEquals(expected, filter.mightContain(value),
                            "blocks=" + blocks + ", probes=" + probes + ", query=" + i);
                }
                assertTrue(positiveAnswers > 0 && positiveAnswers < 1_000,
                        "The oracle comparison must cover both outcomes");
            }
        }
    }

    private static String key(String namespace, int number) {
        String suffix = namespace + "/" + number;
        return switch (number % 7) {
            case 0 -> suffix;
            case 1 -> "caf\u00e9/\u00ff/" + suffix;
            case 2 -> "\u4f1a\u5458/\ud83c\udf31/" + suffix;
            case 3 -> "\ud800x\udc00/" + suffix;
            case 4 -> "a".repeat(84) + "\ud83c\udf31/" + suffix;
            case 5 -> "\u4f1a".repeat(85) + suffix;
            default -> "\u00e9\u4f1a\ud83c\udf31/".repeat(100) + suffix;
        };
    }

    private static int[] referencePositions(String value, int blocks, int probes, HashingStrategy strategy) {
        // The oracle independently encodes with the JDK and uses arbitrary-precision
        // unsigned math, closed-form probe counters, and a flat BitSet. It does not
        // call the implementation's mixer, range reducer, or word-address helpers.
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        HashPair pair = strategy == HashingStrategy.MURMUR3_128
                ? Murmur3.hash(bytes) : RapidHash.hashPair(bytes);
        BigInteger blockHash = permutation(unsigned(pair.first())
                .xor(new BigInteger("d1b54a32d192ed03", 16)));
        int block = blockHash.multiply(BigInteger.valueOf(blocks)).divide(TWO_64).intValueExact();
        BigInteger state = unsigned(pair.second()).xor(new BigInteger("a0761d6478bd642f", 16));
        BigInteger increment = new BigInteger("9e3779b97f4a7c15", 16);
        int[] positions = new int[probes];
        for (int ordinal = 1; ordinal <= probes; ordinal++) {
            BigInteger counter = state.add(increment.multiply(BigInteger.valueOf(ordinal))).mod(TWO_64);
            int local = permutation(counter).divide(BigInteger.ONE.shiftLeft(55)).intValueExact();
            positions[ordinal - 1] = block * 512 + local;
        }
        return positions;
    }

    private static BigInteger unsigned(long value) {
        return BigInteger.valueOf(value).and(MASK_64);
    }

    private static BigInteger permutation(BigInteger value) {
        BigInteger first = value.xor(value.shiftRight(30))
                .multiply(new BigInteger("bf58476d1ce4e5b9", 16)).mod(TWO_64);
        BigInteger second = first.xor(first.shiftRight(27))
                .multiply(new BigInteger("94d049bb133111eb", 16)).mod(TWO_64);
        return second.xor(second.shiftRight(31));
    }
}
