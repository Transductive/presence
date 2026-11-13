package io.transductive.presence.bloom;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Reusable byte view of an array or the JDK-compatible UTF-8 encoding of a string. */
final class HashInput {
    private static final int OVERLAP = 64;
    private final byte[] window = new byte[256];
    private final char[] characterWindow;
    private final CharBuffer characterBuffer;
    private final ByteBuffer byteBuffer;
    private final CharsetEncoder encoder;
    private byte[] bytes;
    private String text;
    private int length;
    private boolean ascii;
    private int charOffset;
    private int windowStart;
    private int windowLength;

    HashInput() {
        this(true);
    }

    HashInput(boolean stringWorkspace) {
        if (!stringWorkspace) {
            characterWindow = null;
            characterBuffer = null;
            byteBuffer = null;
            encoder = null;
            return;
        }
        // Three bytes per UTF-16 code unit is the UTF-8 upper bound; surrogate
        // pairs use four bytes for two units, and malformed units become '?'.
        characterWindow = new char[window.length / 3];
        characterBuffer = CharBuffer.wrap(characterWindow);
        byteBuffer = ByteBuffer.wrap(window);
        encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        // Initialize the encoder's lazy surrogate parser before measured lookups.
        characterWindow[0] = '\ud800';
        characterWindow[1] = 'x';
        characterWindow[2] = '\ud83c';
        characterWindow[3] = '\udf31';
        characterBuffer.limit(4);
        encoder.encode(characterBuffer, byteBuffer, true);
        encoder.flush(byteBuffer);
        encoder.reset();
    }

    void setBytes(byte[] value) {
        bytes = value;
        text = null;
        length = value.length;
    }

