package io.transductive.presence.bloom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class UnsignedRemainderTest {
    private static final BigInteger TWO_64 = BigInteger.ONE.shiftLeft(64);

    @Test
    void reciprocalUsesTheUnsignedCeilingIncludingPowersOfTwo() {
        for (int divisor : divisors()) {
            BigInteger modulus = BigInteger.valueOf(divisor);
            long expected = TWO_64.add(modulus).subtract(BigInteger.ONE).divide(modulus).longValue();
            assertEquals(expected, UnsignedRemainder.reciprocal(divisor), "Divisor " + divisor);
        }
    }

    @Test
    void boundariesAroundMultiplesAndSignedTransitionsMatchBigInteger() {
        for (int divisor : divisors()) {
            long reciprocal = UnsignedRemainder.reciprocal(divisor);
            for (long value : new long[] {0, 1, 2, 3, divisor - 1L, divisor, divisor + 1L,
                    2L * divisor - 1, 2L * divisor, Long.MAX_VALUE - 1, Long.MAX_VALUE,
                    Long.MIN_VALUE, Long.MIN_VALUE + 1, -3, -2, -1}) {
                assertRemainder(value, divisor, reciprocal);
            }
            for (int exponent : new int[] {31, 32, 63, 64}) {
                BigInteger modulus = BigInteger.valueOf(divisor);
                BigInteger multiple = BigInteger.ONE.shiftLeft(exponent).divide(modulus).multiply(modulus);
                for (int offset = -2; offset <= 2; offset++) {
                    long value = multiple.add(BigInteger.valueOf(offset)).mod(TWO_64).longValue();
                    assertRemainder(value, divisor, reciprocal);
                }
            }
        }
    }

    @Test
    void randomizedFullWidthDividendsAndPositiveIntDivisorsMatchBigInteger() {
        var random = new Random(4_719_503);
        for (int round = 0; round < 20_000; round++) {
            int divisor = random.nextInt(Integer.MAX_VALUE) + 1;
            assertRemainder(random.nextLong(), divisor, UnsignedRemainder.reciprocal(divisor));
        }
    }

    @Test
    void overestimatedQuotientIsCorrectedWithoutChangingExactQuotients() {
        int divisor = 3;
        long reciprocal = UnsignedRemainder.reciprocal(divisor);
        long value = -2L;
        long rawRemainder = value - Math.unsignedMultiplyHigh(value, reciprocal) * divisor;
        assertTrue(rawRemainder < 0, "This input must exercise the correction branch");
        assertRemainder(value, divisor, reciprocal);
        assertRemainder(3, divisor, reciprocal);
        assertRemainder(0, divisor, reciprocal);
    }

    private static void assertRemainder(long value, int divisor, long reciprocal) {
        BigInteger unsigned = BigInteger.valueOf(value);
        if (value < 0) {
            unsigned = unsigned.add(TWO_64);
        }
        int expected = unsigned.mod(BigInteger.valueOf(divisor)).intValueExact();
        assertEquals(expected, UnsignedRemainder.remainder(value, divisor, reciprocal),
                () -> "Dividend " + Long.toUnsignedString(value) + ", divisor " + divisor);
    }

    private static List<Integer> divisors() {
        var divisors = new TreeSet<Integer>();
        divisors.add(1);
        divisors.add(3);
        divisors.add(5);
        divisors.add(7);
        divisors.add(10);
        divisors.add(257);
        divisors.add(Integer.MAX_VALUE);
        for (int exponent = 1; exponent <= 30; exponent++) {
            int power = 1 << exponent;
            divisors.add(power - 1);
            divisors.add(power);
            divisors.add(power + 1);
        }
        return List.copyOf(divisors);
    }
}
