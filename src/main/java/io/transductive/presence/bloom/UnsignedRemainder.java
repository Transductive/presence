package io.transductive.presence.bloom;

/** Exact unsigned 64-bit reduction by a fixed positive 31-bit divisor. */
final class UnsignedRemainder {
    private UnsignedRemainder() { }

    static long reciprocal(int divisor) {
        if (divisor <= 0) {
            throw new IllegalArgumentException("divisor must be positive");
        }
        return divisor == 1 ? 0 : Long.divideUnsigned(-1L, divisor) + 1;
    }

    static int remainder(long value, int divisor, long reciprocal) {
        if (divisor == 1) {
            return 0;
        }
        // R = ceil(2^64 / divisor). The high product estimates the quotient
        // exactly or one too high. The wrapping difference is in (-divisor,
        // divisor), so one sign-mask correction recovers the exact remainder.
        long quotient = Math.unsignedMultiplyHigh(value, reciprocal);
        long remainder = value - quotient * divisor;
        return (int) (remainder + ((remainder >> 63) & divisor));
    }
}
