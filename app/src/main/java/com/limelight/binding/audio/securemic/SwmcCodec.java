package com.limelight.binding.audio.securemic;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Starwire's encrypted microphone format, "SWMC" version 1: the bytes of every message and the
 * keys, exactly as docs/dev/mic-protocol.md describes them. The PC's receiver (windows/SecureMic.cs)
 * implements the same, and both are tested against app/src/test/resources/swmc-v1-vectors.txt.
 *
 * Plain Java with the platform's crypto (HMAC-SHA256, AES-GCM, ECDH on P-256), all there since
 * Android 5.0, so it runs in JVM tests as it does on the device. Parsing never throws on what
 * arrives from the network: it returns -1, false or null instead.
 */
public final class SwmcCodec {
    public static final int VERSION = 1;
    public static final int TYPE_HELLO = 1;
    public static final int TYPE_HELLO_ACK = 2;
    public static final int TYPE_AUDIO = 3;
    public static final int TYPE_REHELLO = 4;
    public static final int TYPE_PAIR_REQUEST = 5;
    public static final int TYPE_PAIR_ACCEPT = 6;
    public static final int TYPE_PAIR_REJECT = 7;

    // Magic 4, version 1, type 1, reserved 2, deviceId 8, context 8
    public static final int HEADER = 24;
    public static final int ID_LENGTH = 8;
    public static final int MAC_LENGTH = 16;
    public static final int KEY_LENGTH = 32;
    public static final int POINT_LENGTH = 65;
    public static final int MAX_NAME_BYTES = 64;
    public static final int HELLO_LENGTH = HEADER + MAC_LENGTH;                    // 40, REHELLO too
    public static final int ACK_LENGTH = HEADER + 8 + 1 + 3 + MAC_LENGTH;          // 52
    public static final int ACK_STATUS_AT = HEADER + 8;
    public static final int REJECT_LENGTH = HEADER + 1;                             // 25
    public static final int PAIR_REQUEST_LENGTH = HEADER + POINT_LENGTH + 1 + MAC_LENGTH;   // 106 + the name
    public static final int PAIR_ACCEPT_LENGTH = HEADER + POINT_LENGTH + 8 + 1 + MAC_LENGTH + MAC_LENGTH;   // 130 + the name
    // Where a PAIR_ACCEPT's fields start
    public static final int ACCEPT_POINT_AT = HEADER;
    public static final int ACCEPT_RECEIVER_ID_AT = HEADER + POINT_LENGTH;
    public static final int ACCEPT_NAME_LENGTH_AT = ACCEPT_RECEIVER_ID_AT + ID_LENGTH;
    public static final int ACCEPT_NAME_AT = ACCEPT_NAME_LENGTH_AT + 1;
    // AUDIO: the u32 counter after the header; the header and counter are authenticated in the clear
    public static final int COUNTER_AT = HEADER;
    public static final int AUDIO_AAD = HEADER + 4;
    public static final int NONCE_AT = 16;
    public static final int MAX_AUDIO_PLAINTEXT = 1024;   // the VBAN packet inside (Starwire sends 540)
    public static final int MAX_AUDIO_LENGTH = AUDIO_AAD + MAX_AUDIO_PLAINTEXT + Aead.TAG_LENGTH;
    public static final long MAX_COUNTER = 0xFFFFFFFFL;

    public static final int STATUS_READY = 0;
    public static final int STATUS_NO_OUTPUT = 1;
    public static final int STATUS_BUSY = 2;
    public static final int REJECT_NOT_PAIRING = 1;
    public static final int REJECT_WRONG_CODE = 2;
    public static final int REJECT_EXPIRED = 3;

    private static final byte[] LABEL_AUTH = ascii("starwire-mic/1 auth");
    private static final byte[] LABEL_AUDIO = ascii("starwire-mic/1 audio");
    private static final byte[] LABEL_PAIRING_SALT = ascii("starwire-mic/1 pairing");
    private static final byte[] LABEL_CODE = ascii("code");
    private static final byte[] LABEL_PAIR = ascii("starwire-mic/1 pair");
    private static final byte[] LABEL_CONFIRM = ascii("starwire-mic/1 confirm");

