package io.transductive.presence.bloom;

import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.BitSet;
import java.util.Objects;
import java.util.function.Function;

/**
 * A fixed-size, insert-only Bloom filter with no runtime dependencies.
 *
 * <p>Insertion sets {@code k} bits in an {@code m}-bit array. A query returns false
 * if any of those bits is unset. A true result means "possibly present", never
 * "definitely present". Repeated insertion is harmless; individual values cannot
 * be removed. Exceeding the planned capacity increases false positives.
 *
 * <p>Values are identified by their encoded bytes, not by {@link Object#equals}.
 * The encoder must produce the same bytes for the same logical value on every
 * call, and must not return null. Do not mutate a value's identifying data between
 * insertion and lookup. Neither values nor their byte arrays are retained.
 *
 * <p>This class is not thread-safe, including concurrent queries. Use external synchronization when sharing an
 * instance. The default strategy uses MurmurHash3 with double hashing. The
 * original seeded SHA-256 strategy remains available via {@link HashingStrategy#SHA_256}.
 *
 * @param <T> the value type
 */
public final class BloomFilter<T> {
    private static final double LN_2 = Math.log(2.0);

    private final int bitSize;
    private final long bitSizeReciprocal;
    private final int hashFunctionCount;
    private final Function<? super T, byte[]> encoder;
    private final HashingStrategy hashingStrategy;
    private final BitSet bits;
    private final boolean utf8Strings;
    private final HashInput input;
    private final HashPair hash = new HashPair();
    private final MessageDigest digest;
    private final byte[] digestBytes;

    /**
     * Creates an empty filter with explicit dimensions and MurmurHash3 hashing.
     *
     * @param bitSize number of addressable bits, greater than zero
     * @param hashFunctionCount number of bit probes, greater than zero
     * @param encoder deterministic encoding of a non-null value to non-null bytes
     * @throws IllegalArgumentException if either dimension is not positive
     * @throws NullPointerException if the encoder is null
     */
    public BloomFilter(int bitSize, int hashFunctionCount, Function<? super T, byte[]> encoder) {
        this(bitSize, hashFunctionCount, encoder, HashingStrategy.MURMUR3_128);
    }

    /**
     * Creates an empty filter with explicit dimensions and hashing strategy.
     *
     * @param bitSize number of addressable bits, greater than zero
     * @param hashFunctionCount number of bit probes, greater than zero
     * @param encoder deterministic encoding of a non-null value to non-null bytes
     * @param hashingStrategy strategy used for every insertion and lookup
     * @throws IllegalArgumentException if either dimension is not positive
     * @throws NullPointerException if the encoder or strategy is null
     */
    public BloomFilter(int bitSize, int hashFunctionCount, Function<? super T, byte[]> encoder,
            HashingStrategy hashingStrategy) {
        this(bitSize, hashFunctionCount, encoder, hashingStrategy, false);
    }

    private BloomFilter(int bitSize, int hashFunctionCount, Function<? super T, byte[]> encoder,
            HashingStrategy hashingStrategy, boolean utf8Strings) {
        if (bitSize <= 0) {
            throw new IllegalArgumentException("bitSize must be positive");
        }
        if (hashFunctionCount <= 0) {
            throw new IllegalArgumentException("hashFunctionCount must be positive");
        }
        this.encoder = utf8Strings ? null : Objects.requireNonNull(encoder, "encoder");
        this.utf8Strings = utf8Strings;
        this.input = new HashInput(utf8Strings);
        this.hashingStrategy = Objects.requireNonNull(hashingStrategy, "hashingStrategy");
        this.bitSize = bitSize;
        this.bitSizeReciprocal = UnsignedRemainder.reciprocal(bitSize);
        this.hashFunctionCount = hashFunctionCount;
        this.bits = new BitSet(bitSize);
        this.digest = hashingStrategy == HashingStrategy.SHA_256 ? newDigest() : null;
        this.digestBytes = digest == null ? null : new byte[32];
        if (digest != null) {
            finishDigest(); // Initialize any lazy provider buffers during construction.
            digest.reset();
        }
    }

    /**
     * Creates an empty filter sized for an expected number of distinct values.
     *
     * <p>Uses the usual approximations
     * {@code m = ceil(-n * ln(p) / ln(2)^2)} and
     * {@code k = max(1, round((m / n) * ln(2)))}. The requested probability is a
     * sizing target, not an exact upper bound, particularly for very small filters.
     *
     * @param expectedInsertions expected number of distinct encoded values
     * @param falsePositiveProbability target probability, strictly between 0 and 1
     * @param encoder deterministic encoding of a non-null value to non-null bytes
     * @param <T> the value type
     * @return a new, empty filter
     * @throws IllegalArgumentException if the capacity or probability is invalid,
     *         or the required bit count exceeds {@link Integer#MAX_VALUE}
     * @throws NullPointerException if the encoder is null
     */
    public static <T> BloomFilter<T> create(
            long expectedInsertions,
            double falsePositiveProbability,
            Function<? super T, byte[]> encoder) {
        return create(expectedInsertions, falsePositiveProbability, encoder, HashingStrategy.MURMUR3_128);
    }

