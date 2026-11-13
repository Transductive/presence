package io.transductive.presence.bloom;

import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Function;

/**
 * An insert-only membership filter that adds larger Bloom layers as it fills.
 *
 * <p>Based on Almeida et al., <em>Scalable Bloom Filters</em>. Each layer has
 * disjoint bit slices, one per probe. A layer never fills a slice beyond one
 * half. Layer {@code i} receives probability budget {@code P * (1 - r) * r^i},
 * whose infinite sum is {@code P}. These are targets under the usual uniform
 * hashing model, not guarantees for arbitrary or adversarial inputs.
 *
 * <p>A query returns true if any layer matches. Values accepted by {@link #put}
 * remain discoverable; neither keys nor encoded byte arrays are retained.
 * Growth retains all older layers and consumes additional memory. There is no
 * deletion, eviction, persistence, or fixed total memory budget.
 *
 * <p>Like {@link BloomFilter}, identity is defined by deterministic encoded
 * bytes. Instances are not thread-safe, including concurrent lookups, because
 * their hashing workspace is reused. Use external synchronization when sharing.
 *
 * @param <T> the value type
 */
public final class ScalableBloomFilter<T> {
    private static final double LN_2 = Math.log(2);
    private static final int DEFAULT_GROWTH = 2;
    private static final double DEFAULT_TIGHTENING = 0.9;
    private static final long LAYER_DOMAIN = 0xd1b54a32d192ed03L;
    private static final long LAYER_INCREMENT = 0x9e3779b97f4a7c15L;

    private final ArrayList<Layer> layers = new ArrayList<>();
    private final Function<? super T, byte[]> encoder;
    private final boolean utf8Strings;
    private final HashingStrategy strategy;
    private final HashInput input;
    private final HashPair hash = new HashPair();
    private final MessageDigest digest;
    private final byte[] digestBytes;
    private final double targetProbability;
    private final double initialLogProbability;
    private final double logTightening;
    private final int growthFactor;

    private ScalableBloomFilter(long initialCapacity, double probability,
            Function<? super T, byte[]> encoder, boolean utf8Strings,
            HashingStrategy strategy, int growthFactor, double tighteningRatio) {
        if (initialCapacity <= 0) throw new IllegalArgumentException("initialCapacity must be positive");
        if (!(probability > 0 && probability < 1)) {
            throw new IllegalArgumentException("falsePositiveProbability must be between 0 and 1");
        }
        if (growthFactor != 2 && growthFactor != 4) {
            throw new IllegalArgumentException("growthFactor must be 2 or 4");
        }
        if (!(tighteningRatio > 0 && tighteningRatio < 1)) {
            throw new IllegalArgumentException("tighteningRatio must be between 0 and 1");
        }
        this.encoder = utf8Strings ? null : Objects.requireNonNull(encoder, "encoder");
        this.utf8Strings = utf8Strings;
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.targetProbability = probability;
        // Log space avoids underflow for tiny probabilities and later layers.
        this.initialLogProbability = Math.log(probability) + Math.log1p(-tighteningRatio);
        this.logTightening = Math.log(tighteningRatio);
        this.growthFactor = growthFactor;
        double requiredSliceBits = Math.max(Long.SIZE, Math.ceil(initialCapacity / LN_2));
        if (requiredSliceBits > (1L << 30)) {
            throw new IllegalArgumentException("initialCapacity exceeds the supported layer size");
        }
        int sliceBits = Integer.highestOneBit((int) requiredSliceBits - 1) << 1;
        layers.add(newLayer(0, sliceBits));
        this.input = new HashInput(utf8Strings);
        this.digest = strategy == HashingStrategy.SHA_256 ? newDigest() : null;
        this.digestBytes = digest == null ? null : new byte[32];
        if (digest != null) finishDigest(); // Warm the provider's lazy buffers.
    }

