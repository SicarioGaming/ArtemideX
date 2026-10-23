package com.limelight.nvstream.http;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * The building blocks of pairing: hex as the host sends it, the PIN, and AES-128 with Bouncy
 * Castle, checked against the FIPS-197 test vector. Pairing itself needs a host.
 */
public class PairingCryptoTest {
    private static Object call(String name, Class<?>[] types, Object... args) throws Throwable {
        Method m = PairingManager.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static String toHex(byte[] bytes) throws Throwable {
        return (String) call("bytesToHex", new Class<?>[] {byte[].class}, (Object) bytes);
    }

    private static byte[] fromHex(String hex) throws Throwable {
        return (byte[]) call("hexToBytes", new Class<?>[] {String.class}, hex);
    }

    private static byte[] aes(boolean encrypt, byte[] data, byte[] key) throws Throwable {
        return (byte[]) call(encrypt ? "encryptAes" : "decryptAes", new Class<?>[] {byte[].class, byte[].class}, data, key);
    }

    @Test
    public void hexRoundTrip() throws Throwable {
        byte[] all = new byte[256];
        for (int i = 0; i < all.length; i++) all[i] = (byte) i;
        String hex = toHex(all);
        assertEquals(512, hex.length());
        assertTrue(hex.startsWith("000102") && hex.endsWith("FDFEFF"));
        assertArrayEquals(all, fromHex(hex));
        // The host may send lower case
        assertArrayEquals(new byte[] {(byte) 0xAB, (byte) 0xCD}, fromHex("abCD"));
        assertEquals("", toHex(new byte[0]));
    }

    @Test
    public void oddLengthHexIsRefused() throws Throwable {
        try {
            fromHex("ABC");
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    // FIPS-197 appendix C.1: AES-128
    @Test
    public void aesMatchesTheStandardTestVector() throws Throwable {
        byte[] key = fromHex("000102030405060708090A0B0C0D0E0F");
        byte[] plain = fromHex("00112233445566778899AABBCCDDEEFF");
        byte[] cipher = aes(true, plain, key);
        assertEquals("69C4E0D86A7B0430D8CDB78070B4C55A", toHex(cipher));
        assertArrayEquals(plain, aes(false, cipher, key));
    }

    // Input is padded with zeros to whole 16-byte blocks, each block on its own (ECB)
    @Test
    public void aesPadsToWholeBlocks() throws Throwable {
        byte[] key = fromHex("000102030405060708090A0B0C0D0E0F");
        byte[] plain = new byte[20];
        for (int i = 0; i < plain.length; i++) plain[i] = (byte) (i * 7);
        byte[] cipher = aes(true, plain, key);
        assertEquals(32, cipher.length);
        byte[] back = aes(false, cipher, key);
        byte[] padded = new byte[32];
        System.arraycopy(plain, 0, padded, 0, plain.length);
        assertArrayEquals(padded, back);
        assertEquals(0, aes(true, new byte[0], key).length);
    }

    // Uses SecureRandom; what's checked holds for every PIN it can return
    @Test
    public void pinsAreFourDigits() {
        for (int i = 0; i < 200; i++) {
            String pin = PairingManager.generatePinString();
            assertTrue(pin, pin.matches("[0-9]{4}"));
        }
    }
}