    /**
     * Creates a sized filter with an explicit hashing strategy, using the same
     * sizing approximations as {@link #create(long, double, Function)}.
     *
     * @param expectedInsertions expected number of distinct encoded values
     * @param falsePositiveProbability target probability, strictly between 0 and 1
     * @param encoder deterministic encoding of a non-null value to non-null bytes
     * @param hashingStrategy strategy used for every insertion and lookup
     * @param <T> the value type
     * @return a new, empty filter
     * @throws IllegalArgumentException if sizing parameters are invalid or too large
     * @throws NullPointerException if the encoder or strategy is null
     */
    public static <T> BloomFilter<T> create(
            long expectedInsertions,
            double falsePositiveProbability,
            Function<? super T, byte[]> encoder,
            HashingStrategy hashingStrategy) {
        return create(expectedInsertions, falsePositiveProbability, encoder, hashingStrategy, false);
    }

    private static <T> BloomFilter<T> create(long expectedInsertions, double falsePositiveProbability,
            Function<? super T, byte[]> encoder, HashingStrategy hashingStrategy, boolean utf8Strings) {
        if (!utf8Strings) {
            Objects.requireNonNull(encoder, "encoder");
        }
        Objects.requireNonNull(hashingStrategy, "hashingStrategy");
        if (expectedInsertions <= 0) {
            throw new IllegalArgumentException("expectedInsertions must be positive");
        }
        if (!(falsePositiveProbability > 0.0 && falsePositiveProbability < 1.0)) {
            throw new IllegalArgumentException("falsePositiveProbability must be between 0 and 1");
        }
        double requiredBits = Math.ceil(
                -expectedInsertions * Math.log(falsePositiveProbability) / (LN_2 * LN_2));
        if (requiredBits > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("required bit count exceeds Integer.MAX_VALUE");
        }
        int bitSize = Math.max(1, (int) requiredBits);
        int hashCount = (int) Math.max(1L, Math.round((double) bitSize / expectedInsertions * LN_2));
        return new BloomFilter<>(bitSize, hashCount, encoder, hashingStrategy, utf8Strings);
    }

    /**
     * Creates a filter for strings encoded as UTF-8, without Unicode normalization.
     *
     * @param expectedInsertions expected number of distinct strings
     * @param falsePositiveProbability target probability, strictly between 0 and 1
     * @return a new, empty string filter
     * @throws IllegalArgumentException if the sizing parameters are invalid or too large
     */
    public static BloomFilter<String> forStrings(long expectedInsertions, double falsePositiveProbability) {
        return forStrings(expectedInsertions, falsePositiveProbability, HashingStrategy.MURMUR3_128);
    }

    /**
     * Creates a UTF-8 string filter with an explicit hashing strategy.
     *
     * @param expectedInsertions expected number of distinct strings
     * @param falsePositiveProbability target probability, strictly between 0 and 1
     * @param hashingStrategy strategy used for every insertion and lookup
     * @return a new, empty string filter
     * @throws IllegalArgumentException if sizing parameters are invalid or too large
     * @throws NullPointerException if the strategy is null
     */
    public static BloomFilter<String> forStrings(long expectedInsertions, double falsePositiveProbability,
            HashingStrategy hashingStrategy) {
        return create(expectedInsertions, falsePositiveProbability, null, hashingStrategy, true);
    }

    /**
     * Adds a value by setting all its hash positions.
     *
     * @param value the value to insert
     * @throws NullPointerException if the value or its encoding is null
     */
    public void put(T value) {
        try {
            bind(value);
            if (hashingStrategy == HashingStrategy.SHA_256) {
                for (int seed = 0; seed < hashFunctionCount; seed++) {
                    bits.set(index(seed));
                }
            } else {
                hashInput();
                putHashes(hash);
            }
        } finally {
            input.clear();
        }
    }

    private void putHashes(HashPair hash) {
        long position = bitIndex(hash.first());
        long step = probeStep(hash.second());
        for (int i = 0; i < hashFunctionCount; i++) {
            bits.set((int) position);
            position += step;
            if (position >= bitSize) {
                position -= bitSize;
            }
        }
    }

