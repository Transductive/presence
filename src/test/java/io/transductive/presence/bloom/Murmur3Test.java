package io.transductive.presence.bloom;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

class Murmur3Test {
    // Reference values generated independently with mmh3 5.2.0 (Austin Appleby's C implementation).
    // mmh3.hash64(bytes((i * 137 + 29) & 255 for i in range(length)), seed=0, x64arch=True, signed=False)
    // Covers every tail length, multiple blocks, high-bit bytes, empty input, and larger inputs.
    @ParameterizedTest
    @CsvFileSource(resources = "/murmur3-x64-128-vectors.csv", numLinesToSkip = 1)
    void matchesIndependentReferenceVectors(int length, String first, String second) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i * 137 + 29);
        }
        var hash = Murmur3.hash(bytes);
        assertEquals(Long.parseUnsignedLong(first, 16), hash.first());
        assertEquals(Long.parseUnsignedLong(second, 16), hash.second());
    }
}
