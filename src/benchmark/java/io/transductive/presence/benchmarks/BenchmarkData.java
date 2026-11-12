package io.transductive.presence.benchmarks;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

final class BenchmarkData {
    static final int QUERY_COUNT = 4_096;
    static final List<String> INPUT_KINDS = List.of("ASCII", "LATIN1", "UNICODE", "MALFORMED", "LONG_UNICODE");
    private static final String LONG_PREFIX = "\u00e9\u4f1a\ud83c\udf31-".repeat(256);

    private BenchmarkData() { }

    static void requireSize(int size) {
        if (size < 1_000) {
            throw new IllegalArgumentException("N must be at least 1000");
        }
    }

    static String[] keys(int size) {
        return keys(size, "ASCII");
    }

    static String[] keys(int size, String inputKind) {
        requireSize(size);
        var random = new Random(420);
        String[] keys = new String[size];
        for (int i = 0; i < size; i++) {
            keys[i] = decorate("member-" + i + "-" + Long.toHexString(random.nextLong()), inputKind);
            keys[i].hashCode(); // Model reusable strings with cached Java hashes.
        }
        return keys;
    }

    static String[] queries(String[] keys, int hitPercent) {
        return queries(keys, hitPercent, "ASCII");
    }

    static String[] queries(String[] keys, int hitPercent, String inputKind) {
        return queries(keys, hitPercent, inputKind, QUERY_COUNT);
    }

    static int queryCount(int size, String workingSet) {
        return switch (workingSet) {
            case "HOT" -> QUERY_COUNT;
            case "BROAD" -> Integer.highestOneBit(Math.max(65_536, size) - 1) << 1;
            default -> throw new IllegalArgumentException("Unknown query working set: " + workingSet);
        };
    }

    static String[] queries(String[] keys, int hitPercent, String inputKind, int queryCount) {
        if (hitPercent != 0 && hitPercent != 50 && hitPercent != 100) {
            throw new IllegalArgumentException("hitPercent must be 0, 50, or 100");
        }
        var random = new Random(421);
        if (queryCount < 2 || Integer.bitCount(queryCount) != 1) {
            throw new IllegalArgumentException("queryCount must be a power of two, at least 2");
        }
        List<String> queries = new ArrayList<>(queryCount);
        for (int i = 0; i < queryCount; i++) {
            // Hits have equal content but different object identity and backing storage.
            String key = i < (long) queryCount * hitPercent / 100
                    ? new String(keys[random.nextInt(keys.length)].toCharArray())
                    : decorate("absent-" + i + "-" + Long.toHexString(random.nextLong()), inputKind);
            key.hashCode();
            queries.add(key);
        }
        Collections.shuffle(queries, random);
        return queries.toArray(String[]::new);
    }

    private static String decorate(String value, String inputKind) {
        return switch (inputKind) {
            case "ASCII" -> value;
            case "LATIN1" -> "caf\u00e9-\u00ff-" + value;
            case "UNICODE" -> "\u4f1a\u5458-\ud83c\udf31-" + value;
            case "MALFORMED" -> "\ud800x\udc00-" + value;
            case "LONG_UNICODE" -> LONG_PREFIX + value;
            default -> throw new IllegalArgumentException("Unknown input kind: " + inputKind);
        };
    }
}
