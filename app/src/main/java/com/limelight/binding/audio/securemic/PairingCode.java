package com.limelight.binding.audio.securemic;

import java.util.Locale;

/**
 * The one-time code the PC's receiver shows for pairing: 10 random bytes (80 bits) in Crockford's
 * base32, 16 characters in groups of four, like 2491-650N-2RBH-G690. There's no I, L, O or U, so
 * the code can't be misread; typing O for 0 or I or L for 1 still works.
 */
public final class PairingCode {
    public static final int BYTES = 10;
    public static final int CHARACTERS = 16;
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private PairingCode() {
    }

    /** 10 bytes as the receiver shows them. */
    public static String encode(byte[] code) {
        if (code.length != BYTES) {
            throw new IllegalArgumentException("a pairing code is 10 bytes");
        }
        StringBuilder sb = new StringBuilder(CHARACTERS + 3);
        int buffer = 0, bits = 0;
        for (byte b : code) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                if (sb.length() == 4 || sb.length() == 9 || sb.length() == 14) {
                    sb.append('-');
                }
                sb.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        return sb.toString();
    }

    /**
     * The 10 bytes of a typed code, or null if it isn't 16 valid characters. Case, spaces and
     * hyphens don't matter, and O reads as 0, I and L as 1.
     */
    public static byte[] decode(String text) {
        if (text == null) {
            return null;
        }
        int[] values = new int[CHARACTERS];
        int count = 0;
        for (char c : text.toUpperCase(Locale.ROOT).toCharArray()) {
            if (c == '-' || c == ' ') {
                continue;
            }
            int value = valueOf(c);
            if (value < 0 || count == CHARACTERS) {
                return null;
            }
            values[count++] = value;
        }
        if (count != CHARACTERS) {
            return null;
        }
        byte[] out = new byte[BYTES];
        int buffer = 0, bits = 0, at = 0;
        for (int value : values) {
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out[at++] = (byte) (buffer >> (bits - 8));
                bits -= 8;
            }
        }
        return out;
    }

    /**
     * What the code field shows while the user types: capitals, no spaces, a hyphen after every
     * four characters. Characters that can't be in a code stay, so decode() can say it's wrong
     * instead of the field quietly dropping what was typed.
     */
    public static String format(String typed) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (char c : typed.toUpperCase(Locale.ROOT).toCharArray()) {
            if (c == '-' || Character.isWhitespace(c)) {
                continue;
            }
            if (count > 0 && count % 4 == 0) {
                sb.append('-');
            }
            sb.append(c);
            count++;
        }
        return sb.toString();
    }

    private static int valueOf(char c) {
        if (c == 'O') {
            c = '0';
        }
        else if (c == 'I' || c == 'L') {
            c = '1';
        }
        return ALPHABET.indexOf(c);
    }
}
