package io.transductive.presence.bloom;

/** Built-in Bloom filter hashing strategies, fixed for the lifetime of a filter. */
public enum HashingStrategy {
    /**
     * Default: MurmurHash3 x64 128-bit, seed zero, with double hashing for bit positions.
     * A fast, noncryptographic strategy implemented using only the Java core library.
     */
    MURMUR3_128,

    /** rapidhash v3 with default secrets, two fixed seeds, and double hashing. */
    RAPIDHASH_V3,

    /** rapidhash Nano v3, tuned for small inputs, with two fixed seeds and double hashing. */
    RAPIDHASH_NANO_V3,

    /** Komihash 5.34 with two fixed seeds and double hashing. */
    KOMIHASH_5,

    /**
     * A separate seeded SHA-256 digest for each bit position, supplied by the
     * JDK's {@link java.security.MessageDigest}, with reusable state and output.
     */
    SHA_256;

    static final long SECOND_SEED = 0x9e3779b97f4a7c15L;

}
