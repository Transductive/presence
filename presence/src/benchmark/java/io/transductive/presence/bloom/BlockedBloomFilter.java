package io.transductive.presence.bloom;

import java.util.Objects;

/**
 * Experimental, benchmark-only UTF-8 Bloom filter with 512-bit logical blocks.
 *
 * <p>Every value selects one block and all its probes stay inside that block.
 * Eight adjacent Java {@code long}s do not imply physical cache-line alignment.
 * The requested bits per key are a sizing input, not a false-positive guarantee:
 * block occupancy varies, so comparisons must calibrate measured false positives.
 * This class is not thread-safe, including concurrent queries.
 */
public final class BlockedBloomFilter {
    private static final int BLOCK_BITS = 512;
    private static final int BLOCK_WORDS = BLOCK_BITS / Long.SIZE;
    private static final int MAX_BLOCKS = (Integer.MAX_VALUE - 8) / BLOCK_WORDS;
    private static final long BLOCK_DOMAIN = 0xd1b54a32d192ed03L;
    private static final long PROBE_DOMAIN = 0xa0761d6478bd642fL;
    private static final long PROBE_INCREMENT = 0x9e3779b97f4a7c15L;

    private final long[] words;
    private final int blockCount;
    private final int hashCount;
    private final HashingStrategy strategy;
    private final HashInput input;
    private final HashPair hash;

    /**
     * Creates a filter rounded up to a whole number of 512-bit logical blocks.
     *
     * @param expectedInsertions positive expected number of distinct encoded keys
     * @param bitsPerKey positive, finite target bits per expected insertion
     * @param hashCount number of probes per key, from 1 through 512
     * @param strategy either {@link HashingStrategy#MURMUR3_128} or
     *                 {@link HashingStrategy#RAPIDHASH_V3}
     * @throws IllegalArgumentException for unsupported strategies, invalid
     *         dimensions, or storage exceeding one Java array
     * @throws NullPointerException if the strategy is null
     */
    public BlockedBloomFilter(long expectedInsertions, double bitsPerKey, int hashCount,
            HashingStrategy strategy) {
        if (expectedInsertions <= 0) {
            throw new IllegalArgumentException("expectedInsertions must be positive");
        }
        if (!(bitsPerKey > 0) || !Double.isFinite(bitsPerKey)) {
            throw new IllegalArgumentException("bitsPerKey must be positive and finite");
        }
        if (hashCount <= 0 || hashCount > BLOCK_BITS) {
            throw new IllegalArgumentException("hashCount must be between 1 and 512");
        }
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        if (strategy != HashingStrategy.MURMUR3_128 && strategy != HashingStrategy.RAPIDHASH_V3) {
            throw new IllegalArgumentException("Blocked experiment supports Murmur3 and rapidhash v3 only");
        }
        double requiredBlocks = Math.max(1.0, Math.ceil(expectedInsertions * bitsPerKey / BLOCK_BITS));
        if (requiredBlocks > MAX_BLOCKS) {
            throw new IllegalArgumentException("Blocked filter storage exceeds one Java array");
        }
        this.blockCount = (int) requiredBlocks;
        this.hashCount = hashCount;
        this.words = new long[blockCount * BLOCK_WORDS];
        this.input = new HashInput(true);
        this.hash = new HashPair();
    }

    /**
     * Adds a key using the same UTF-8 and malformed-surrogate replacement as
     * {@link String#getBytes(java.nio.charset.Charset)} with UTF-8.
     *
     * @param value non-null key
     * @throws NullPointerException if the key is null
     */
    public void put(String value) {
        try {
            hash(value);
            int firstWord = firstWord();
            long state = hash.second() ^ PROBE_DOMAIN;
            for (int i = 0; i < hashCount; i++) {
                state += PROBE_INCREMENT;
                int probe = (int) (mix64(state) >>> 55);
                words[firstWord + (probe >>> 6)] |= 1L << (probe & 63);
            }
        } finally {
            input.clear();
        }
    }

    /**
     * Returns false for a definitely absent key, or true for a possible member.
     *
     * @param value non-null key
     * @return whether every probe is set
     * @throws NullPointerException if the key is null
     */
    public boolean mightContain(String value) {
        try {
            hash(value);
            int firstWord = firstWord();
            long state = hash.second() ^ PROBE_DOMAIN;
            for (int i = 0; i < hashCount; i++) {
                state += PROBE_INCREMENT;
                int probe = (int) (mix64(state) >>> 55);
                if ((words[firstWord + (probe >>> 6)] & (1L << (probe & 63))) == 0) {
                    return false;
                }
            }
            return true;
        } finally {
            input.clear();
        }
    }

    /** @return allocated logical bits, including whole-block rounding */
    public long bitSize() {
        return (long) blockCount * BLOCK_BITS;
    }

    /** @return bit-array payload bytes, excluding object headers and hashing workspace */
    public long storageBytes() {
        return (long) words.length * Long.BYTES;
    }

    /** @return number of 512-bit logical blocks */
    public int blockCount() {
        return blockCount;
    }

    /** @return number of probes per key */
    public int hashFunctionCount() {
        return hashCount;
    }

    private void hash(String value) {
        input.setString(Objects.requireNonNull(value, "value"));
        if (strategy == HashingStrategy.MURMUR3_128) {
            Murmur3.hash(input, hash);
        } else {
            RapidHash.hashPair(input, hash);
        }
    }

    private int firstWord() {
        long blockHash = mix64(hash.first() ^ BLOCK_DOMAIN);
        // Unsigned high-product reduction keeps any bias below one part in 2^64
        // per block and does not require a power-of-two allocation.
        int block = (int) Math.unsignedMultiplyHigh(blockHash, blockCount);
        return block * BLOCK_WORDS;
    }

    private static long mix64(long value) {
        // SplitMix64's public-domain avalanche: https://prng.di.unimi.it/splitmix.c
        // Each probe mixes a full 64-bit
        // state, avoiding the tiny pattern family of 9-bit arithmetic double hashing.
        // This is pseudorandom mixing, not a claim of independent random functions.
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
