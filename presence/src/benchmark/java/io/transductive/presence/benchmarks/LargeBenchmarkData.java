package io.transductive.presence.benchmarks;

import java.util.Random;

/** Deterministic, random-access keys without a retained inserted-key array. */
final class LargeBenchmarkData {
    static final int DEFAULT_QUERY_COUNT = 1_048_576;
    static final int MAX_QUERY_COUNT = 4_194_304;
    static final int MAX_MISSES = 1 << 24;
    static final int QUERY_DOMAIN = 1;
    static final int TRAINING_DOMAIN = 2;
    static final int CALIBRATION_HOLDOUT_DOMAIN = 3;
    static final int VALIDATION_DOMAIN = 4;
    static final int ALLOCATION_DOMAIN = 5;

    private LargeBenchmarkData() { }

    static void requireSize(int size) {
        if (size < 1_000) {
            throw new IllegalArgumentException("N must be at least 1000");
        }
    }

    static void requireQueryCount(int count) {
        if (count < 1_024 || count > MAX_QUERY_COUNT || Integer.bitCount(count) != 1) {
            throw new IllegalArgumentException("queryCount must be a power of two from 1024 through 4194304");
        }
    }

    static void requireMissCount(int count) {
        if (count < 2_000_000 || count > MAX_MISSES) {
            throw new IllegalArgumentException("misses must be from 2000000 through 16777216");
        }
    }

    static String key(long id) {
        // The mixer is a bijection on all 64-bit values: xor-shifts and odd multipliers.
        // Hex encoding is injective. Stored nonnegative IDs and negative miss IDs cannot collide.
        return "presence-key-" + Long.toUnsignedString(mix(id), 16);
    }

    static long absentId(int domain, int size, int ordinal) {
        if (domain < 1 || domain > 127 || size < 0 || ordinal < 0 || ordinal >= MAX_MISSES) {
            throw new IllegalArgumentException("Invalid absent-key domain, size, or ordinal");
        }
        return Long.MIN_VALUE | ((long) domain << 56) | ((long) size << 24) | ordinal;
    }

    static String[] queries(int size, int hitPercent, int count) {
        requireSize(size);
        requireQueryCount(count);
        if (hitPercent != 0 && hitPercent != 50 && hitPercent != 100) {
            throw new IllegalArgumentException("hitPercent must be 0, 50, or 100");
        }
        String[] queries = new String[count];
        int hits = (int) ((long) count * hitPercent / 100);
        for (int i = 0; i < count; i++) {
            long id = i < hits ? Long.remainderUnsigned(mix(i ^ 0x4f1bbcdc12345678L), size)
                    : absentId(QUERY_DOMAIN, size, i);
            queries[i] = key(id);
            queries[i].hashCode(); // CACHED fixture; FRESH reconstructs from characters inside each operation.
        }
        var random = new Random(741_923);
        for (int i = count - 1; i > 0; i--) {
            int other = random.nextInt(i + 1);
            String value = queries[i];
            queries[i] = queries[other];
            queries[other] = value;
        }
        return queries;
    }

    static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    static String json(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                .replace("\r", "\\r").replace("\t", "\\t") + '"';
    }
}
