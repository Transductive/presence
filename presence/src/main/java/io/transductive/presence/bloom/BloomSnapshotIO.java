package io.transductive.presence.bloom;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.zip.CRC32C;

/** Versioned, big-endian snapshot framing. Payloads never contain user objects. */
final class BloomSnapshotIO {
    private BloomSnapshotIO() {}

    static ByteBuffer allocate(int magic, int payloadBytes) {
        return ByteBuffer.allocate(Math.addExact(payloadBytes, 12)).putInt(magic).putInt(1);
    }

    static byte[] finish(ByteBuffer buffer) {
        if (buffer.remaining() != Integer.BYTES) throw new IllegalStateException("Incomplete Bloom snapshot");
        var checksum = new CRC32C();
        checksum.update(buffer.array(), 0, buffer.position());
        buffer.putInt((int) checksum.getValue());
        return buffer.array();
    }

    static ByteBuffer read(byte[] snapshot, int magic) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.length < 12) throw invalid();
        var buffer = ByteBuffer.wrap(snapshot);
        if (buffer.getInt() != magic || buffer.getInt() != 1) throw invalid();
        var checksum = new CRC32C();
        checksum.update(snapshot, 0, snapshot.length - Integer.BYTES);
        if ((int) checksum.getValue() != buffer.getInt(snapshot.length - Integer.BYTES)) throw invalid();
        return buffer.slice(8, snapshot.length - 12);
    }

    static int strategyId(HashingStrategy strategy) {
        return switch (strategy) {
            case MURMUR3_128 -> 1;
            case RAPIDHASH_V3 -> 2;
            case RAPIDHASH_NANO_V3 -> 3;
            case KOMIHASH_5 -> 4;
            case SHA_256 -> 5;
        };
    }

    static HashingStrategy strategy(int id) {
        return switch (id) {
            case 1 -> HashingStrategy.MURMUR3_128;
            case 2 -> HashingStrategy.RAPIDHASH_V3;
            case 3 -> HashingStrategy.RAPIDHASH_NANO_V3;
            case 4 -> HashingStrategy.KOMIHASH_5;
            case 5 -> HashingStrategy.SHA_256;
            default -> throw invalid();
        };
    }

    static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid, corrupt, or unsupported Bloom snapshot");
    }
}
