package io.transductive.presence.bloom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Pins the existing bit layout while the implementation changes how eagerly it hashes. */
class FastProbeCompatibilityTest {
    private static final long SECOND_SEED = 0x9e3779b97f4a7c15L;
    private static final int[][] DIMENSIONS = {
        {1, 1}, {1, 7}, {2, 2}, {3, 7}, {17, 17}, {31, 7}, {63, 7},
        {64, 7}, {65, 7}, {257, 1}, {997, 7}, {997, 65}, {9_586, 7}
    };

    @ParameterizedTest
    @EnumSource(HashingStrategy.class)
    void exactBitsAndMembershipMatchIndependentUnsignedProbeArithmetic(HashingStrategy strategy) throws Exception {
        List<byte[]> values = values();
        for (int[] dimensions : DIMENSIONS) {
            int bitSize = dimensions[0];
            int probes = dimensions[1];
            var filter = new BloomFilter<byte[]>(bitSize, probes, Function.identity(), strategy);
            var expected = new BitSet(bitSize);
            List<int[]> positions = new ArrayList<>();
            for (byte[] value : values) {
                positions.add(referencePositions(value, bitSize, probes, strategy));
            }
            for (int inserted = 0; inserted < values.size(); inserted++) {
                filter.put(values.get(inserted));
                for (int position : positions.get(inserted)) {
                    expected.set(position);
                }
                // Query-only comparisons could miss a changed layout in a sparse filter.
                assertEquals(expected, storedBits(filter),
                        strategy + ": m=" + bitSize + ", k=" + probes + ", insertion=" + inserted);
                for (int query = 0; query < values.size(); query++) {
                    boolean present = true;
                    for (int position : positions.get(query)) {
                        present &= expected.get(position);
                    }
                    assertEquals(present, filter.mightContain(values.get(query)));
                }
                assertEquals(expected, storedBits(filter), "Queries must leave every bit unchanged");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(HashingStrategy.class)
    void stringWorkspaceTransitionsKeepTheSameBitLayout(HashingStrategy strategy) throws Exception {
        var filter = BloomFilter.forStrings(100, 0.01, strategy);
        var expected = new BitSet(filter.bitSize());
        String unicode = "\u00e9\u4f1a\ud83c\udf31\ud800x\udc00".repeat(100);
        List<String> values = List.of("", unicode, "short", "a".repeat(257), "\ud800", "x".repeat(256),
                "\u4f1a".repeat(84), "\u4f1a".repeat(85), "\u4f1a".repeat(86), unicode + "tail", "last");
        for (String value : values) {
            filter.put(value);
            for (int position : referencePositions(value.getBytes(StandardCharsets.UTF_8), filter.bitSize(),
                    filter.hashFunctionCount(), strategy)) {
                expected.set(position);
            }
            assertEquals(expected, storedBits(filter));
            for (String query : values) {
                filter.mightContain(query);
            }
            assertEquals(expected, storedBits(filter));
        }
        for (String value : values) {
            assertTrue(filter.mightContain(value));
        }
    }

    @ParameterizedTest
    @EnumSource(value = HashingStrategy.class, names = "SHA_256", mode = EnumSource.Mode.EXCLUDE)
    void referenceCasesExerciseNegativeHashHalves(HashingStrategy strategy) throws Exception {
        boolean negativeFirst = false;
        boolean negativeSecond = false;
        for (byte[] value : values()) {
            long[] pair = referencePair(value, strategy);
            negativeFirst |= pair[0] < 0;
            negativeSecond |= pair[1] < 0;
        }
        assertTrue(negativeFirst, "Unsigned reduction must be exercised for the first hash");
        assertTrue(negativeSecond, "Unsigned reduction must be exercised for the second hash");
    }

    private static int[] referencePositions(byte[] value, int bitSize, int probes, HashingStrategy strategy)
            throws Exception {
        var positions = new int[probes];
        BigInteger modulus = BigInteger.valueOf(bitSize);
        if (strategy == HashingStrategy.SHA_256) {
            for (int seed = 0; seed < probes; seed++) {
                var digest = MessageDigest.getInstance("SHA-256");
                digest.update(ByteBuffer.allocate(4).putInt(seed).array());
                byte[] bytes = digest.digest(value);
                positions[seed] = new BigInteger(1, Arrays.copyOf(bytes, 8)).mod(modulus).intValueExact();
            }
            return positions;
        }
        long[] pair = referencePair(value, strategy);
        BigInteger first = unsigned(pair[0]);
        BigInteger step = unsigned(pair[1]).mod(modulus);
        if (step.signum() == 0) {
            step = BigInteger.ONE;
        }
        for (int i = 0; i < probes; i++) {
            positions[i] = first.add(step.multiply(BigInteger.valueOf(i))).mod(modulus).intValueExact();
        }
        return positions;
    }

    private static long[] referencePair(byte[] value, HashingStrategy strategy) {
        return switch (strategy) {
            case MURMUR3_128 -> {
                HashPair pair = Murmur3.hash(value);
                yield new long[] {pair.first(), pair.second()};
            }
            // Single-seed entry points deliberately avoid the paired/lazy hash path under test.
            case RAPIDHASH_V3 -> new long[] {RapidHash.hash(value, 0), RapidHash.hash(value, SECOND_SEED)};
            case RAPIDHASH_NANO_V3 -> new long[] {RapidHash.nano(value, 0), RapidHash.nano(value, SECOND_SEED)};
            case KOMIHASH_5 -> new long[] {KomiHash.hash(value, 0), KomiHash.hash(value, SECOND_SEED)};
            case SHA_256 -> throw new AssertionError("SHA-256 uses independent seeded probes");
        };
    }

    private static BigInteger unsigned(long value) {
        return new BigInteger(1, ByteBuffer.allocate(8).putLong(value).array());
    }

    private static BitSet storedBits(BloomFilter<?> filter) throws Exception {
        Field field = BloomFilter.class.getDeclaredField("bits");
        field.setAccessible(true);
        return (BitSet) field.get(filter);
    }

    private static List<byte[]> values() {
        var random = new Random(740_263);
        var values = new ArrayList<byte[]>();
        for (int length : new int[] {0, 1, 3, 4, 7, 8, 15, 16, 17, 31, 32, 33, 47, 48, 49,
                63, 64, 65, 111, 112, 113, 255, 256, 257, 511}) {
            var value = new byte[length];
            random.nextBytes(value);
            values.add(value);
        }
        values.add(new byte[128]);
        byte[] ones = new byte[128];
        Arrays.fill(ones, (byte) 0xff);
        values.add(ones);
        return values;
    }
}