    /**
     * Creates a UTF-8 string filter with Murmur3, doubling growth, and ratio 0.9.
     *
     * @param initialCapacity expected distinct values in the first layer, positive
     * @param falsePositiveProbability target for the entire filter, between 0 and 1
     * @return an empty, growable filter
     * @throws IllegalArgumentException for invalid or unsupported sizing
     */
    public static ScalableBloomFilter<String> forStrings(long initialCapacity, double falsePositiveProbability) {
        return forStrings(initialCapacity, falsePositiveProbability, HashingStrategy.MURMUR3_128);
    }

    /**
     * Creates a UTF-8 string filter with a selected hash strategy and default growth.
     * Malformed surrogates become {@code ?}, matching JDK UTF-8 encoding.
     *
     * @param initialCapacity expected distinct values in the first layer, positive
     * @param falsePositiveProbability target for the entire filter, between 0 and 1
     * @param strategy hashing strategy, fixed for the filter's lifetime
     * @return an empty, growable filter
     * @throws IllegalArgumentException for invalid or unsupported sizing
     * @throws NullPointerException if strategy is null
     */
    public static ScalableBloomFilter<String> forStrings(long initialCapacity, double falsePositiveProbability,
            HashingStrategy strategy) {
        return forStrings(initialCapacity, falsePositiveProbability, strategy, DEFAULT_GROWTH, DEFAULT_TIGHTENING);
    }

    /**
     * Creates a UTF-8 string filter with explicit layer-growth settings.
     *
     * @param initialCapacity expected distinct values in the first layer, positive
     * @param falsePositiveProbability target for the entire filter, between 0 and 1
     * @param strategy hashing strategy
     * @param growthFactor slice-size multiplier, either 2 or 4
     * @param tighteningRatio successive-layer probability multiplier, between 0 and 1
     * @return an empty, growable filter
     * @throws IllegalArgumentException for invalid parameters or unsupported sizing
     * @throws NullPointerException if strategy is null
     */
    public static ScalableBloomFilter<String> forStrings(long initialCapacity, double falsePositiveProbability,
            HashingStrategy strategy, int growthFactor, double tighteningRatio) {
        return new ScalableBloomFilter<>(initialCapacity, falsePositiveProbability, null, true,
                strategy, growthFactor, tighteningRatio);
    }

    /**
     * Creates a filter with a deterministic byte encoder and default hashing/growth.
     *
     * @param initialCapacity expected distinct values in the first layer, positive
     * @param falsePositiveProbability target for the entire filter, between 0 and 1
     * @param encoder non-null function returning non-null bytes; called once per operation
     * @param <T> value type
     * @return an empty, growable filter
     * @throws IllegalArgumentException for invalid or unsupported sizing
     * @throws NullPointerException if encoder is null
     */
    public static <T> ScalableBloomFilter<T> create(long initialCapacity, double falsePositiveProbability,
            Function<? super T, byte[]> encoder) {
        return create(initialCapacity, falsePositiveProbability, encoder, HashingStrategy.MURMUR3_128);
    }

    /**
     * Creates a filter with an encoder, selected hash strategy, and default growth.
     *
     * @param initialCapacity expected distinct values in the first layer, positive
     * @param falsePositiveProbability target for the entire filter, between 0 and 1
     * @param encoder non-null function returning non-null bytes; called once per operation
     * @param strategy hashing strategy
     * @param <T> value type
     * @return an empty, growable filter
     * @throws IllegalArgumentException for invalid or unsupported sizing
     * @throws NullPointerException if encoder or strategy is null
     */
    public static <T> ScalableBloomFilter<T> create(long initialCapacity, double falsePositiveProbability,
            Function<? super T, byte[]> encoder, HashingStrategy strategy) {
        return create(initialCapacity, falsePositiveProbability, encoder, strategy, DEFAULT_GROWTH, DEFAULT_TIGHTENING);
    }

