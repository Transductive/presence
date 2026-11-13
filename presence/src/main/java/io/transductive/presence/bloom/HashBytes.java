package io.transductive.presence.bloom;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/** Unaligned, explicitly little-endian reads using Java 21 core APIs. */
final class HashBytes {
    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INTS = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private HashBytes() { }

    static long read64(byte[] bytes, int offset) {
        return (long) LONGS.get(bytes, offset);
    }

    static long read32(byte[] bytes, int offset) {
        return Integer.toUnsignedLong((int) INTS.get(bytes, offset));
    }
}