    /**
     * Checks whether all the value's hash positions have been set.
     *
     * @param value the value to query
     * @return false if definitely absent; true if possibly present
     * @throws NullPointerException if the value or its encoding is null
     */
    public boolean mightContain(T value) {
        try {
            bind(value);
            if (hashingStrategy == HashingStrategy.SHA_256) {
                for (int seed = 0; seed < hashFunctionCount; seed++) {
                    if (!bits.get(index(seed))) {
                        return false;
                    }
                }
                return true;
            }
            return containsHashes();
        } finally {
            input.clear();
        }
    }

    private boolean containsHashes() {
        long position = bitIndex(firstHash());
        if (!bits.get((int) position)) {
            return false;
        }
        if (hashFunctionCount == 1) {
            return true;
        }
        // An unset first bit already proves absence. Defer the second seeded
        // hash (and its reduction) until it can affect the answer.
        long step = probeStep(secondHash());
        for (int i = 1; i < hashFunctionCount; i++) {
            position += step;
            if (position >= bitSize) {
                position -= bitSize;
            }
            if (!bits.get((int) position)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the logical bit count, independent of storage-word rounding.
     *
     * @return number of addressable bits
     */
    public int bitSize() {
        return bitSize;
    }

    /**
     * Returns the number of bit probes per insertion. MurmurHash3 derives these
     * from one 128-bit hash; rapidhash and Komihash use two seeded 64-bit hashes.
     * SHA-256 evaluates a separate seeded hash per probe.
     *
     * @return number of bit probes
     */
    public int hashFunctionCount() {
        return hashFunctionCount;
    }

    /**
     * Returns the strategy fixed at construction time.
     *
     * @return the filter's hashing strategy
     */
    public HashingStrategy hashingStrategy() {
        return hashingStrategy;
    }

    private long probeStep(long hash) {
        long step = bitIndex(hash);
        // In particular, Murmur3 hashes the empty array to (0, 0); do not probe one bit k times.
        return step == 0 ? 1 : step;
    }

    private int bitIndex(long value) {
        return UnsignedRemainder.remainder(value, bitSize, bitSizeReciprocal);
    }

    private void bind(T value) {
        Objects.requireNonNull(value, "value");
        if (utf8Strings) {
            input.setString((String) value);
        } else {
            input.setBytes(Objects.requireNonNull(encoder.apply(value), "encoder returned null"));
        }
    }

    private void hashInput() {
        switch (hashingStrategy) {
            case MURMUR3_128 -> Murmur3.hash(input, hash);
            case RAPIDHASH_V3 -> RapidHash.hashPair(input, hash);
            case RAPIDHASH_NANO_V3 -> RapidHash.nanoPair(input, hash);
            case KOMIHASH_5 -> KomiHash.hashPair(input, hash);
            case SHA_256 -> throw new AssertionError("SHA-256 uses separate probes");
        }
    }

    private long firstHash() {
        return switch (hashingStrategy) {
            case MURMUR3_128 -> {
                Murmur3.hash(input, hash);
                yield hash.first();
            }
            case RAPIDHASH_V3 -> RapidHash.first(input);
            case RAPIDHASH_NANO_V3 -> RapidHash.nanoFirst(input);
            case KOMIHASH_5 -> KomiHash.first(input);
            case SHA_256 -> throw new AssertionError("SHA-256 uses separate probes");
        };
    }

    private long secondHash() {
        return switch (hashingStrategy) {
            case MURMUR3_128 -> hash.second();
            case RAPIDHASH_V3 -> RapidHash.second(input);
            case RAPIDHASH_NANO_V3 -> RapidHash.nanoSecond(input);
            case KOMIHASH_5 -> KomiHash.second(input);
            case SHA_256 -> throw new AssertionError("SHA-256 uses separate probes");
        };
    }

    private int index(int seed) {
        // Fixed-width, big-endian seed prefix gives each hash its own domain.
        digestBytes[0] = (byte) (seed >>> 24);
        digestBytes[1] = (byte) (seed >>> 16);
        digestBytes[2] = (byte) (seed >>> 8);
        digestBytes[3] = (byte) seed;
        digest.reset();
        digest.update(digestBytes, 0, 4);
        input.update(digest);
        finishDigest();
        long value = 0;
        for (int i = 0; i < Long.BYTES; i++) {
            value = (value << Byte.SIZE) | (digestBytes[i] & 0xffL);
        }
        return bitIndex(value);
    }

    private void finishDigest() {
        try {
            digest.digest(digestBytes, 0, digestBytes.length);
        } catch (DigestException e) {
            throw new IllegalStateException("SHA-256 did not fit its 32-byte output buffer", e);
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required by every Java implementation.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