    /**
     * Creates a filter with explicit encoding, hashing, and layer-growth settings.
     *
     * @param initialCapacity expected distinct values in the first layer, positive
     * @param falsePositiveProbability target for the entire filter, between 0 and 1
     * @param encoder non-null function returning non-null bytes; called once per operation
     * @param strategy hashing strategy
     * @param growthFactor slice-size multiplier, either 2 or 4
     * @param tighteningRatio successive-layer probability multiplier, between 0 and 1
     * @param <T> value type
     * @return an empty, growable filter
     * @throws IllegalArgumentException for invalid parameters or unsupported sizing
     * @throws NullPointerException if encoder or strategy is null
     */
    public static <T> ScalableBloomFilter<T> create(long initialCapacity, double falsePositiveProbability,
            Function<? super T, byte[]> encoder, HashingStrategy strategy, int growthFactor, double tighteningRatio) {
        return new ScalableBloomFilter<>(initialCapacity, falsePositiveProbability, encoder, false,
                strategy, growthFactor, tighteningRatio);
    }

    /**
     * Adds a value, allocating a larger layer when a slice would exceed half fill.
     * An existing positive is a no-op, including false positives: layers are never
     * cleared, so that value remains discoverable. Repeated inserts do not grow
     * the filter. The initial capacity is a sizing hint, not a rollover counter.
     *
     * @param value non-null value to add
     * @throws NullPointerException if value or its encoding is null
     * @throws IllegalStateException if growth would exceed Integer.MAX_VALUE bits per layer;
     *         previously accepted values remain discoverable
     */
    public void put(T value) {
        try {
            bindAndHash(value);
            if (containsBoundValue()) return;
            Layer active = layers.getLast();
            if (!prepareInsertion(active)) {
                long nextSliceBits = (long) active.sliceBits * growthFactor;
                Layer next;
                try {
                    next = newLayer(layers.size(), nextSliceBits);
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException("Scalable Bloom layer size limit reached", e);
                }
                prepareInsertion(next);
                layers.add(next);
                active = next;
            }
            for (int slice = 0; slice < active.probes; slice++) {
                int bit = active.pending[slice];
                int word = bit >>> 6;
                long mask = 1L << (bit & 63);
                if ((active.words[word] & mask) == 0) {
                    active.words[word] |= mask;
                    active.setBits[slice]++;
                }
            }
        } finally {
            input.clear();
        }
    }

    /**
     * Checks layers newest first, stopping at the first possible match.
     * A negative answer checks every layer. Queries do not allocate new layers.
     *
     * @param value non-null value to query
     * @return false if definitely absent, or true if possibly present
     * @throws NullPointerException if value or its encoding is null
     */
    public boolean mightContain(T value) {
        try {
            bindAndHash(value);
            return containsBoundValue();
        } finally {
            input.clear();
        }
    }

    /**
     * Returns the current number of layers, including the initially empty layer.
     * @return number of allocated layers
     */
    public int layerCount() { return layers.size(); }

    /**
     * Returns the total bit-array payload size, excluding counters and workspace.
     * @return allocated payload bits across all layers
     */
    public long bitSize() {
        long total = 0;
        for (int i = 0; i < layers.size(); i++) total += (long) layers.get(i).words.length * Long.SIZE;
        return total;
    }

    /**
     * Returns the requested probability target for the entire filter.
     * @return the whole-filter target, not a per-layer allowance
     */
    public double targetFalsePositiveProbability() { return targetProbability; }

    /**
     * Returns the strategy fixed at construction.
     * @return the filter's hashing strategy
     */
    public HashingStrategy hashingStrategy() { return strategy; }

    /**
     * Sums each layer's product of slice fill ratios (a union-bound estimate).
     * This models uniform, independent probes; it is not a measured rate or a
     * guarantee for correlated hashes or adversarial inputs. Cost is proportional
     * to the total number of slices, without scanning the bit arrays.
     *
     * @return occupancy-based estimate, zero for an empty filter
     */
    public double estimatedFalsePositiveProbability() {
        double total = 0;
        for (int i = 0; i < layers.size(); i++) {
            Layer layer = layers.get(i);
            double probability = 1;
            for (int count : layer.setBits) probability *= (double) count / layer.sliceBits;
            total += probability;
        }
        return total;
    }

