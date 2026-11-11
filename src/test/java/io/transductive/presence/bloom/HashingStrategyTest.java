package io.transductive.presence.bloom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Random;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class HashingStrategyTest {
    @Test
    void existingEntryPointsDefaultToMurmurAndExplicitSelectionIsRetained() {
        assertEquals(HashingStrategy.MURMUR3_128, new BloomFilter<byte[]>(100, 7, Function.identity()).hashingStrategy());
        assertEquals(HashingStrategy.MURMUR3_128, BloomFilter.create(100, 0.01, Function.identity()).hashingStrategy());
        assertEquals(HashingStrategy.MURMUR3_128, BloomFilter.forStrings(100, 0.01).hashingStrategy());
        assertEquals(HashingStrategy.SHA_256, BloomFilter.forStrings(100, 0.01, HashingStrategy.SHA_256).hashingStrategy());
    }

    @Test
    void nullStrategyIsRejectedByEveryEntryPoint() {
        assertThrows(NullPointerException.class, () -> new BloomFilter<byte[]>(100, 7, Function.identity(), null));
        assertThrows(NullPointerException.class, () -> BloomFilter.create(100, 0.01, Function.identity(), null));
        assertThrows(NullPointerException.class, () -> BloomFilter.forStrings(100, 0.01, null));
    }

    @ParameterizedTest
    @EnumSource(HashingStrategy.class)
    void allByteLengthsSurviveInterleavedInsertionsAndQueries(HashingStrategy strategy) {
        var filter = BloomFilter.<byte[]>create(1_000, 0.01, Function.identity(), strategy);
        var values = new ArrayList<byte[]>();
        var random = new Random(420);
        assertFalse(filter.mightContain(new byte[0]));
        for (int length = 0; length <= 256; length++) {
            byte[] bytes = new byte[length];
            random.nextBytes(bytes);
            values.add(bytes.clone());
            filter.put(bytes);
            filter.put(bytes);
            Arrays.fill(bytes, (byte) 0);
            for (byte[] previous : values) {
                assertTrue(filter.mightContain(previous.clone()));
            }
        }
    }

    @Test
    void zeroSecondHashStillAdvancesProbes() {
        var filter = new BloomFilter<byte[]>(17, 17, Function.identity());
        // Murmur3's empty input is (0, 0). A corrected step visits all 17 bits here.
        filter.put(new byte[0]);
        for (int i = 0; i < 256; i++) {
            assertTrue(filter.mightContain(new byte[] {(byte) i}));
        }
    }

    @ParameterizedTest
    @CsvSource({"MURMUR3_128,0.1", "MURMUR3_128,0.01", "MURMUR3_128,0.001",
            "RAPIDHASH_V3,0.1", "RAPIDHASH_V3,0.01", "RAPIDHASH_V3,0.001",
            "RAPIDHASH_NANO_V3,0.1", "RAPIDHASH_NANO_V3,0.01", "RAPIDHASH_NANO_V3,0.001",
            "KOMIHASH_5,0.1", "KOMIHASH_5,0.01", "KOMIHASH_5,0.001",
            "SHA_256,0.1", "SHA_256,0.01", "SHA_256,0.001"})
    void falsePositiveRatesTrackSizingAcrossStrategies(HashingStrategy strategy, double probability) {
        var filter = BloomFilter.forStrings(10_000, probability, strategy);
        for (int i = 0; i < 10_000; i++) {
            filter.put("member-" + i);
        }
        for (int i = 0; i < 10_000; i++) {
            assertTrue(filter.mightContain("member-" + i));
        }
        int positives = 0;
        for (int i = 0; i < 100_000; i++) {
            if (filter.mightContain("absent-" + i)) {
                positives++;
            }
        }
        double rate = positives / 100_000.0;
        assertTrue(rate > probability * 0.5 && rate < probability * 1.5,
                () -> strategy + ": observed " + rate + " for target " + probability);
    }

    @ParameterizedTest
    @EnumSource(HashingStrategy.class)
    void stringFactoryUsesUtf8ForEveryStrategy(HashingStrategy strategy) {
        var strings = BloomFilter.forStrings(100, 0.01, strategy);
        var bytes = BloomFilter.<byte[]>create(100, 0.01, Function.identity(), strategy);
        for (int i = 0; i < 100; i++) {
            String value = "\u03bb\uD83C\uDF31\u4F1A\u5458-" + i;
            strings.put(value);
            bytes.put(value.getBytes(StandardCharsets.UTF_8));
        }
        for (int i = 0; i < 1_000; i++) {
            String value = "\u03bb\uD83C\uDF31\u4F1A\u5458-" + i;
            boolean actual = strings.mightContain(value);
            if (i < 100) {
                assertTrue(actual);
            }
            assertEquals(bytes.mightContain(value.getBytes(StandardCharsets.UTF_8)), actual);
        }
    }

    @Test
    void shaFallbackPreservesOriginalSeededDigestBitPositions() throws Exception {
        int bitSize = 9_586;
        var expected = new BitSet(bitSize);
        var filter = new BloomFilter<String>(bitSize, 7,
                value -> value.getBytes(StandardCharsets.UTF_8), HashingStrategy.SHA_256);
        for (int i = 0; i < 1_000; i++) {
            String value = "member-" + i;
            filter.put(value);
            for (int position : referenceShaPositions(value, bitSize)) {
                expected.set(position);
            }
        }
        for (int i = 0; i < 2_000; i++) {
            String value = i < 1_000 ? "member-" + i : "absent-" + i;
            boolean present = Arrays.stream(referenceShaPositions(value, bitSize)).allMatch(expected::get);
            assertEquals(present, filter.mightContain(value));
        }
    }

    private static int[] referenceShaPositions(String value, int bitSize) throws Exception {
        int[] positions = new int[7];
        for (int seed = 0; seed < positions.length; seed++) {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(ByteBuffer.allocate(4).putInt(seed).array());
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            positions[seed] = new BigInteger(1, Arrays.copyOf(hash, 8)).mod(BigInteger.valueOf(bitSize)).intValue();
        }
        return positions;
    }

    @ParameterizedTest
    @EnumSource(HashingStrategy.class)
    void reusedWorkspacePreservesUnicodeAndLongValueMembership(HashingStrategy strategy) {
        var strings = BloomFilter.forStrings(500, 0.01, strategy);
        var bytes = BloomFilter.<byte[]>create(500, 0.01, Function.identity(), strategy);
        String prefix = "\u00e9\u4f1a\ud83c\udf31\ud800x\udc00".repeat(150);
        for (int i = 0; i < 500; i++) {
            String text = i % 2 == 0 ? prefix + i : "short-" + i;
            strings.put(text);
            bytes.put(text.getBytes(StandardCharsets.UTF_8));
        }
        for (int i = 999; i >= 0; i--) {
            String text = i % 2 == 0 ? prefix + i : "short-" + i;
            boolean result = strings.mightContain(text);
            assertEquals(bytes.mightContain(text.getBytes(StandardCharsets.UTF_8)), result);
            if (i < 500) {
                assertTrue(result);
            }
        }
        assertThrows(NullPointerException.class, () -> strings.mightContain(null));
        assertTrue(strings.mightContain(prefix + 0));
    }
}
