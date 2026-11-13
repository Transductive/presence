package io.transductive.presence.bloom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HashInputTest {
    @Test
    void everyUtf16CodeUnitUsesTheJdkReplacementAndEncodingRules() {
        var input = new HashInput();
        for (int c = 0; c <= Character.MAX_VALUE; c++) {
            String value = String.valueOf((char) c);
            byte[] expected = value.getBytes(StandardCharsets.UTF_8);
            input.setString(value);
            assertEquals(expected.length, input.length(), "UTF-16 code unit " + c);
            for (int i = 0; i < expected.length; i++) {
                assertEquals(expected[i] & 0xffL, input.read8(i), "UTF-16 code unit " + c);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc\u0000xyz", "caf\u00e9\u00ff", "\u03bb\u4f1a\u5458", "a\ud83c\udf31z",
            "\ud800x\udc00\ud800\ud800\udc00"})
    void arbitraryReadsAndDigestsMatchUtf8AcrossWindowBoundaries(String pattern) throws Exception {
        var input = new HashInput();
        var digest = MessageDigest.getInstance("SHA-256");
        String full = pattern.repeat(300);
        for (int length = 0; length <= 600; length++) {
            String text = full.substring(0, length);
            byte[] expected = text.getBytes(StandardCharsets.UTF_8);
            input.setString(text);
            assertEquals(expected.length, input.length());
            for (int offset = 0; offset < expected.length; offset++) {
                assertEquals(expected[offset] & 0xffL, input.read8(offset));
                if (offset + 8 <= expected.length) {
                    assertEquals(littleEndian(expected, offset, 8), input.read64(offset));
                }
                if (offset + 4 <= expected.length) {
                    assertEquals(littleEndian(expected, offset, 4), input.read32(offset));
                }
            }
            // Moving backward forces a rewind after longer inputs, as a second seed does.
            if (expected.length >= 8) {
                assertEquals(littleEndian(expected, 0, 8), input.read64(0));
            }
            input.update(digest);
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected), digest.digest());
            input.update(digest);
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected), digest.digest());
            input.clear();
            input.setBytes(expected);
            input.update(digest);
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected), digest.digest());
        }
    }

    @Test
    void allFastHashesMatchEncodedBytesForRandomUtf16AndLongInputs() {
        var input = new HashInput();
        var actual = new HashPair();
        var random = new Random(693);
        for (int round = 0; round < 300; round++) {
            int length = round < 257 ? round : 1_000 + random.nextInt(10_000);
            var chars = new char[length];
            for (int i = 0; i < chars.length; i++) {
                chars[i] = (char) random.nextInt(65_536);
            }
            String text = new String(chars);
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            input.setString(text);
            Murmur3.hash(input, actual);
            assertPair(Murmur3.hash(bytes), actual);
            RapidHash.hashPair(input, actual);
            assertPair(RapidHash.hashPair(bytes), actual);
            RapidHash.nanoPair(input, actual);
            assertPair(RapidHash.nanoPair(bytes), actual);
            KomiHash.hashPair(input, actual);
            assertPair(KomiHash.hashPair(bytes), actual);
        }
    }

    @Test
    void unicodeReadsOutsideTheInputFailInsteadOfRefillingForever() {
        var input = new HashInput();
        input.setString("\u4f1a".repeat(100));
        assertThrows(IndexOutOfBoundsException.class, () -> input.read8(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> input.read8(300));
        assertThrows(IndexOutOfBoundsException.class, () -> input.read64(297));
    }

    @Test
    void shortEncodingBoundaryAndOptionalWorkspaceKeepTheSameUtf8Bytes() throws Exception {
        String[] values = {"\u4f1a".repeat(84), "\u4f1a".repeat(85), "\u4f1a".repeat(86),
                "a".repeat(84) + "\ud800", "a".repeat(83) + "\ud83c\udf31",
                "a".repeat(84) + "\ud83c\udf31", "a".repeat(84) + "\ud800x", ""};
        var actual = new HashPair();
        var digest = MessageDigest.getInstance("SHA-256");
        for (boolean stringWorkspace : new boolean[] {false, true}) {
            var input = new HashInput(stringWorkspace);
            for (String value : values) {
                byte[] expected = value.getBytes(StandardCharsets.UTF_8);
                input.setString(value);
                assertEquals(expected.length, input.length());
                for (int offset = 0; offset < expected.length; offset++) {
                    assertEquals(expected[offset] & 0xffL, input.read8(offset));
                }
                input.update(digest);
                assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected), digest.digest());
                Murmur3.hash(input, actual);
                assertPair(Murmur3.hash(expected), actual);
                RapidHash.hashPair(input, actual);
                assertPair(RapidHash.hashPair(expected), actual);
                RapidHash.nanoPair(input, actual);
                assertPair(RapidHash.nanoPair(expected), actual);
                KomiHash.hashPair(input, actual);
                assertPair(KomiHash.hashPair(expected), actual);
                input.clear();
            }
        }
    }

    @Test
    void rebindingAcrossInputKindsDoesNotReuseAnOldWindowOrCursor() throws Exception {
        var input = new HashInput();
        var digest = MessageDigest.getInstance("SHA-256");
        String[] values = {"ascii".repeat(80), "short ascii", "\u4f1a\ud83c\udf31".repeat(100),
                "caf\u00e9", "", "\ud800", "z".repeat(256), "\u4f1a".repeat(85), "final"};
        for (String value : values) {
            byte[] expected = value.getBytes(StandardCharsets.UTF_8);
            input.setString(value);
            assertEquals(expected.length, input.length());
            for (int i = expected.length - 1; i >= 0; i--) {
                assertEquals(expected[i] & 0xffL, input.read8(i));
            }
            input.update(digest);
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(expected), digest.digest());
            // A borrowed byte array must not remain selected when the next string is bound.
            input.setBytes(new byte[] {17, 23});
        }
        input.clear();
        assertEquals(0, input.length());
        input.setString("after clear");
        input.update(digest);
        assertArrayEquals(MessageDigest.getInstance("SHA-256")
                .digest("after clear".getBytes(StandardCharsets.UTF_8)), digest.digest());
    }

    private static void assertPair(HashPair expected, HashPair actual) {
        assertEquals(expected.first(), actual.first());
        assertEquals(expected.second(), actual.second());
    }

    private static long littleEndian(byte[] bytes, int offset, int count) {
        long value = 0;
        for (int i = 0; i < count; i++) {
            value |= (bytes[offset + i] & 0xffL) << (i * 8);
        }
        return value;
    }
}
