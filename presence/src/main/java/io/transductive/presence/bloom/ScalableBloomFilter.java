package io.transductive.presence.bloom;

import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
 * deletion, eviction, or fixed total memory budget. Layer snapshots support persistence.
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
    private static final int METADATA_MAGIC = 0x5052534d;
    private static final int LAYER_MAGIC = 0x5052534c;

    private final ArrayList<Layer> layers = new ArrayList<>();
    private final List<Layer> layerView = Collections.unmodifiableList(layers);
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
    private final long initialCapacity;
    private final double tighteningRatio;

    private ScalableBloomFilter(long initialCapacity, double probability,
            Function<? super T, byte[]> encoder, boolean utf8Strings,
            HashingStrategy strategy, int growthFactor, double tighteningRatio) {
        this(initialCapacity, probability, encoder, utf8Strings, strategy, growthFactor, tighteningRatio, null);
    }

    private ScalableBloomFilter(long initialCapacity, double probability,
            Function<? super T, byte[]> encoder, boolean utf8Strings,
            HashingStrategy strategy, int growthFactor, double tighteningRatio, List<Layer> restored) {
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
        this.initialCapacity = initialCapacity;
        this.tighteningRatio = tighteningRatio;
        double requiredSliceBits = Math.max(Long.SIZE, Math.ceil(initialCapacity / LN_2));
        if (requiredSliceBits > (1L << 30)) {
            throw new IllegalArgumentException("initialCapacity exceeds the supported layer size");
        }
        int sliceBits = Integer.highestOneBit((int) requiredSliceBits - 1) << 1;
        if (restored == null) layers.add(newLayer(0, sliceBits));
        else layers.addAll(restored);
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
     * Returns an unmodifiable, live view of layers in oldest-first index order.
     * Layers expose inspection and independent snapshots, never writable bit arrays.
     * The view grows with the filter; do not iterate concurrently with insertion.
     * @return read-only layer views
     */
    public List<Layer> layers() { return layerView; }

    /**
     * Snapshots configuration and the current layer count, without copying any layer bits.
     * Capture this and all referenced layer snapshots without concurrent mutations.
     * @return versioned, checksummed metadata for {@link #restoreStrings(byte[], Iterable)}
     */
    public byte[] snapshotMetadata() {
        var buffer = BloomSnapshotIO.allocate(METADATA_MAGIC, 40);
        buffer.putInt(BloomSnapshotIO.strategyId(strategy)).putInt(utf8Strings ? 1 : 0)
                .putLong(initialCapacity).putDouble(targetProbability).putInt(growthFactor)
                .putDouble(tighteningRatio).putInt(layers.size());
        return BloomSnapshotIO.finish(buffer);
    }

    /**
     * Restores a string filter from metadata and exactly its referenced layers.
     * Restored filters retain membership, growth settings, and hashing semantics.
     * @param metadata bytes from {@link #snapshotMetadata()}
     * @param snapshots layer snapshots in oldest-first index order
     * @return an independent mutable filter
     * @throws IllegalArgumentException for incompatible, corrupt, missing, or extra layers
     */
    public static ScalableBloomFilter<String> restoreStrings(byte[] metadata, Iterable<byte[]> snapshots) {
        return restore(metadata, snapshots, null, true);
    }

    /**
     * Restores a byte-encoded filter using the original encoding semantics supplied by the caller.
     * @param metadata bytes from {@link #snapshotMetadata()}
     * @param snapshots layer snapshots in oldest-first index order
     * @param encoder deterministic encoder compatible with the original filter
     * @param <T> value type
     * @return an independent mutable filter
     * @throws IllegalArgumentException for incompatible, corrupt, missing, or extra layers
     */
    public static <T> ScalableBloomFilter<T> restore(byte[] metadata, Iterable<byte[]> snapshots,
            Function<? super T, byte[]> encoder) {
        return restore(metadata, snapshots, Objects.requireNonNull(encoder, "encoder"), false);
    }

    private static <T> ScalableBloomFilter<T> restore(byte[] metadata, Iterable<byte[]> snapshots,
            Function<? super T, byte[]> encoder, boolean strings) {
        var buffer = BloomSnapshotIO.read(metadata, METADATA_MAGIC);
        if (buffer.remaining() != 40) throw BloomSnapshotIO.invalid();
        var strategy = BloomSnapshotIO.strategy(buffer.getInt());
        if (buffer.getInt() != (strings ? 1 : 0)) throw BloomSnapshotIO.invalid();
        long capacity = buffer.getLong();
        double probability = buffer.getDouble();
        int growth = buffer.getInt();
        double tightening = buffer.getDouble();
        int count = buffer.getInt();
        // Validate configuration without allocating bit arrays from untrusted dimensions.
        var filter = new ScalableBloomFilter<T>(capacity, probability, encoder, strings,
                strategy, growth, tightening, List.of());
        if (count <= 0) throw BloomSnapshotIO.invalid();
        var iterator = Objects.requireNonNull(snapshots, "snapshots").iterator();
        long sliceBits = Integer.highestOneBit((int) Math.max(64, Math.ceil(capacity / LN_2)) - 1) << 1;
        for (int i = 0; i < count; i++) {
            if (!iterator.hasNext()) throw BloomSnapshotIO.invalid();
            int probes = Math.max(1, (int) Math.ceil(-(filter.initialLogProbability + i * filter.logTightening) / LN_2));
            filter.layers.add(Layer.restore(iterator.next(), i, sliceBits, probes));
            sliceBits *= growth;
        }
        if (iterator.hasNext()) throw BloomSnapshotIO.invalid();
        return filter;
    }

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
     * Estimates represented distinct values from each layer's slice occupancies.
     * Assumes uniform hashing. Values whose insertion was already a possible
     * match do not change occupancy, so this is not an exact accepted-input count.
     * Cost is proportional to total slices, without scanning bit arrays.
     * @return sum of the layer estimates, zero for an empty filter
     */
    public double estimatedElementCount() {
        double total = 0;
        for (Layer layer : layers) total += layer.estimatedElementCount();
        return total;
    }

    /**
     * Returns the largest slice fill ratio in the newest, writable layer.
     * Growth occurs before a slice would exceed one-half fill; this value drops
     * when a new layer is allocated. Cost is proportional to the active probe count.
     * @return active layer's maximum slice fill, between zero and one-half
     */
    public double activeLayerFillRatio() { return layers.getLast().maximumSliceFillRatio(); }

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

    /** Read-only access to one partitioned Bloom layer; mutations remain owned by its filter. */
    public static final class Layer {
        private final int index;
        private final int sliceBits;
        private final int probes;
        private final long salt;
        private final long[] words;
        private final int[] setBits;
        private final int[] pending;

        private Layer(int index, int sliceBits, int probes) {
            this.index = index;
            this.sliceBits = sliceBits;
            this.probes = probes;
            this.salt = mix64(LAYER_DOMAIN + index * LAYER_INCREMENT);
            this.words = new long[(sliceBits / Long.SIZE) * probes];
            this.setBits = new int[probes];
            this.pending = new int[probes];
        }

        /**
         * Identifies this layer within its owning filter.
         * @return stable zero-based index
         */
        public int index() { return index; }

        /**
         * Reports this layer's bit-array payload size.
         * @return number of allocated bits
         */
        public long bitSize() { return (long) sliceBits * probes; }

        /**
         * Reports this layer's probe count.
         * @return number of disjoint probe slices
         */
        public int hashFunctionCount() { return probes; }

        /**
         * Estimates this layer's distinct values by averaging slice occupancy estimates.
         * Assumes uniform hashing; does not count put calls or scan the bit array.
         * @return approximate represented element count
         */
        public double estimatedElementCount() {
            double total = 0;
            for (int count : setBits) total -= sliceBits * Math.log1p(-(double) count / sliceBits);
            return total / probes;
        }

        /**
         * Returns the fullest slice's occupancy, which drives layer rollover.
         * @return maximum slice fill ratio, between zero and one-half
         */
        public double maximumSliceFillRatio() {
            int maximum = 0;
            for (int count : setBits) maximum = Math.max(maximum, count);
            return (double) maximum / sliceBits;
        }

        /**
         * Copies this layer into an independent versioned snapshot with an integrity checksum.
         * Pair it with the owning filter's metadata; it is not a standalone fixed Bloom.
         * @return newly allocated layer bytes
         */
        public byte[] snapshot() {
            var buffer = BloomSnapshotIO.allocate(LAYER_MAGIC, 12 + words.length * Long.BYTES);
            buffer.putInt(index).putInt(sliceBits).putInt(probes);
            for (long word : words) buffer.putLong(word);
            return BloomSnapshotIO.finish(buffer);
        }

        private static Layer restore(byte[] snapshot, int index, long sliceBits, int probes) {
            var buffer = BloomSnapshotIO.read(snapshot, LAYER_MAGIC);
            if (sliceBits < 64 || sliceBits > Integer.MAX_VALUE / probes || buffer.remaining() < 12
                    || buffer.getInt() != index || buffer.getInt() != sliceBits || buffer.getInt() != probes
                    || buffer.remaining() != sliceBits / 64 * probes * Long.BYTES) throw BloomSnapshotIO.invalid();
            var layer = new Layer(index, (int) sliceBits, probes);
            int wordsPerSlice = layer.sliceBits / 64;
            for (int i = 0; i < layer.words.length; i++) {
                layer.words[i] = buffer.getLong();
                layer.setBits[i / wordsPerSlice] += Long.bitCount(layer.words[i]);
            }
            for (int count : layer.setBits) if (count > sliceBits / 2) throw BloomSnapshotIO.invalid();
            return layer;
        }
    }
}