    private static volatile ECParameterSpec p256;

    private SwmcCodec() {
    }

    // ---------------------------------------------------------------- bytes
    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    public static void putU32(byte[] b, int at, long v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >> 8);
        b[at + 2] = (byte) (v >> 16);
        b[at + 3] = (byte) (v >> 24);
    }

    public static long getU32(byte[] b, int at) {
        return (b[at] & 0xFFL) | (b[at + 1] & 0xFFL) << 8 | (b[at + 2] & 0xFFL) << 16 | (b[at + 3] & 0xFFL) << 24;
    }

    public static boolean constantTimeEquals(byte[] a, int aAt, byte[] b, int bAt, int length) {
        int diff = 0;
        for (int i = 0; i < length; i++) {
            diff |= a[aAt + i] ^ b[bAt + i];
        }
        return diff == 0;
    }

    // ---------------------------------------------------------------- HMAC and HKDF (RFC 5869)
    static byte[] hmac(byte[] key, byte[] data, int at, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        mac.update(data, at, length);
        return mac.doFinal();
    }

    /** The first 16 bytes of HMAC-SHA256. */
    public static byte[] mac16(byte[] key, byte[] data, int at, int length) throws GeneralSecurityException {
        return Arrays.copyOf(hmac(key, data, at, length), MAC_LENGTH);
    }

    /** HKDF-SHA256; no salt (null or empty) means 32 zero bytes, as RFC 5869 says. */
    public static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
        byte[] prk = hmac(salt == null || salt.length == 0 ? new byte[32] : salt, ikm, 0, ikm.length);
        byte[] okm = new byte[length];
        byte[] t = new byte[0];
        int done = 0;
        for (int i = 1; done < length; i++) {
            byte[] input = concat(t, info, new byte[] {(byte) i});
            t = hmac(prk, input, 0, input.length);
            int n = Math.min(t.length, length - done);
            System.arraycopy(t, 0, okm, done, n);
            done += n;
        }
        return okm;
    }

    // ---------------------------------------------------------------- keys
    public static byte[] authKey(byte[] pairKey, byte[] deviceId) throws GeneralSecurityException {
        return hkdf(null, pairKey, concat(LABEL_AUTH, deviceId), KEY_LENGTH);
    }

    public static byte[] audioKey(byte[] pairKey, byte[] deviceId, byte[] clientRandom, byte[] serverRandom)
            throws GeneralSecurityException {
        return hkdf(concat(clientRandom, serverRandom), pairKey, concat(LABEL_AUDIO, deviceId), KEY_LENGTH);
    }

    public static byte[] codeKey(byte[] code) throws GeneralSecurityException {
        return hkdf(LABEL_PAIRING_SALT, code, LABEL_CODE, KEY_LENGTH);
    }

    public static byte[] pairKey(byte[] codeKey, byte[] zHash, byte[] deviceId, byte[] pairNonce,
                                 byte[] phonePoint, byte[] receiverPoint) throws GeneralSecurityException {
        return hkdf(codeKey, zHash, concat(LABEL_PAIR, deviceId, pairNonce, phonePoint, receiverPoint), KEY_LENGTH);
    }

    public static byte[] confirm(byte[] pairKey) throws GeneralSecurityException {
        return mac16(pairKey, LABEL_CONFIRM, 0, LABEL_CONFIRM.length);
    }

    // ---------------------------------------------------------------- the header
    public static void writeHeader(byte[] p, int type, byte[] deviceId, byte[] context) {
        p[0] = 'S';
        p[1] = 'W';
        p[2] = 'M';
        p[3] = 'C';
        p[4] = VERSION;
        p[5] = (byte) type;
        p[6] = 0;
        p[7] = 0;
        System.arraycopy(deviceId, 0, p, 8, ID_LENGTH);
        System.arraycopy(context, 0, p, 16, ID_LENGTH);
    }

    /**
     * The packet's type if it has SWMC v1's header (magic, version, zero reserved bytes), else -1.
     * Everything else is up to the MAC or the tag.
     */
    public static int typeOf(byte[] p, int length) {
        if (p == null || length < HEADER || length > p.length || p[0] != 'S' || p[1] != 'W' || p[2] != 'M' || p[3] != 'C'
                || p[4] != VERSION || p[6] != 0 || p[7] != 0) {
            return -1;
        }
        return p[5] & 0xFF;
    }

    /** Whether the packet's device id is this one. */
    public static boolean isFor(byte[] p, byte[] deviceId) {
        return constantTimeEquals(p, 8, deviceId, 0, ID_LENGTH);
    }

    /** Whether the packet's context is these 8 bytes. */
    public static boolean contextIs(byte[] p, byte[] context) {
        return constantTimeEquals(p, 16, context, 0, ID_LENGTH);
    }

    /** The last 16 bytes are the MAC of everything before them. */
    public static boolean macOk(byte[] key, byte[] p, int length) throws GeneralSecurityException {
        if (length < HEADER + MAC_LENGTH || length > p.length) {
            return false;
        }
        byte[] expected = mac16(key, p, 0, length - MAC_LENGTH);
        return constantTimeEquals(expected, 0, p, length - MAC_LENGTH, MAC_LENGTH);
    }

    private static byte[] signed(byte[] p, byte[] key) throws GeneralSecurityException {
        System.arraycopy(mac16(key, p, 0, p.length - MAC_LENGTH), 0, p, p.length - MAC_LENGTH, MAC_LENGTH);
        return p;
    }

    // ---------------------------------------------------------------- sessions
    /** HELLO: asks for a session; the context is Starwire's random number. */
    public static byte[] hello(byte[] authKey, byte[] deviceId, byte[] clientRandom) throws GeneralSecurityException {
        byte[] p = new byte[HELLO_LENGTH];
        writeHeader(p, TYPE_HELLO, deviceId, clientRandom);
        return signed(p, authKey);
    }

    /** HELLO_ACK: echoes the client random, then the receiver's (the session id) and a status. */
    public static byte[] helloAck(byte[] authKey, byte[] deviceId, byte[] clientRandom, byte[] serverRandom, int status)
            throws GeneralSecurityException {
        byte[] p = new byte[ACK_LENGTH];
        writeHeader(p, TYPE_HELLO_ACK, deviceId, clientRandom);
        System.arraycopy(serverRandom, 0, p, HEADER, ID_LENGTH);
        p[ACK_STATUS_AT] = (byte) status;
        return signed(p, authKey);
    }

    /** REHELLO: the receiver doesn't know the session named in the context. */
    public static byte[] rehello(byte[] authKey, byte[] deviceId, byte[] sessionId) throws GeneralSecurityException {
        byte[] p = new byte[HELLO_LENGTH];
        writeHeader(p, TYPE_REHELLO, deviceId, sessionId);
        return signed(p, authKey);
    }

    /**
     * AUDIO: header (context: the session id), counter, then the VBAN packet encrypted and its
     * tag, written into out. The nonce is out[16..28) and out[0..28) is authenticated. Returns the
     * packet's length.
     */
    public static int audio(Aead aead, byte[] deviceId, byte[] sessionId, long counter,
                            byte[] vban, int vbanLength, byte[] out) throws GeneralSecurityException {
        if (vbanLength < 1 || vbanLength > MAX_AUDIO_PLAINTEXT || counter < 0 || counter > MAX_COUNTER) {
            throw new IllegalArgumentException("can't seal " + vbanLength + " bytes with counter " + counter);
        }
        writeHeader(out, TYPE_AUDIO, deviceId, sessionId);
        putU32(out, COUNTER_AT, counter);
        return AUDIO_AAD + aead.seal(out, NONCE_AT, out, 0, AUDIO_AAD, vban, 0, vbanLength, out, AUDIO_AAD);
    }

    /** The VBAN packet inside an AUDIO packet, written to out; -1 if it's malformed or not authentic. */
    public static int openAudio(Aead aead, byte[] p, int length, byte[] out) throws GeneralSecurityException {
        if (typeOf(p, length) != TYPE_AUDIO || length < AUDIO_AAD + 1 + Aead.TAG_LENGTH || length > MAX_AUDIO_LENGTH) {
            return -1;
        }
        return aead.open(p, NONCE_AT, p, 0, AUDIO_AAD, p, AUDIO_AAD, length - AUDIO_AAD, out, 0);
    }

    // ---------------------------------------------------------------- pairing
    /**
     * PAIR_REQUEST: header (context: the pairing nonce), Starwire's one-time public key, its name,
     * and a MAC with the code's key.
     */
    public static byte[] pairRequest(byte[] codeKey, byte[] deviceId, byte[] pairNonce, byte[] phonePoint, String name)
            throws GeneralSecurityException {
        byte[] n = nameBytes(name);
        byte[] p = new byte[PAIR_REQUEST_LENGTH + n.length];
        writeHeader(p, TYPE_PAIR_REQUEST, deviceId, pairNonce);
        System.arraycopy(phonePoint, 0, p, HEADER, POINT_LENGTH);
        p[HEADER + POINT_LENGTH] = (byte) n.length;
        System.arraycopy(n, 0, p, HEADER + POINT_LENGTH + 1, n.length);
        return signed(p, codeKey);
    }

    /** A PAIR_REQUEST's name length if it's shaped like one (the MAC and point are checked later), else -1. */
    public static int pairRequestNameLength(byte[] p, int length) {
        if (typeOf(p, length) != TYPE_PAIR_REQUEST || length < PAIR_REQUEST_LENGTH || p[HEADER] != 4) {
            return -1;
        }
        int nameLength = p[HEADER + POINT_LENGTH] & 0xFF;
        return nameLength <= MAX_NAME_BYTES && length == PAIR_REQUEST_LENGTH + nameLength ? nameLength : -1;
    }

    /**
     * PAIR_ACCEPT: header (the request's device id and nonce), the receiver's one-time public key,
     * its id, its name, the confirmation made with the new pairing key, and a MAC with the code's key.
     */
    public static byte[] pairAccept(byte[] codeKey, byte[] pairKey, byte[] deviceId, byte[] pairNonce,
                                    byte[] receiverPoint, byte[] receiverId, String name) throws GeneralSecurityException {
        byte[] n = nameBytes(name);
        byte[] p = new byte[PAIR_ACCEPT_LENGTH + n.length];
        writeHeader(p, TYPE_PAIR_ACCEPT, deviceId, pairNonce);
        int at = HEADER;
        System.arraycopy(receiverPoint, 0, p, at, POINT_LENGTH);
        at += POINT_LENGTH;
        System.arraycopy(receiverId, 0, p, at, ID_LENGTH);
        at += ID_LENGTH;
        p[at++] = (byte) n.length;
        System.arraycopy(n, 0, p, at, n.length);
        at += n.length;
        System.arraycopy(confirm(pairKey), 0, p, at, MAC_LENGTH);
        return signed(p, codeKey);
    }

    /** A PAIR_ACCEPT's name length if it's shaped like one, else -1. */
    public static int pairAcceptNameLength(byte[] p, int length) {
        if (typeOf(p, length) != TYPE_PAIR_ACCEPT || length < PAIR_ACCEPT_LENGTH || p[HEADER] != 4) {
            return -1;
        }
        int nameLength = p[ACCEPT_NAME_LENGTH_AT] & 0xFF;
        return nameLength <= MAX_NAME_BYTES && length == PAIR_ACCEPT_LENGTH + nameLength ? nameLength : -1;
    }

    /** PAIR_REJECT: the request's header with type 7, then why. Not authenticated. */
    public static byte[] pairReject(byte[] request, int reason) {
        byte[] p = new byte[REJECT_LENGTH];
        System.arraycopy(request, 0, p, 0, HEADER);
        p[5] = TYPE_PAIR_REJECT;
        p[HEADER] = (byte) reason;
        return p;
    }

    /** The reason in a PAIR_REJECT, or -1 if it isn't one. */
    public static int pairRejectReason(byte[] p, int length) {
        return typeOf(p, length) == TYPE_PAIR_REJECT && length == REJECT_LENGTH ? p[HEADER] & 0xFF : -1;
    }

    /** A name as UTF-8, cut to 64 bytes without splitting a character. */
    public static byte[] nameBytes(String name) {
        if (name == null) {
            name = "";
        }
        byte[] b = name.getBytes(StandardCharsets.UTF_8);
        while (b.length > MAX_NAME_BYTES) {
            int end = name.offsetByCodePoints(name.length(), -1);
            name = name.substring(0, end);
            b = name.getBytes(StandardCharsets.UTF_8);
        }
        return b;
    }

    /**
     * A name that came over the network, made safe to show and store: no control or formatting
     * characters (which could break a line or reverse the text), runs of spaces made one.
     */
    public static String cleanName(String raw, String fallback) {
        StringBuilder sb = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < raw.length(); ) {
            int c = raw.codePointAt(i);
            i += Character.charCount(c);
            int type = Character.getType(c);
            if (Character.isISOControl(c) || Character.isWhitespace(c) || Character.isSpaceChar(c)
                    || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR || type == Character.SURROGATE
                    || type == Character.UNASSIGNED || type == Character.PRIVATE_USE) {
                space = sb.length() > 0;
                continue;
            }
            if (space) {
                sb.append(' ');
                space = false;
            }
            sb.appendCodePoint(c);
        }
        return sb.length() == 0 ? fallback : sb.toString();
    }

    // ---------------------------------------------------------------- ECDH on P-256
    /** P-256's parameters, as this platform's provider has them. */
    public static ECParameterSpec p256() throws GeneralSecurityException {
        ECParameterSpec spec = p256;
        if (spec == null) {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            spec = ((ECPublicKey) g.generateKeyPair().getPublic()).getParams();
            p256 = spec;
        }
        return spec;
    }

    /** A new one-time key pair for pairing. */
    public static KeyPair newKeyPair(SecureRandom random) throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), random);
        return g.generateKeyPair();
    }

    private static byte[] fixed32(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        int n = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - n, out, 32 - n, n);
        return out;
    }

    /** 0x04, X, Y. */
    public static byte[] encodePoint(PublicKey key) {
        ECPoint w = ((ECPublicKey) key).getW();
        return concat(new byte[] {4}, fixed32(w.getAffineX()), fixed32(w.getAffineY()));
    }

    /**
     * The public key in p[at..at+65), or null unless it's an uncompressed point on P-256 with both
     * coordinates below the field prime. Checked here rather than trusting each provider to.
     */
    public static PublicKey decodePoint(byte[] p, int at) {
        try {
            if (at < 0 || p.length - at < POINT_LENGTH || p[at] != 4) {
                return null;
            }
            BigInteger x = new BigInteger(1, Arrays.copyOfRange(p, at + 1, at + 33));
            BigInteger y = new BigInteger(1, Arrays.copyOfRange(p, at + 33, at + 65));
            ECParameterSpec params = p256();
            BigInteger prime = ((ECFieldFp) params.getCurve().getField()).getP();
            if (x.compareTo(prime) >= 0 || y.compareTo(prime) >= 0) {
                return null;
            }
            BigInteger lhs = y.multiply(y).mod(prime);
            BigInteger rhs = x.pow(3).add(params.getCurve().getA().multiply(x)).add(params.getCurve().getB()).mod(prime);
            if (!lhs.equals(rhs)) {
                return null;
            }
            return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), params));
        }
        catch (GeneralSecurityException | RuntimeException e) {
            return null;
        }
    }

    /** SHA-256 of the shared X coordinate: what Windows' ECDiffieHellmanCng gives with its Hash KDF. */
    public static byte[] ecdhHash(PrivateKey mine, PublicKey theirs) throws GeneralSecurityException {
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(mine);
        ka.doPhase(theirs, true);
        byte[] x = ka.generateSecret();
        if (x.length != 32) {
            throw new GeneralSecurityException("ECDH gave " + x.length + " bytes");
        }
        return MessageDigest.getInstance("SHA-256").digest(x);
    }
}
