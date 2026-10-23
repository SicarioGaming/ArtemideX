// Test sender for e2e-loopback.ps1 (GPL-3.0, part of Starwire): plays Starwire's part against a
// receiver running on this PC. It pairs with the code the receiver printed, checks the answers to
// wrong and repeated pairing requests, streams a quiet tone in real time, and then sends packets
// the receiver must refuse. It isn't the app's code: the app has its own implementation of the
// same format, and both are held to tests/vectors/swmc-v1-vectors.txt. Based on the research
// prototype; Java 8 and its standard library only.
//
//   java SwmcTestSender <port> <code> <packets> <amplitude> <expected HELLO_ACK status>
//
// Prints PASS and FAIL lines, then one SENT line with what the receiver should report.

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class SwmcTestSender {

    static final int T_HELLO = 1, T_HELLO_ACK = 2, T_AUDIO = 3, T_REHELLO = 4, T_PAIR_REQUEST = 5, T_PAIR_ACCEPT = 6, T_PAIR_REJECT = 7;
    static final int HEADER = 24, MAC = 16, TAG = 16, COUNTER_AT = 24, AUDIO_AAD = 28;
    static final int HELLO_LEN = HEADER + MAC, ACK_LEN = HEADER + 8 + 1 + 3 + MAC, REJECT_LEN = HEADER + 1;
    static final int ACCEPT_MIN = HEADER + 65 + 8 + 1 + MAC + MAC;

    static int failures;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) failures++;
    }

    static byte[] ascii(String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    static void putU32(byte[] b, int at, long v) {
        b[at] = (byte) v; b[at + 1] = (byte) (v >> 8); b[at + 2] = (byte) (v >> 16); b[at + 3] = (byte) (v >> 24);
    }

    static byte[] random(SecureRandom rng, int n) {
        byte[] b = new byte[n];
        rng.nextBytes(b);
        return b;
    }

    static boolean same(byte[] a, int aAt, byte[] b, int bAt, int n) {
        int diff = 0;
        for (int i = 0; i < n; i++) diff |= a[aAt + i] ^ b[bAt + i];
        return diff == 0;
    }

    // HMAC-SHA256, HKDF (RFC 5869), and the 16-byte MACs
    static byte[] hmac(byte[] key, byte[] data, int at, int len) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        mac.update(data, at, len);
        return mac.doFinal();
    }

    static byte[] mac16(byte[] key, byte[] data, int at, int len) throws GeneralSecurityException {
        return Arrays.copyOf(hmac(key, data, at, len), MAC);
    }

    static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
        byte[] prk = hmac(salt == null ? new byte[32] : salt, ikm, 0, ikm.length);
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

    static byte[] authKey(byte[] pairKey, byte[] deviceId) throws GeneralSecurityException {
        return hkdf(null, pairKey, concat(ascii("starwire-mic/1 auth"), deviceId), 32);
    }

    static byte[] audioKey(byte[] pairKey, byte[] deviceId, byte[] clientRandom, byte[] serverRandom) throws GeneralSecurityException {
        return hkdf(concat(clientRandom, serverRandom), pairKey, concat(ascii("starwire-mic/1 audio"), deviceId), 32);
    }

    static byte[] codeKey(byte[] code) throws GeneralSecurityException {
        return hkdf(ascii("starwire-mic/1 pairing"), code, ascii("code"), 32);
    }

    static void header(byte[] p, int type, byte[] deviceId, byte[] context) {
        p[0] = 'S'; p[1] = 'W'; p[2] = 'M'; p[3] = 'C';
        p[4] = 1;
        p[5] = (byte) type;
        System.arraycopy(deviceId, 0, p, 8, 8);
        System.arraycopy(context, 0, p, 16, 8);
    }

    static int typeOf(byte[] p, int len) {
        if (len < HEADER || p[0] != 'S' || p[1] != 'W' || p[2] != 'M' || p[3] != 'C' || p[4] != 1 || p[6] != 0 || p[7] != 0) return -1;
        return p[5] & 0xFF;
    }

    static boolean macOk(byte[] key, byte[] p, int len) throws GeneralSecurityException {
        return len >= HEADER + MAC && same(mac16(key, p, 0, len - MAC), 0, p, len - MAC, MAC);
    }

    static byte[] signed(byte[] p, byte[] key) throws GeneralSecurityException {
        System.arraycopy(mac16(key, p, 0, p.length - MAC), 0, p, p.length - MAC, MAC);
        return p;
    }

    static byte[] hello(byte[] authKey, byte[] deviceId, byte[] clientRandom) throws GeneralSecurityException {
        byte[] p = new byte[HELLO_LEN];
        header(p, T_HELLO, deviceId, clientRandom);
        return signed(p, authKey);
    }

    static byte[] pairRequest(byte[] codeKey, byte[] deviceId, byte[] nonce, byte[] point, String name) throws GeneralSecurityException {
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        byte[] p = new byte[HEADER + 65 + 1 + n.length + MAC];
        header(p, T_PAIR_REQUEST, deviceId, nonce);
        System.arraycopy(point, 0, p, HEADER, 65);
        p[HEADER + 65] = (byte) n.length;
        System.arraycopy(n, 0, p, HEADER + 66, n.length);
        return signed(p, codeKey);
    }

    // AES-256-GCM: nonce bytes 16 to 28, bytes 0 to 28 authenticated
    static final class Aead {
        final SecretKeySpec key;
        final Cipher cipher;

        Aead(byte[] key) throws GeneralSecurityException {
            this.key = new SecretKeySpec(key, "AES");
            cipher = Cipher.getInstance("AES/GCM/NoPadding");
        }

        int audio(byte[] deviceId, byte[] sessionId, long counter, byte[] vban, byte[] out) throws GeneralSecurityException {
            header(out, T_AUDIO, deviceId, sessionId);
            putU32(out, COUNTER_AT, counter);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, out, 16, 12));
            cipher.updateAAD(out, 0, AUDIO_AAD);
            return AUDIO_AAD + cipher.doFinal(vban, 0, vban.length, out, AUDIO_AAD);
        }
    }

    // Crockford base32: case, spaces and hyphens don't matter; O reads as 0, I and L as 1
    static final String B32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    static byte[] decodeCode(String text) {
        StringBuilder clean = new StringBuilder();
        for (char c : text.toUpperCase(Locale.ROOT).toCharArray()) {
            if (c == '-' || c == ' ') continue;
            if (c == 'O') c = '0';
            if (c == 'I' || c == 'L') c = '1';
            if (B32.indexOf(c) < 0) return null;
            clean.append(c);
        }
        if (clean.length() != 16) return null;
        byte[] out = new byte[10];
        int buffer = 0, bits = 0, at = 0;
        for (int i = 0; i < 16; i++) {
            buffer = (buffer << 5) | B32.indexOf(clean.charAt(i));
            bits += 5;
            if (bits >= 8) {
                out[at++] = (byte) (buffer >> (bits - 8));
                bits -= 8;
            }
        }
        return out;
    }

    // ECDH P-256; points travel as 0x04, X, Y and are checked to be on the curve
    static ECParameterSpec p256;

    static KeyPair newKeyPair() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair k = g.generateKeyPair();
        p256 = ((ECPublicKey) k.getPublic()).getParams();
        return k;
    }

    static byte[] fixed32(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        int n = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - n, out, 32 - n, n);
        return out;
    }

    static byte[] encodePoint(KeyPair k) {
        ECPoint w = ((ECPublicKey) k.getPublic()).getW();
        return concat(new byte[] {4}, fixed32(w.getAffineX()), fixed32(w.getAffineY()));
    }

    static PublicKey decodePoint(byte[] p, int at) throws GeneralSecurityException {
        if (p[at] != 4) throw new GeneralSecurityException("not an uncompressed point");
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(p, at + 1, at + 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(p, at + 33, at + 65));
        BigInteger prime = ((ECFieldFp) p256.getCurve().getField()).getP();
        if (x.compareTo(prime) >= 0 || y.compareTo(prime) >= 0) throw new GeneralSecurityException("coordinate out of range");
        BigInteger rhs = x.pow(3).add(p256.getCurve().getA().multiply(x)).add(p256.getCurve().getB()).mod(prime);
        if (!y.multiply(y).mod(prime).equals(rhs)) throw new GeneralSecurityException("point not on P-256");
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), p256));
    }

    static byte[] ecdhHash(PrivateKey mine, PublicKey theirs) throws GeneralSecurityException {
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(mine);
        ka.doPhase(theirs, true);
        return MessageDigest.getInstance("SHA-256").digest(ka.generateSecret());
    }

    // 256 mono samples at 48 kHz: a quiet 1 kHz sine, as Starwire's VBAN packets are laid out
    static byte[] vbanPacket(int frame, int amplitude) {
        byte[] b = new byte[28 + 512];
        b[0] = 'V'; b[1] = 'B'; b[2] = 'A'; b[3] = 'N';
        b[4] = 3; b[5] = (byte) 255; b[6] = 0; b[7] = 1;
        System.arraycopy(ascii("Stream1"), 0, b, 8, 7);
        putU32(b, 24, frame);
        for (int i = 0; i < 256; i++) {
            long t = (long) frame * 256 + i;
            short s = (short) Math.round(amplitude * Math.sin(2 * Math.PI * 1000 * t / 48000.0));
            b[28 + i * 2] = (byte) s;
            b[29 + i * 2] = (byte) (s >> 8);
        }
        return b;
    }

    // Sends a packet and waits for an answer about the same device and context (bytes 8 to 24),
    // of the wanted type; null if none comes in time. wanted -1: any packet about that device.
    static byte[] exchange(DatagramSocket sock, InetAddress host, int port, byte[] packet, int waitMs, int wanted) throws Exception {
        sock.send(new DatagramPacket(packet, packet.length, host, port));
        long until = System.currentTimeMillis() + waitMs;
        byte[] buf = new byte[2048];
        while (true) {
            long left = until - System.currentTimeMillis();
            if (left <= 0) return null;
            sock.setSoTimeout((int) left);
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            try {
                sock.receive(in);
            }
            catch (SocketTimeoutException e) {
                return null;
            }
            int n = in.getLength();
            int type = typeOf(buf, n);
            if (wanted < 0 ? type >= 0 && same(buf, 8, packet, 8, 8) : type == wanted && same(buf, 8, packet, 8, 16)) {
                return Arrays.copyOf(buf, n);
            }
        }
    }

    static int reason(byte[] reject) {
        return reject != null && reject.length == REJECT_LEN ? reject[HEADER] & 0xFF : -1;
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        byte[] code = decodeCode(args[1]);
        int packets = Integer.parseInt(args[2]);
        int amplitude = Integer.parseInt(args[3]);
        int expectStatus = Integer.parseInt(args[4]);
        check("the receiver's code reads as 16 characters", code != null);
        if (code == null) System.exit(1);
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        SecureRandom rng = new SecureRandom();
        try (DatagramSocket sock = new DatagramSocket(0, lo)) {
            // A wrong code, sent twice the way a device repeats itself: counted once
            byte[] typo = code.clone();
            typo[9] ^= 1;
            byte[] wrong = pairRequest(codeKey(typo), random(rng, 8), random(rng, 8), encodePoint(newKeyPair()), "e2e wrong code");
            int first = reason(exchange(sock, lo, port, wrong, 2000, T_PAIR_REJECT));
            int second = reason(exchange(sock, lo, port, wrong, 2000, T_PAIR_REJECT));
            check("a wrong code gets PAIR_REJECT 2, both times", first == 2 && second == 2);

            // The right code
            byte[] deviceId = random(rng, 8), nonce = random(rng, 8);
            KeyPair keys = newKeyPair();
            byte[] myPoint = encodePoint(keys);
            byte[] kCode = codeKey(code);
            byte[] request = pairRequest(kCode, deviceId, nonce, myPoint, "e2e test sender");
            byte[] accept = null;
            for (int attempt = 0; attempt < 12 && accept == null; attempt++) {
                accept = exchange(sock, lo, port, request, 500, T_PAIR_ACCEPT);
            }
            check("the right code gets a PAIR_ACCEPT", accept != null);
            if (accept == null) System.exit(1);
            int nameLen = accept.length >= ACCEPT_MIN ? accept[HEADER + 65 + 8] & 0xFF : -1;
            check("its length and MAC (with the code's key) are right", accept.length == ACCEPT_MIN + nameLen && macOk(kCode, accept, accept.length));
            byte[] receiverPoint = Arrays.copyOfRange(accept, HEADER, HEADER + 65);
            byte[] kPair = hkdf(kCode, ecdhHash(keys.getPrivate(), decodePoint(accept, HEADER)),
                    concat(ascii("starwire-mic/1 pair"), deviceId, nonce, myPoint, receiverPoint), 32);
            byte[] label = ascii("starwire-mic/1 confirm");
            byte[] confirm = mac16(kPair, label, 0, label.length);
            check("its confirmation matches the pairing key worked out here", same(confirm, 0, accept, HEADER + 65 + 9 + nameLen, MAC));
            System.out.println("INFO paired with " + new String(accept, HEADER + 65 + 9, nameLen, StandardCharsets.UTF_8));
            check("the same request again gets the same PAIR_ACCEPT", Arrays.equals(exchange(sock, lo, port, request, 1000, T_PAIR_ACCEPT), accept));
            byte[] other = pairRequest(kCode, random(rng, 8), random(rng, 8), encodePoint(newKeyPair()), "e2e second device");
            check("another request with the same code gets PAIR_REJECT 1: a code works once",
                    reason(exchange(sock, lo, port, other, 2000, T_PAIR_REJECT)) == 1);

            // A device that isn't paired gets no answer
            byte[] strangerId = random(rng, 8);
            byte[] strangerHello = hello(authKey(random(rng, 32), strangerId), strangerId, random(rng, 8));
            check("a HELLO from a device that isn't paired gets no answer", exchange(sock, lo, port, strangerHello, 700, -1) == null);

            // Audio for a session the receiver doesn't know gets a REHELLO
            byte[] kAuth = authKey(kPair, deviceId);
            byte[] buf = new byte[2048];
            byte[] unknownSession = random(rng, 8);
            int n = new Aead(random(rng, 32)).audio(deviceId, unknownSession, 0, vbanPacket(0, amplitude), buf);
            byte[] rehello = exchange(sock, lo, port, Arrays.copyOf(buf, n), 1500, T_REHELLO);
            check("audio for a session the receiver doesn't know gets a REHELLO for that session",
                    rehello != null && rehello.length == HELLO_LEN && macOk(kAuth, rehello, rehello.length));

            // A session
            byte[] clientRandom = random(rng, 8);
            byte[] hello = hello(kAuth, deviceId, clientRandom);
            byte[] ack = null;
            for (int attempt = 0; attempt < 8 && ack == null; attempt++) {
                ack = exchange(sock, lo, port, hello, 250, T_HELLO_ACK);
            }
            check("HELLO gets a HELLO_ACK with a good MAC", ack != null && ack.length == ACK_LEN && macOk(kAuth, ack, ack.length));
            if (ack == null) System.exit(1);
            check("with status " + expectStatus, (ack[HEADER + 8] & 0xFF) == expectStatus);
            byte[] sessionId = Arrays.copyOfRange(ack, HEADER, HEADER + 8);
            Aead aead = new Aead(audioKey(kPair, deviceId, clientRandom, sessionId));

            // The stream, in real time: 256 samples at 48 kHz every 5.333 ms
            long hash = 0xcbf29ce484222325L;
            List<byte[]> sent = new ArrayList<>();
            long start = System.nanoTime();
            for (int i = 0; i < packets; i++) {
                byte[] vban = vbanPacket(i, amplitude);
                for (int j = 28; j < vban.length; j++) {
                    hash = (hash ^ (vban[j] & 0xFF)) * 0x100000001b3L;
                }
                n = aead.audio(deviceId, sessionId, i, vban, buf);
                sock.send(new DatagramPacket(buf, n, lo, port));
                sent.add(Arrays.copyOf(buf, n));
                long due = start + (i + 1) * 5333333L;
                while (System.nanoTime() < due) Thread.sleep(0, 200000);
            }

            // What must not play: a replay, a late packet, a forged counter, another key, plain VBAN
            sock.send(new DatagramPacket(sent.get(5), sent.get(5).length, lo, port));
            sock.send(new DatagramPacket(sent.get(packets - 2), sent.get(packets - 2).length, lo, port));
            byte[] forged = sent.get(packets - 1).clone();
            putU32(forged, COUNTER_AT, packets + 100);
            sock.send(new DatagramPacket(forged, forged.length, lo, port));
            n = new Aead(random(rng, 32)).audio(deviceId, sessionId, packets + 200, vbanPacket(0, amplitude), buf);
            sock.send(new DatagramPacket(buf, n, lo, port));
            byte[] plain = vbanPacket(packets + 300, amplitude);
            sock.send(new DatagramPacket(plain, plain.length, lo, port));
            System.out.println("INFO sent " + packets + " audio packets in " + (System.nanoTime() - start) / 1000000 + " ms, then 5 that must be refused");
            System.out.println(String.format(Locale.ROOT, "SENT played=%d pcm=%016x replayed_or_late=2 forged=2 unencrypted=1 unpaired=1 unknown_session=1 wrong_codes=1",
                    packets, hash));
        }
        System.out.println(failures == 0 ? "sender: all passed" : "sender: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
