package io.transductive.presence.flink;

import io.transductive.presence.bloom.BloomFilter;
import io.transductive.presence.bloom.ScalableBloomFilter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

/** One key's membership, with a versioned manifest independent of Flink serializers. */
final class BloomGroup {
    private static final int MAGIC = 0x5052464d;
    private final BloomFilter<String> fixed;
    private final ScalableBloomFilter<String> scalable;
    private long events;

    private BloomGroup(BloomFilter<String> fixed, ScalableBloomFilter<String> scalable, long events) {
        this.fixed = fixed;
        this.scalable = scalable;
        this.events = events;
    }

    static BloomGroup create(BloomConfiguration configuration) {
        return configuration.scalable()
                ? new BloomGroup(null, ScalableBloomFilter.forStrings(configuration.capacity(),
                        configuration.probability(), configuration.hashingStrategy()), 0)
                : new BloomGroup(BloomFilter.forStrings(configuration.capacity(),
                        configuration.probability(), configuration.hashingStrategy()), null, 0);
    }

    boolean observe(String id) {
        boolean present = scalable != null ? scalable.mightContain(id) : fixed.mightContain(id);
        if (!present) {
            if (scalable != null) scalable.put(id);
            else fixed.put(id);
        }
        if (events != Long.MAX_VALUE) events++;
        return present;
    }

    long events() { return events; }
    int layerCount() { return scalable != null ? scalable.layerCount() : 1; }
    byte[] snapshotLayer(int index) {
        if (scalable != null) return scalable.layers().get(index).snapshot();
        if (index != 0) throw new IndexOutOfBoundsException(index);
        return fixed.snapshot();
    }

    byte[] snapshotMetadata(BloomConfiguration configuration) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(1);
            output.writeBoolean(configuration.scalable());
            output.writeLong(configuration.capacity());
            output.writeDouble(configuration.probability());
            output.writeUTF(configuration.hashingStrategy().name());
            output.writeLong(events);
            output.writeInt(layerCount());
            byte[] metadata = scalable != null ? scalable.snapshotMetadata() : new byte[0];
            output.writeInt(metadata.length);
            output.write(metadata);
        }
        return bytes.toByteArray();
    }

    static Metadata readMetadata(byte[] bytes, BloomConfiguration configuration) throws IOException {
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC || input.readInt() != 1) {
                throw new IOException("Unsupported Presence managed-state manifest");
            }
            if (input.readBoolean() != configuration.scalable() || input.readLong() != configuration.capacity()
                    || Double.compare(input.readDouble(), configuration.probability()) != 0
                    || !input.readUTF().equals(configuration.hashingStrategy().name())) {
                throw new IOException("Presence Bloom configuration differs from restored state. "
                        + "Keep filter type, capacity, probability, and hashing strategy unchanged when restoring this UID.");
            }
            long events = input.readLong();
            int count = input.readInt();
            int length = input.readInt();
            if (events < 0 || count <= 0 || count > 31 || (!configuration.scalable() && count != 1)
                    || length < 0 || length != input.available()) throw new IOException("Invalid Presence state manifest");
            byte[] metadata = input.readNBytes(length);
            if (!configuration.scalable() && length != 0) throw new IOException("Unexpected fixed Bloom metadata");
            return new Metadata(events, count, metadata);
        }
    }

    static BloomGroup restore(BloomConfiguration configuration, Metadata metadata, List<byte[]> layers) {
        if (layers.size() != metadata.layerCount()) throw new IllegalArgumentException("Missing Presence Bloom layers");
        return configuration.scalable()
                ? new BloomGroup(null, ScalableBloomFilter.restoreStrings(metadata.filterMetadata(), layers), metadata.events())
                : new BloomGroup(BloomFilter.restoreStrings(layers.getFirst()), null, metadata.events());
    }

    record Metadata(long events, int layerCount, byte[] filterMetadata) {}
}