    private Layer newLayer(int index, long sliceBits) {
        double logProbability = initialLogProbability + index * logTightening;
        int probes = Math.max(1, (int) Math.ceil(-logProbability / LN_2));
        if (sliceBits > Integer.MAX_VALUE / probes) {
            throw new IllegalArgumentException("A layer cannot exceed Integer.MAX_VALUE bits");
        }
        return new Layer(index, (int) sliceBits, probes);
    }

    private boolean containsBoundValue() {
        for (int i = layers.size() - 1; i >= 0; i--) {
            Layer layer = layers.get(i);
            boolean match = true;
            for (int slice = 0; slice < layer.probes; slice++) {
                int bit = position(layer, slice);
                if ((layer.words[bit >>> 6] & (1L << (bit & 63))) == 0) {
                    match = false;
                    break;
                }
            }
            if (match) return true;
        }
        return false;
    }

    private boolean prepareInsertion(Layer layer) {
        // Validate all slices before changing any bits, including at rollover.
        for (int slice = 0; slice < layer.probes; slice++) {
            int bit = position(layer, slice);
            layer.pending[slice] = bit;
            if (layer.setBits[slice] == layer.sliceBits / 2
                    && (layer.words[bit >>> 6] & (1L << (bit & 63))) == 0) return false;
        }
        return true;
    }

    private int position(Layer layer, int slice) {
        long value;
        if (digest == null) {
            // Avalanche every derived probe before masking. Ordinary double
            // hashing on a tiny power-of-two slice has a small pattern family.
            value = mix64((hash.first() ^ layer.salt) + slice * (hash.second() | 1L));
        } else {
            digest.reset();
            for (int shift = 24; shift >= 0; shift -= 8) digest.update((byte) (layer.index >>> shift));
            for (int shift = 24; shift >= 0; shift -= 8) digest.update((byte) (slice >>> shift));
            input.update(digest);
            finishDigest();
            value = HashBytes.read64(digestBytes, 0);
        }
        return slice * layer.sliceBits + ((int) value & (layer.sliceBits - 1));
    }

    private void bindAndHash(T value) {
        Objects.requireNonNull(value, "value");
        if (utf8Strings) input.setString((String) value);
        else input.setBytes(Objects.requireNonNull(encoder.apply(value), "encoder returned null"));
        switch (strategy) {
            case MURMUR3_128 -> Murmur3.hash(input, hash);
            case RAPIDHASH_V3 -> RapidHash.hashPair(input, hash);
            case RAPIDHASH_NANO_V3 -> RapidHash.nanoPair(input, hash);
            case KOMIHASH_5 -> KomiHash.hashPair(input, hash);
            case SHA_256 -> { /* Independently seeded digest per slice in position(). */ }
        }
    }

    private void finishDigest() {
        try {
            digest.digest(digestBytes, 0, digestBytes.length);
        } catch (DigestException e) {
            throw new IllegalStateException("SHA-256 did not fit its output buffer", e);
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static long mix64(long value) {
        // MurmurHash3's public-domain 64-bit finalizer (see NOTICE).
        value = (value ^ (value >>> 33)) * 0xff51afd7ed558ccdL;
        value = (value ^ (value >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return value ^ (value >>> 33);
    }

    private static final class Layer {
        final int index;
        final int sliceBits;
        final int probes;
        final long salt;
        final long[] words;
        final int[] setBits;
        final int[] pending;

        Layer(int index, int sliceBits, int probes) {
            this.index = index;
            this.sliceBits = sliceBits;
            this.probes = probes;
            this.salt = mix64(LAYER_DOMAIN + index * LAYER_INCREMENT);
            this.words = new long[(sliceBits / Long.SIZE) * probes];
            this.setBits = new int[probes];
            this.pending = new int[probes];
        }
    }
}
