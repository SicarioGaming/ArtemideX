package com.limelight.binding.audio.securemic;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The shared vectors file (app/src/test/resources/swmc-v1-vectors.txt, the same bytes as
 * windows/tests/vectors/swmc-v1-vectors.txt) and the fixed inputs it was made from, as
 * docs/dev/mic-protocol.md lists them.
 */
final class SwmcVectors {
    static final String SHA256 = "bc79b6d1e099e89bbfc93125a987afb96a02bd5bfe8f4480ced36b369bfd810a";

    static final byte[] PAIR_KEY = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    static final byte[] DEVICE_ID = hex("a0a1a2a3a4a5a6a7");
    static final byte[] CLIENT_RANDOM = hex("c0c1c2c3c4c5c6c7");
    static final byte[] SERVER_RANDOM = hex("5051525354555657");
    static final byte[] CODE = hex("11121314151617181920");
    static final byte[] PAIR_NONCE = hex("7071727374757677");
    static final byte[] RECEIVER_ID = hex("b0b1b2b3b4b5b6b7");
    static final String PHONE_NAME = "Test phone";
    static final String PC_NAME = "TEST-PC";
    // RFC 5903 section 8.1 (256-bit random ECP group): i is the phone, r the receiver
    static final byte[] ECDH_I = hex("C88F01F510D9AC3F70A292DAA2316DE544E9AAB8AFE84049C62A9C57862D1433");
    static final byte[] ECDH_GIX = hex("DAD0B65394221CF9B051E1FECA5787D098DFE637FC90B9EF945D0C3772581180");
    static final byte[] ECDH_GIY = hex("5271A0461CDB8252D61F1C456FA3E59AB1F45B33ACCF5F58389E0577B8990BB3");
    static final byte[] ECDH_R = hex("C6EF9C5D78AE012A011164ACB397CE2088685D8F06BF9BE0B283AB46476BEE53");
    static final byte[] ECDH_GRX = hex("D12DFB5289C8D4F81208B70270398C342296970A0BCCB74C736FC7554494BF63");
    static final byte[] ECDH_GRY = hex("56FBF3CA366CC23E8157854C13C58D6AAC23F046ADA30F8353E74F33039872AB");
    static final byte[] ECDH_GIRX = hex("D6840F6B42F6EDAFD13116E0E12565202FEF8E9ECE7DCE03812464D04B9442DE");

    private SwmcVectors() {
    }

    static byte[] fileBytes() throws IOException {
        try (InputStream in = SwmcVectors.class.getClassLoader().getResourceAsStream("swmc-v1-vectors.txt")) {
            if (in == null) {
                throw new IOException("swmc-v1-vectors.txt isn't on the test classpath");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** name=hex lines, in the file's order. */
    static Map<String, String> read() throws IOException {
        Map<String, String> v = new LinkedHashMap<>();
        for (String line : new String(fileBytes(), StandardCharsets.US_ASCII).split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                v.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return v;
    }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    static String hex(byte[] b) {
        return hex(b, 0, b.length);
    }

    static String hex(byte[] b, int at, int length) {
        StringBuilder sb = new StringBuilder(length * 2);
        for (int i = 0; i < length; i++) {
            sb.append(Character.forDigit((b[at + i] >> 4) & 0xF, 16)).append(Character.forDigit(b[at + i] & 0xF, 16));
        }
        return sb.toString();
    }

    static byte[] point(byte[] x, byte[] y) {
        byte[] p = new byte[SwmcCodec.POINT_LENGTH];
        p[0] = 4;
        System.arraycopy(x, 0, p, 1, 32);
        System.arraycopy(y, 0, p, 33, 32);
        return p;
    }

    static byte[] phonePoint() {
        return point(ECDH_GIX, ECDH_GIY);
    }

    static byte[] receiverPoint() {
        return point(ECDH_GRX, ECDH_GRY);
    }

    static PrivateKey privateKey(byte[] d) throws GeneralSecurityException {
        return KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, d), SwmcCodec.p256()));
    }

    static PublicKey publicKey(byte[] x, byte[] y) throws GeneralSecurityException {
        return KeyFactory.getInstance("EC").generatePublic(
                new ECPublicKeySpec(new ECPoint(new BigInteger(1, x), new BigInteger(1, y)), SwmcCodec.p256()));
    }

    /** The phone's fixed one-time key pair (RFC 5903's i). */
    static KeyPair phoneKeyPair() throws GeneralSecurityException {
        return new KeyPair(publicKey(ECDH_GIX, ECDH_GIY), privateKey(ECDH_I));
    }

    /** The VBAN packet in the vectors: frame 7 of a ramp, stream "Stream1", as VbanSender packs it. */
    static byte[] vbanFrame(int frame, int offset) {
        byte[] b = new byte[28 + 512];
        b[0] = 'V';
        b[1] = 'B';
        b[2] = 'A';
        b[3] = 'N';
        b[4] = 3;
        b[5] = (byte) 255;
        b[6] = 0;
        b[7] = 1;
        byte[] name = "Stream1".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, b, 8, name.length);
        SwmcCodec.putU32(b, 24, frame);
        for (int i = 0; i < 256; i++) {
            short s = (short) ((i + offset) * 37 - 16000);
            b[28 + i * 2] = (byte) s;
            b[29 + i * 2] = (byte) (s >> 8);
        }
        return b;
    }
}
