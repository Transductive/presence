package io.transductive.presence.bloom;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BloomSnapshotTest {
    @ParameterizedTest
    @EnumSource(HashingStrategy.class)
    void fixedRoundTripPreservesEveryAnswerAndOwnsItsBytes(HashingStrategy strategy) {
        var original = BloomFilter.forStrings(1_000, 0.01, strategy);
        var empty = BloomFilter.restoreStrings(original.snapshot());
        assertFalse(empty.mightContain(""));
        for (int i = 0; i < 1_000; i++) original.put("member-" + i);
        for (String value : List.of("", "é", "😀", "a\ud800b")) original.put(value);
        byte[] snapshot = original.snapshot();
        var restored = BloomFilter.restoreStrings(snapshot);
        Arrays.fill(snapshot, (byte) 0);
        assertEquals(original.bitSize(), restored.bitSize());
        assertEquals(original.hashFunctionCount(), restored.hashFunctionCount());
        assertEquals(strategy, restored.hashingStrategy());
        for (int i = 0; i < 3_000; i++) {
            String value = "member-" + i;
            assertEquals(original.mightContain(value), restored.mightContain(value));
        }
        for (String value : List.of("", "é", "😀", "a\ud800b")) assertTrue(restored.mightContain(value));
        byte[] before = original.snapshot();
        for (int i = 0; i < 1_000; i++) restored.put("new-" + i);
        assertArrayEquals(before, original.snapshot());
    }

    @ParameterizedTest
    @EnumSource(HashingStrategy.class)
    void scalableRoundTripPreservesLayersFillCountsAndFutureGrowth(HashingStrategy strategy) {
        var original = ScalableBloomFilter.forStrings(1, 0.001, strategy, 4, 0.8);
        var views = original.layers();
        assertThrows(UnsupportedOperationException.class, views::clear);
        for (int i = 0; i < 700; i++) original.put("member-" + i);
        assertTrue(views.size() > 1);
        var snapshots = views.stream().map(ScalableBloomFilter.Layer::snapshot).toList();
        byte[] metadata = original.snapshotMetadata();
        var restored = ScalableBloomFilter.restoreStrings(metadata, snapshots);
        Arrays.fill(metadata, (byte) 0);
        snapshots.forEach(bytes -> Arrays.fill(bytes, (byte) 0));
        assertEquals(original.bitSize(), restored.bitSize());
        assertEquals(original.estimatedFalsePositiveProbability(), restored.estimatedFalsePositiveProbability());
        for (int i = 0; i < 2_000; i++) {
            String value = "member-" + i;
            assertEquals(original.mightContain(value), restored.mightContain(value));
            original.put(value);
            restored.put(value);
        }
        assertEquals(original.layerCount(), restored.layerCount());
        for (int i = 0; i < original.layerCount(); i++) {
            assertEquals(i, original.layers().get(i).index());
            assertArrayEquals(original.layers().get(i).snapshot(), restored.layers().get(i).snapshot());
        }
        assertEquals(original.bitSize(), original.layers().stream().mapToLong(ScalableBloomFilter.Layer::bitSize).sum());
    }

    @Test
    void byteEncodedFiltersRequireTheCallerEncoderAndRejectStringMode() {
        var fixed = BloomFilter.create(100, 0.01, (Integer value) -> ByteBuffer.allocate(4).putInt(value).array());
        fixed.put(42);
        var fixedCopy = BloomFilter.restore(fixed.snapshot(), (Integer value) -> ByteBuffer.allocate(4).putInt(value).array());
        assertTrue(fixedCopy.mightContain(42));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.restoreStrings(fixed.snapshot()));
        var scalable = ScalableBloomFilter.create(1, 0.01, (String value) -> value.getBytes(StandardCharsets.UTF_8));
        scalable.put("42");
        var layers = scalable.layers().stream().map(ScalableBloomFilter.Layer::snapshot).toList();
        var copy = ScalableBloomFilter.restore(scalable.snapshotMetadata(), layers,
                (String value) -> value.getBytes(StandardCharsets.UTF_8));
        assertTrue(copy.mightContain("42"));
        assertThrows(IllegalArgumentException.class, () -> ScalableBloomFilter.restoreStrings(scalable.snapshotMetadata(), layers));
    }

    @Test
    void rejectsTruncationCorruptionVersionsAndInconsistentLayerSequences() {
        var fixed = BloomFilter.forStrings(3, 0.01);
        fixed.put("value");
        byte[] valid = fixed.snapshot();
        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = Arrays.copyOf(valid, length);
            assertThrows(IllegalArgumentException.class, () -> BloomFilter.restoreStrings(truncated));
        }
        byte[] corrupt = valid.clone();
        corrupt[corrupt.length - 5] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.restoreStrings(corrupt));
        byte[] future = valid.clone();
        ByteBuffer.wrap(future).putInt(4, 2);
        checksum(future);
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.restoreStrings(future));
        byte[] dimensions = valid.clone();
        ByteBuffer.wrap(dimensions).putInt(16, Integer.MAX_VALUE);
        checksum(dimensions);
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.restoreStrings(dimensions));

        var scalable = ScalableBloomFilter.forStrings(1, 0.01);
        for (int i = 0; i < 1_000; i++) scalable.put("member-" + i);
        byte[] metadata = scalable.snapshotMetadata();
        var layers = new ArrayList<>(scalable.layers().stream().map(ScalableBloomFilter.Layer::snapshot).toList());
        assertThrows(IllegalArgumentException.class,
                () -> ScalableBloomFilter.restoreStrings(metadata, layers.subList(1, layers.size())));
        layers.add(layers.getFirst());
        assertThrows(IllegalArgumentException.class, () -> ScalableBloomFilter.restoreStrings(metadata, layers));
        layers.removeLast();
        layers.set(1, layers.getFirst());
        assertThrows(IllegalArgumentException.class, () -> ScalableBloomFilter.restoreStrings(metadata, layers));
    }

    private static void checksum(byte[] bytes) {
        var checksum = new CRC32C();
        checksum.update(bytes, 0, bytes.length - 4);
        ByteBuffer.wrap(bytes).putInt(bytes.length - 4, (int) checksum.getValue());
    }
}