    void setString(String value) {
        if (characterWindow != null && value.length() <= characterWindow.length) {
            setBufferedString(value);
            return;
        }
        bytes = null;
        text = value;
        ascii = true;
        // Combine the ASCII checks after the copy instead of branching for every
        // character. A Unicode prefix is rescanned below to count its UTF-8 bytes.
        int prefix = Math.min(value.length(), window.length);
        int characters = 0;
        for (int i = 0; i < prefix; i++) {
            char c = value.charAt(i);
            characters |= c;
            window[i] = (byte) c;
        }
        if ((characters & 0xff80) != 0) {
            prefix = 0;
        }
        if (prefix == value.length()) {
            length = prefix;
            bytes = window;
            rewind();
            return;
        }
        long encodedLength = prefix;
        for (int i = prefix; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x80) {
                encodedLength++;
            } else {
                ascii = false;
                if (c < 0x800) {
                    encodedLength += 2;
                } else if (!Character.isSurrogate(c)) {
                    encodedLength += 3;
                } else if (Character.isHighSurrogate(c) && i + 1 < value.length()
                        && Character.isLowSurrogate(value.charAt(i + 1))) {
                    encodedLength += 4;
                    i++;
                } else {
                    // String.getBytes(UTF_8) replaces each malformed surrogate with '?'.
                    encodedLength++;
                }
            }
        }
        if (encodedLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("UTF-8 encoding exceeds Integer.MAX_VALUE bytes");
        }
        length = (int) encodedLength;
        rewind();
        if (!ascii && length <= window.length - 3) {
            fill();
            bytes = window;
        }
    }

    private void setBufferedString(String value) {
        value.getChars(0, value.length(), characterWindow, 0);
        characterBuffer.clear().limit(value.length());
        byteBuffer.clear();
        encoder.reset();
        if (!encoder.encode(characterBuffer, byteBuffer, true).isUnderflow()
                || !encoder.flush(byteBuffer).isUnderflow()) {
            throw new AssertionError("Short UTF-8 input must fit the owned byte window");
        }
        length = byteBuffer.position();
        bytes = window;
        text = null;
        ascii = false;
        rewind();
    }

    void clear() {
        bytes = null;
        text = null;
        length = 0;
        rewind();
    }

    int length() {
        return length;
    }

    long read8(int offset) {
        if (bytes != null) {
            return bytes[offset] & 0xffL;
        }
        if (ascii) {
            return text.charAt(offset);
        }
        ensure(offset, 1);
        return window[offset - windowStart] & 0xffL;
    }

    long read32(int offset) {
        if (bytes != null) {
            return HashBytes.read32(bytes, offset);
        }
        if (ascii) {
            return (long) text.charAt(offset) | ((long) text.charAt(offset + 1) << 8)
                    | ((long) text.charAt(offset + 2) << 16) | ((long) text.charAt(offset + 3) << 24);
        }
        ensure(offset, Integer.BYTES);
        return HashBytes.read32(window, offset - windowStart);
    }

    long read64(int offset) {
        if (bytes != null) {
            return HashBytes.read64(bytes, offset);
        }
        if (ascii) {
            return (long) text.charAt(offset) | ((long) text.charAt(offset + 1) << 8)
                    | ((long) text.charAt(offset + 2) << 16) | ((long) text.charAt(offset + 3) << 24)
                    | ((long) text.charAt(offset + 4) << 32) | ((long) text.charAt(offset + 5) << 40)
                    | ((long) text.charAt(offset + 6) << 48) | ((long) text.charAt(offset + 7) << 56);
        }
        ensure(offset, Long.BYTES);
        return HashBytes.read64(window, offset - windowStart);
    }

    void update(MessageDigest digest) {
        if (bytes != null) {
            digest.update(bytes, 0, length);
            return;
        }
        rewind();
        if (ascii) {
            for (int offset = 0; offset < length;) {
                int count = Math.min(window.length, length - offset);
                for (int i = 0; i < count; i++) {
                    window[i] = (byte) text.charAt(offset + i);
                }
                digest.update(window, 0, count);
                offset += count;
            }
        } else {
            while (charOffset < text.length()) {
                windowLength = 0;
                fill();
                digest.update(window, 0, windowLength);
            }
        }
        rewind();
    }

    private void ensure(int offset, int width) {
        if (offset < 0 || offset > length - width) {
            throw new IndexOutOfBoundsException("Read outside the encoded input");
        }
        if (offset < windowStart) {
            rewind(); // The second seeded hash starts again at byte zero.
        }
        while (offset + width > windowStart + windowLength) {
            int keep = Math.min(OVERLAP, windowLength);
            System.arraycopy(window, windowLength - keep, window, 0, keep);
            windowStart += windowLength - keep;
            windowLength = keep;
            fill();
        }
    }

    private void rewind() {
        charOffset = 0;
        windowStart = 0;
        windowLength = 0;
    }

    private void fill() {
        // Reserve room for a whole supplementary character. The overlap also covers
        // nonsequential Komihash lanes and rapidhash's overlapping final 16-byte read.
        while (charOffset < text.length() && windowLength <= window.length - 4) {
            char c = text.charAt(charOffset++);
            if (c < 0x80) {
                window[windowLength++] = (byte) c;
            } else if (c < 0x800) {
                window[windowLength++] = (byte) (0xc0 | (c >>> 6));
                window[windowLength++] = (byte) (0x80 | (c & 0x3f));
            } else if (!Character.isSurrogate(c)) {
                window[windowLength++] = (byte) (0xe0 | (c >>> 12));
                window[windowLength++] = (byte) (0x80 | ((c >>> 6) & 0x3f));
                window[windowLength++] = (byte) (0x80 | (c & 0x3f));
            } else if (Character.isHighSurrogate(c) && charOffset < text.length()
                    && Character.isLowSurrogate(text.charAt(charOffset))) {
                int codePoint = Character.toCodePoint(c, text.charAt(charOffset++));
                window[windowLength++] = (byte) (0xf0 | (codePoint >>> 18));
                window[windowLength++] = (byte) (0x80 | ((codePoint >>> 12) & 0x3f));
                window[windowLength++] = (byte) (0x80 | ((codePoint >>> 6) & 0x3f));
                window[windowLength++] = (byte) (0x80 | (codePoint & 0x3f));
            } else {
                window[windowLength++] = '?';
            }
        }
    }
}
