/*
 * Java adaptation of rapidhash v3, Copyright (C) 2025 Nicolas De Carli.
 * Distributed under the MIT license; see licenses/rapidhash-LICENSE.txt.
 */
package io.transductive.presence.bloom;

/** rapidhash v3 and Nano v3, default secrets and FAST multiplication, with arbitrary 64-bit seeds. */
final class RapidHash {
    private static final long S0 = 0x2d358dccaa6c78a5L;
    private static final long S1 = 0x8bb84b93962eacc9L;
    private static final long S2 = 0x4b33a62ed433d4a3L;
    private static final long S3 = 0x4d5a2da51de1aa47L;
    private static final long S4 = 0xa0761d6478bd642fL;
    private static final long S5 = 0xe7037ed1a0b428dbL;
    private static final long S6 = 0x90ed1765281c388cL;
    private static final long S7 = 0xaaaaaaaaaaaaaaaaL;
    private static final long FIRST_SEED = mix(S2, S1);
    private static final long SECOND_SEED = HashingStrategy.SECOND_SEED
            ^ mix(HashingStrategy.SECOND_SEED ^ S2, S1);

    private RapidHash() { }

    static long hash(byte[] bytes, long seed) {
        var input = new HashInput();
        input.setBytes(bytes);
        return hashPrepared(input, seed ^ mix(seed ^ S2, S1));
    }

    static HashPair hashPair(byte[] bytes) {
        var input = new HashInput();
        input.setBytes(bytes);
        var result = new HashPair();
        hashPair(input, result);
        return result;
    }

    static HashPair nanoPair(byte[] bytes) {
        var input = new HashInput();
        input.setBytes(bytes);
        var result = new HashPair();
        nanoPair(input, result);
        return result;
    }

    static void hashPair(HashInput input, HashPair result) {
        result.set(first(input), second(input));
    }

    static void nanoPair(HashInput input, HashPair result) {
        result.set(nanoFirst(input), nanoSecond(input));
    }

    static long first(HashInput input) {
        return hashPrepared(input, FIRST_SEED);
    }

    static long second(HashInput input) {
        return hashPrepared(input, SECOND_SEED);
    }

    static long nanoFirst(HashInput input) {
        return nanoPrepared(input, FIRST_SEED);
    }

    static long nanoSecond(HashInput input) {
        return nanoPrepared(input, SECOND_SEED);
    }

    private static long hashPrepared(HashInput input, long seed) {
        if (input.length() <= 48) {
            return shortHash(input, seed);
        }
        return longHash(input, seed);
    }

    // Keep the shared short-input dispatch independent of the large-input loop,
    // so the JIT can inline it without inlining the entire hash implementation.
    private static long longHash(HashInput input, long seed) {
        int offset = 0;
        int remaining = input.length();
        if (remaining > 112) {
            long see1 = seed;
            long see2 = seed;
            long see3 = seed;
            long see4 = seed;
            long see5 = seed;
            long see6 = seed;
            do {
                seed = mix(input.read64(offset) ^ S0, input.read64(offset + 8) ^ seed);
                see1 = mix(input.read64(offset + 16) ^ S1, input.read64(offset + 24) ^ see1);
                see2 = mix(input.read64(offset + 32) ^ S2, input.read64(offset + 40) ^ see2);
                see3 = mix(input.read64(offset + 48) ^ S3, input.read64(offset + 56) ^ see3);
                see4 = mix(input.read64(offset + 64) ^ S4, input.read64(offset + 72) ^ see4);
                see5 = mix(input.read64(offset + 80) ^ S5, input.read64(offset + 88) ^ see5);
                see6 = mix(input.read64(offset + 96) ^ S6, input.read64(offset + 104) ^ see6);
                offset += 112;
                remaining -= 112;
            } while (remaining > 112);
            seed ^= see1 ^ see2 ^ see3 ^ see4 ^ see5 ^ see6;
        }
        if (remaining > 16) {
            seed = mix(input.read64(offset) ^ S2, input.read64(offset + 8) ^ seed);
        }
        if (remaining > 32) {
            seed = mix(input.read64(offset + 16) ^ S2, input.read64(offset + 24) ^ seed);
        }
        if (remaining > 48) {
            seed = mix(input.read64(offset + 32) ^ S1, input.read64(offset + 40) ^ seed);
        }
        if (remaining > 64) {
            seed = mix(input.read64(offset + 48) ^ S1, input.read64(offset + 56) ^ seed);
        }
        if (remaining > 80) {
            seed = mix(input.read64(offset + 64) ^ S2, input.read64(offset + 72) ^ seed);
        }
        if (remaining > 96) {
            seed = mix(input.read64(offset + 80) ^ S1, input.read64(offset + 88) ^ seed);
        }
        return finish(input.read64(input.length() - 16) ^ remaining,
                input.read64(input.length() - 8), seed, remaining);
    }

    static long nano(byte[] bytes, long seed) {
        var input = new HashInput();
        input.setBytes(bytes);
        return nanoPrepared(input, seed ^ mix(seed ^ S2, S1));
    }

    private static long nanoPrepared(HashInput input, long seed) {
        if (input.length() <= 48) {
            return shortHash(input, seed);
        }
        return longNano(input, seed);
    }

    private static long longNano(HashInput input, long seed) {
        long see1 = seed;
        long see2 = seed;
        int offset = 0;
        int remaining = input.length();
        do {
            seed = mix(input.read64(offset) ^ S0, input.read64(offset + 8) ^ seed);
            see1 = mix(input.read64(offset + 16) ^ S1, input.read64(offset + 24) ^ see1);
            see2 = mix(input.read64(offset + 32) ^ S2, input.read64(offset + 40) ^ see2);
            offset += 48;
            remaining -= 48;
        } while (remaining > 48);
        seed ^= see1 ^ see2;
        if (remaining > 16) {
            seed = mix(input.read64(offset) ^ S2, input.read64(offset + 8) ^ seed);
        }
        if (remaining > 32) {
            seed = mix(input.read64(offset + 16) ^ S2, input.read64(offset + 24) ^ seed);
        }
        return finish(input.read64(input.length() - 16) ^ remaining,
                input.read64(input.length() - 8), seed, remaining);
    }

    private static long shortHash(HashInput input, long seed) {
        int length = input.length();
        long first = 0;
        long second = 0;
        if (length <= 16) {
            if (length >= 4) {
                seed ^= length;
                if (length >= 8) {
                    first = input.read64(0);
                    second = input.read64(length - 8);
                } else {
                    first = input.read32(0);
                    second = input.read32(length - 4);
                }
            } else if (length > 0) {
                first = (input.read8(0) << 45) | input.read8(length - 1);
                second = input.read8(length >>> 1);
            }
        } else {
            seed = mix(input.read64(0) ^ S2, input.read64(8) ^ seed);
            if (length > 32) {
                seed = mix(input.read64(16) ^ S2, input.read64(24) ^ seed);
            }
            first = input.read64(length - 16) ^ length;
            second = input.read64(length - 8);
        }
        return finish(first, second, seed, length);
    }

    private static long finish(long first, long second, long seed, int remaining) {
        first ^= S1;
        second ^= seed;
        long low = first * second;
        long high = Math.unsignedMultiplyHigh(first, second);
        return mix(low ^ S7, high ^ S1 ^ remaining);
    }

    private static long mix(long first, long second) {
        return (first * second) ^ Math.unsignedMultiplyHigh(first, second);
    }
}
