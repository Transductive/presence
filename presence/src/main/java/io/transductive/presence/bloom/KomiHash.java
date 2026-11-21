/*
 * Java adaptation of Komihash 5.34, Copyright (c) 2021-2026 Aleksey Vaneev.
 * Distributed under the MIT license; see licenses/komihash-LICENSE.txt.
 */
package io.transductive.presence.bloom;

/** Komihash 5.34, using Java's unsigned wide multiplication and little-endian reads. */
final class KomiHash {
    private static final HashPair FIRST_SEED = prepare(0);
    private static final HashPair SECOND_SEED = prepare(HashingStrategy.SECOND_SEED);

    private KomiHash() { }

    static HashPair hashPair(byte[] bytes) {
        var input = new HashInput();
        input.setBytes(bytes);
        var result = new HashPair();
        hashPair(input, result);
        return result;
    }

    static void hashPair(HashInput input, HashPair result) {
        result.set(first(input), second(input));
    }

    static long first(HashInput input) {
        return hashPrepared(input, FIRST_SEED.first(), FIRST_SEED.second());
    }

    static long second(HashInput input) {
        return hashPrepared(input, SECOND_SEED.first(), SECOND_SEED.second());
    }

    static long hash(byte[] bytes, long seed) {
        var input = new HashInput();
        input.setBytes(bytes);
        var state = prepare(seed);
        return hashPrepared(input, state.first(), state.second());
    }

    private static HashPair prepare(long seed) {
        long first = 0x243f6a8885a308d3L ^ (seed & 0x5555555555555555L);
        long fifth = 0x452821e638d01377L ^ (seed & 0xaaaaaaaaaaaaaaaaL);
        long low = first * fifth;
        fifth += Math.unsignedMultiplyHigh(first, fifth);
        return new HashPair(low ^ fifth, fifth);
    }

    private static long hashPrepared(HashInput input, long first, long fifth) {
        int offset = 0;
        int remaining = input.length();
        if (remaining >= 64) {
            long second = 0x13198a2e03707344L ^ first;
            long third = 0xa4093822299f31d0L ^ first;
            long fourth = 0x082efa98ec4e6c89L ^ first;
            long sixth = 0xbe5466cf34e90c6cL ^ fifth;
            long seventh = 0xc0ac29b7c97c50ddL ^ fifth;
            long eighth = 0x3f84d5b5b5470917L ^ fifth;
            do {
                long a = input.read64(offset) ^ first;
                long b = input.read64(offset + 32) ^ fifth;
                first = a * b;
                fifth += Math.unsignedMultiplyHigh(a, b);
                a = input.read64(offset + 8) ^ second;
                b = input.read64(offset + 40) ^ sixth;
                second = a * b;
                sixth += Math.unsignedMultiplyHigh(a, b);
                a = input.read64(offset + 16) ^ third;
                b = input.read64(offset + 48) ^ seventh;
                third = a * b;
                seventh += Math.unsignedMultiplyHigh(a, b);
                a = input.read64(offset + 24) ^ fourth;
                b = input.read64(offset + 56) ^ eighth;
                fourth = a * b;
                eighth += Math.unsignedMultiplyHigh(a, b);
                fourth ^= seventh;
                first ^= eighth;
                second ^= fifth;
                third ^= sixth;
                offset += 64;
                remaining -= 64;
            } while (remaining >= 64);
            fifth ^= sixth ^ seventh ^ eighth;
            first ^= second ^ third ^ fourth;
        }
        while (remaining >= 16) {
            long a = input.read64(offset) ^ first;
            long b = input.read64(offset + 8) ^ fifth;
            fifth += Math.unsignedMultiplyHigh(a, b);
            first = (a * b) ^ fifth;
            offset += 16;
            remaining -= 16;
        }
        long a = first;
        long b = fifth;
        if (remaining >= 8) {
            a ^= input.read64(offset);
            b ^= tail(input, offset + 8, remaining - 8);
        } else if (input.length() != 0) {
            a ^= tail(input, offset, remaining);
        }
        // Two final multiplication rounds, retaining the accumulated high half.
        fifth += Math.unsignedMultiplyHigh(a, b);
        first = (a * b) ^ fifth;
        long low = first * fifth;
        fifth += Math.unsignedMultiplyHigh(first, fifth);
        return low ^ fifth;
    }

    private static long tail(HashInput input, int offset, int length) {
        // Append one marker bit after 0..7 trailing bytes; no out-of-bounds loads.
        long value = 1L << (length * Byte.SIZE);
        if (length >= 4) {
            return value | input.read32(offset)
                    | ((input.read32(offset + length - 4) >>> ((8 - length) * Byte.SIZE)) << 32);
        }
        for (int i = 0; i < length; i++) {
            value |= input.read8(offset + i) << (i * Byte.SIZE);
        }
        return value;
    }
}
