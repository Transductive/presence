package io.transductive.presence.bloom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SeededHashTest {
    @ParameterizedTest
    @EnumSource(value = HashingStrategy.class,
            names = {"RAPIDHASH_V3", "RAPIDHASH_NANO_V3", "KOMIHASH_5"})
    void matchesPinnedNativeReferenceIncludingPreparedSeeds(HashingStrategy strategy) throws Exception {
        var resource = getClass().getResourceAsStream("/seeded-hash-vectors.csv");
        assertNotNull(resource);
        int checked = 0;
        try (var reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            assertEquals("pattern,length,seed,rapid,nano,komi", reader.readLine());
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(",");
                int pattern = Integer.parseInt(fields[0]);
                byte[] bytes = new byte[Integer.parseInt(fields[1])];
                for (int i = 0; i < bytes.length; i++) {
                    bytes[i] = switch (pattern) {
                        case 0 -> (byte) (i * 137 + 29);
                        case 1 -> 0;
                        case 2 -> (byte) 0xff;
                        default -> throw new AssertionError("Unknown byte pattern: " + pattern);
                    };
                }
                byte[] original = bytes.clone();
                long seed = Long.parseUnsignedLong(fields[2], 16);
                int column = switch (strategy) {
                    case RAPIDHASH_V3 -> 3;
                    case RAPIDHASH_NANO_V3 -> 4;
                    case KOMIHASH_5 -> 5;
                    default -> throw new AssertionError(strategy);
                };
                long expected = Long.parseUnsignedLong(fields[column], 16);
                long actual = switch (strategy) {
                    case RAPIDHASH_V3 -> RapidHash.hash(bytes, seed);
                    case RAPIDHASH_NANO_V3 -> RapidHash.nano(bytes, seed);
                    case KOMIHASH_5 -> KomiHash.hash(bytes, seed);
                    default -> throw new AssertionError(strategy);
                };
                String context = strategy + ": " + line;
                assertEquals(expected, actual, context);
                if (seed == 0 || seed == HashingStrategy.SECOND_SEED) {
                    var pair = switch (strategy) {
                        case RAPIDHASH_V3 -> RapidHash.hashPair(bytes);
                        case RAPIDHASH_NANO_V3 -> RapidHash.nanoPair(bytes);
                        case KOMIHASH_5 -> KomiHash.hashPair(bytes);
                        default -> throw new AssertionError(strategy);
                    };
                    assertEquals(expected, seed == 0 ? pair.first() : pair.second(), context);
                }
                assertArrayEquals(original, bytes, context);
                checked++;
            }
        }
        assertEquals(4_095, checked);
    }
}
