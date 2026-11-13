package io.transductive.presence.bloom;

/**
 * MurmurHash3 x64 128-bit, seed zero. Based on Austin Appleby's public-domain reference:
 * https://github.com/aappleby/smhasher/blob/master/src/MurmurHash3.cpp
 */
final class Murmur3 {
    private static final long C1 = 0x87c37b91114253d5L;
    private static final long C2 = 0x4cf5ad432745937fL;

    private Murmur3() { }

    static HashPair hash(byte[] bytes) {
        var input = new HashInput();
        input.setBytes(bytes);
        var result = new HashPair();
        hash(input, result);
        return result;
    }

    static void hash(HashInput input, HashPair result) {
        long first = 0;
        long second = 0;
        int blockEnd = input.length() & ~15;
        for (int offset = 0; offset < blockEnd; offset += 16) {
            long low = input.read64(offset);
            long high = input.read64(offset + 8);
            first ^= Long.rotateLeft(low * C1, 31) * C2;
            first = (Long.rotateLeft(first, 27) + second) * 5 + 0x52dce729;
            second ^= Long.rotateLeft(high * C2, 33) * C1;
            second = (Long.rotateLeft(second, 31) + first) * 5 + 0x38495ab5;
        }

        long low = 0;
        long high = 0;
        int tailLength = input.length() - blockEnd;
        int lowBytes = Math.min(8, tailLength);
        int lowRead = 0;
        if (lowBytes == 8) {
            low = input.read64(blockEnd);
            lowRead = 8;
        } else if (lowBytes >= 4) {
            low = input.read32(blockEnd);
            lowRead = 4;
        }
        for (int i = lowRead; i < lowBytes; i++) {
            low |= input.read8(blockEnd + i) << (i * Byte.SIZE);
        }
        int highRead = 8;
        if (tailLength >= 12) {
            high = input.read32(blockEnd + 8);
            highRead = 12;
        }
        for (int i = highRead; i < tailLength; i++) {
            high |= input.read8(blockEnd + i) << ((i - 8) * Byte.SIZE);
        }
        first ^= Long.rotateLeft(low * C1, 31) * C2;
        second ^= Long.rotateLeft(high * C2, 33) * C1;
        first ^= input.length();
        second ^= input.length();
        first += second;
        second += first;
        first = mix(first);
        second = mix(second);
        first += second;
        second += first;
        result.set(first, second);
    }

    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        return value ^ (value >>> 33);
    }
}
