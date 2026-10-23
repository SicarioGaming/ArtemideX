package com.limelight.binding.audio.securemic;

import static com.limelight.binding.audio.securemic.SwmcVectors.hex;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.limelight.RepoFiles;

import org.junit.Test;

import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * SWMC v1, the encrypted microphone's format (docs/dev/mic-protocol.md): the standards' known
 * answers, every line of the vectors file the Windows receiver is tested against too, and what
 * must be refused: tampering, truncation, the wrong key, replays and late packets, bad points,
 * bad codes, and random junk (a seeded fuzz).
 */
public class SwmcCodecTest {

    @Test
    public void theVectorsFileIsTheSharedOneByteForByte() throws Exception {
        byte[] ours = SwmcVectors.fileBytes();
        assertEquals(SwmcVectors.SHA256, hex(MessageDigest.getInstance("SHA-256").digest(ours)));
        byte[] receivers = Files.readAllBytes(RepoFiles.file("windows/tests/vectors/swmc-v1-vectors.txt").toPath());
        assertArrayEquals("the app's and the receiver's vectors differ", receivers, ours);
        assertEquals(16, SwmcVectors.read().size());
    }

    @Test
    public void standardsKnownAnswers() throws Exception {
        // GCM spec test case 16 (AES-256, 60 bytes, 20 bytes of additional data)
        Aead tc16 = new Aead(hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308"));
        byte[] pt = hex("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39");
        byte[] aad = hex("feedfacedeadbeeffeedfacedeadbeefabaddad2");
        byte[] iv = hex("cafebabefacedbaddecaf888");
        byte[] ct = new byte[pt.length + 16];
        assertEquals(ct.length, tc16.seal(iv, 0, aad, 0, aad.length, pt, 0, pt.length, ct, 0));
        assertEquals("522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662"
                + "76fc6ece0f4e1768cddf8853bb2d551b", hex(ct));
        byte[] back = new byte[pt.length];
        assertEquals(pt.length, tc16.open(iv, 0, aad, 0, aad.length, ct, 0, ct.length, back, 0));
        assertArrayEquals(pt, back);

        // HKDF, RFC 5869 test case 1
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
                hex(SwmcCodec.hkdf(hex("000102030405060708090a0b0c"), hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
                        hex("f0f1f2f3f4f5f6f7f8f9"), 42)));

        // ECDH P-256, RFC 5903 section 8.1, both directions, through the point checks
        PublicKey gi = SwmcCodec.decodePoint(SwmcVectors.phonePoint(), 0);
        PublicKey gr = SwmcCodec.decodePoint(SwmcVectors.receiverPoint(), 0);
        assertNotNull(gi);
        assertNotNull(gr);
        byte[] want = MessageDigest.getInstance("SHA-256").digest(SwmcVectors.ECDH_GIRX);
        assertArrayEquals(want, SwmcCodec.ecdhHash(SwmcVectors.privateKey(SwmcVectors.ECDH_I), gr));
        assertArrayEquals(want, SwmcCodec.ecdhHash(SwmcVectors.privateKey(SwmcVectors.ECDH_R), gi));
        assertArrayEquals(SwmcVectors.phonePoint(), SwmcCodec.encodePoint(gi));
    }

    // Each line made here from the fixed inputs, compared with the file; and no line goes unchecked
    @Test
    public void everyVectorLineIsReproduced() throws Exception {
        Map<String, String> want = SwmcVectors.read();
        Set<String> checked = new HashSet<>();

        byte[] ct = new byte[76];
        new Aead(hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")).seal(
                hex("cafebabefacedbaddecaf888"), 0, hex("feedfacedeadbeeffeedfacedeadbeefabaddad2"), 0, 20,
                hex("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39"),
                0, 60, ct, 0);
        same(want, checked, "kat.gcm_tc16", hex(ct));
        same(want, checked, "kat.hkdf_rfc5869_1", hex(SwmcCodec.hkdf(hex("000102030405060708090a0b0c"),
                hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), hex("f0f1f2f3f4f5f6f7f8f9"), 42)));
        byte[] z = SwmcCodec.ecdhHash(SwmcVectors.privateKey(SwmcVectors.ECDH_I),
                SwmcCodec.decodePoint(SwmcVectors.receiverPoint(), 0));
        same(want, checked, "kat.ecdh_rfc5903_sha256", hex(z));

        byte[] kAuth = SwmcCodec.authKey(SwmcVectors.PAIR_KEY, SwmcVectors.DEVICE_ID);
        byte[] kAudio = SwmcCodec.audioKey(SwmcVectors.PAIR_KEY, SwmcVectors.DEVICE_ID, SwmcVectors.CLIENT_RANDOM, SwmcVectors.SERVER_RANDOM);
        same(want, checked, "key.auth", hex(kAuth));
        same(want, checked, "key.audio", hex(kAudio));
        same(want, checked, "pkt.hello", hex(SwmcCodec.hello(kAuth, SwmcVectors.DEVICE_ID, SwmcVectors.CLIENT_RANDOM)));
        same(want, checked, "pkt.hello_ack", hex(SwmcCodec.helloAck(kAuth, SwmcVectors.DEVICE_ID, SwmcVectors.CLIENT_RANDOM,
                SwmcVectors.SERVER_RANDOM, SwmcCodec.STATUS_READY)));
        same(want, checked, "pkt.rehello", hex(SwmcCodec.rehello(kAuth, SwmcVectors.DEVICE_ID, SwmcVectors.SERVER_RANDOM)));
        byte[] vban = SwmcVectors.vbanFrame(7, 0);
        same(want, checked, "vban.frame7", hex(vban));
        byte[] out = new byte[SwmcCodec.MAX_AUDIO_LENGTH];
        int n = SwmcCodec.audio(new Aead(kAudio), SwmcVectors.DEVICE_ID, SwmcVectors.SERVER_RANDOM, 7, vban, vban.length, out);
        assertEquals(584, n);
        same(want, checked, "pkt.audio7", hex(out, 0, n));
        n = SwmcCodec.audio(new Aead(kAudio), SwmcVectors.DEVICE_ID, SwmcVectors.SERVER_RANDOM, SwmcCodec.MAX_COUNTER, vban, vban.length, out);
        same(want, checked, "pkt.audio_last", hex(out, 0, n));

        same(want, checked, "pair.code", PairingCode.encode(SwmcVectors.CODE));
        byte[] kCode = SwmcCodec.codeKey(SwmcVectors.CODE);
        same(want, checked, "pair.code_key", hex(kCode));
        same(want, checked, "pkt.pair_request", hex(SwmcCodec.pairRequest(kCode, SwmcVectors.DEVICE_ID, SwmcVectors.PAIR_NONCE,
                SwmcVectors.phonePoint(), SwmcVectors.PHONE_NAME)));
        byte[] kPair = SwmcCodec.pairKey(kCode, z, SwmcVectors.DEVICE_ID, SwmcVectors.PAIR_NONCE,
                SwmcVectors.phonePoint(), SwmcVectors.receiverPoint());
        same(want, checked, "pair.key", hex(kPair));
        same(want, checked, "pkt.pair_accept", hex(SwmcCodec.pairAccept(kCode, kPair, SwmcVectors.DEVICE_ID, SwmcVectors.PAIR_NONCE,
                SwmcVectors.receiverPoint(), SwmcVectors.RECEIVER_ID, SwmcVectors.PC_NAME)));

        assertEquals("vector lines nobody checked", want.keySet(), checked);
    }

    private static void same(Map<String, String> want, Set<String> checked, String name, String got) {
        assertEquals(name, want.get(name), got);
        checked.add(name);
    }

    // The vectors' packets, read the way Starwire and the receiver read them
    @Test
    public void theVectorsPacketsParse() throws Exception {
        Map<String, String> v = SwmcVectors.read();
        byte[] kAuth = SwmcCodec.authKey(SwmcVectors.PAIR_KEY, SwmcVectors.DEVICE_ID);
        byte[] ack = hex(v.get("pkt.hello_ack"));
        assertEquals(SwmcCodec.TYPE_HELLO_ACK, SwmcCodec.typeOf(ack, ack.length));
        assertEquals(SwmcCodec.ACK_LENGTH, ack.length);
        assertTrue(SwmcCodec.macOk(kAuth, ack, ack.length));
        assertTrue(SwmcCodec.isFor(ack, SwmcVectors.DEVICE_ID));
        assertTrue(SwmcCodec.contextIs(ack, SwmcVectors.CLIENT_RANDOM));
        assertArrayEquals(SwmcVectors.SERVER_RANDOM, Arrays.copyOfRange(ack, 24, 32));
        byte[] re = hex(v.get("pkt.rehello"));
        assertEquals(SwmcCodec.TYPE_REHELLO, SwmcCodec.typeOf(re, re.length));
        assertTrue(SwmcCodec.macOk(kAuth, re, re.length));

        Aead aead = new Aead(SwmcCodec.audioKey(SwmcVectors.PAIR_KEY, SwmcVectors.DEVICE_ID, SwmcVectors.CLIENT_RANDOM, SwmcVectors.SERVER_RANDOM));
        byte[] pcm = new byte[2048];
        byte[] a = hex(v.get("pkt.audio7"));
        assertEquals(540, SwmcCodec.openAudio(aead, a, a.length, pcm));
        assertEquals(v.get("vban.frame7"), hex(pcm, 0, 540));
        byte[] last = hex(v.get("pkt.audio_last"));
        assertEquals(540, SwmcCodec.openAudio(aead, last, last.length, pcm));
        assertEquals(SwmcCodec.MAX_COUNTER, SwmcCodec.getU32(last, SwmcCodec.COUNTER_AT));

        byte[] kCode = SwmcCodec.codeKey(PairingCode.decode(v.get("pair.code")));
        byte[] req = hex(v.get("pkt.pair_request"));
        assertEquals(SwmcVectors.PHONE_NAME.length(), SwmcCodec.pairRequestNameLength(req, req.length));
        assertTrue(SwmcCodec.macOk(kCode, req, req.length));
        byte[] acc = hex(v.get("pkt.pair_accept"));
        assertEquals(SwmcVectors.PC_NAME.length(), SwmcCodec.pairAcceptNameLength(acc, acc.length));
        assertTrue(SwmcCodec.macOk(kCode, acc, acc.length));
        assertArrayEquals(SwmcVectors.RECEIVER_ID, Arrays.copyOfRange(acc, SwmcCodec.ACCEPT_RECEIVER_ID_AT, SwmcCodec.ACCEPT_RECEIVER_ID_AT + 8));
        int confirmAt = SwmcCodec.ACCEPT_NAME_AT + SwmcVectors.PC_NAME.length();
        assertArrayEquals(SwmcCodec.confirm(hex(v.get("pair.key"))), Arrays.copyOfRange(acc, confirmAt, confirmAt + 16));
    }

    @Test
    public void everyFlippedBitAndEveryTruncationIsRefused() throws Exception {
        Map<String, String> v = SwmcVectors.read();
        Aead aead = new Aead(hex(v.get("key.audio")));
        byte[] good = hex(v.get("pkt.audio7"));
        byte[] out = new byte[2048];
        assertEquals(540, SwmcCodec.openAudio(aead, good, good.length, out));
        for (int at = 0; at < good.length; at++) {
            for (int bit = 0; bit < 8; bit++) {
                byte[] t = good.clone();
                t[at] ^= (byte) (1 << bit);
                assertEquals("byte " + at + " bit " + bit, -1, SwmcCodec.openAudio(aead, t, t.length, out));
            }
        }
        for (int length = 0; length < good.length; length++) {
            assertEquals("cut to " + length, -1, SwmcCodec.openAudio(aead, good, length, out));
        }
        byte[] longer = Arrays.copyOf(good, good.length + 1);
        assertEquals(-1, SwmcCodec.openAudio(aead, longer, longer.length, out));
        byte[] huge = Arrays.copyOf(good, SwmcCodec.MAX_AUDIO_LENGTH + 1);
        assertEquals(-1, SwmcCodec.openAudio(aead, huge, huge.length, out));
        // A length that claims more than the buffer holds
        assertEquals(-1, SwmcCodec.openAudio(aead, good, good.length + 10, out));

        // The handshake's MACs: any bit, any truncation
        byte[] kAuth = hex(v.get("key.auth"));
        for (String name : new String[] {"pkt.hello", "pkt.hello_ack", "pkt.rehello", "pkt.pair_request", "pkt.pair_accept"}) {
            byte[] p = hex(v.get(name));
            byte[] key = name.startsWith("pkt.pair") ? hex(v.get("pair.code_key")) : kAuth;
            assertTrue(name, SwmcCodec.macOk(key, p, p.length));
            for (int at = 0; at < p.length; at++) {
                byte[] t = p.clone();
                t[at] ^= 0x01;
                assertFalse(name + " byte " + at, SwmcCodec.macOk(key, t, t.length));
            }
            for (int length = 0; length < p.length; length++) {
                assertFalse(name + " cut to " + length, SwmcCodec.macOk(key, p, length));
            }
        }
    }

    @Test
    public void theWrongKeyOrDeviceIsRefused() throws Exception {
        Map<String, String> v = SwmcVectors.read();
        byte[] good = hex(v.get("pkt.audio7"));
        byte[] out = new byte[2048];
        byte[] otherPairing = SwmcVectors.PAIR_KEY.clone();
        otherPairing[0] ^= (byte) 0xFF;
        Aead wrongPairing = new Aead(SwmcCodec.audioKey(otherPairing, SwmcVectors.DEVICE_ID, SwmcVectors.CLIENT_RANDOM, SwmcVectors.SERVER_RANDOM));
        assertEquals(-1, SwmcCodec.openAudio(wrongPairing, good, good.length, out));
        byte[] otherDevice = SwmcVectors.DEVICE_ID.clone();
        otherDevice[7] ^= 1;
        Aead wrongDevice = new Aead(SwmcCodec.audioKey(SwmcVectors.PAIR_KEY, otherDevice, SwmcVectors.CLIENT_RANDOM, SwmcVectors.SERVER_RANDOM));
        assertEquals(-1, SwmcCodec.openAudio(wrongDevice, good, good.length, out));
        // An older session's key (another client random)
        Aead oldSession = new Aead(SwmcCodec.audioKey(SwmcVectors.PAIR_KEY, SwmcVectors.DEVICE_ID, hex("0000000000000000"), SwmcVectors.SERVER_RANDOM));
        assertEquals(-1, SwmcCodec.openAudio(oldSession, good, good.length, out));

        byte[] ack = hex(v.get("pkt.hello_ack"));
        assertFalse(SwmcCodec.macOk(SwmcCodec.authKey(SwmcVectors.PAIR_KEY, otherDevice), ack, ack.length));
        assertFalse(SwmcCodec.isFor(ack, otherDevice));
        byte[] typo = SwmcVectors.CODE.clone();
        typo[9] ^= 1;
        byte[] req = hex(v.get("pkt.pair_request"));
        assertFalse(SwmcCodec.macOk(SwmcCodec.codeKey(typo), req, req.length));
    }

    @Test
    public void typesVersionsAndPlainVbanAreTold() throws Exception {
        Map<String, String> v = SwmcVectors.read();
        byte[] kAuth = hex(v.get("key.auth"));
        byte[] hello = hex(v.get("pkt.hello"));
        byte[] asRehello = hello.clone();
        asRehello[5] = SwmcCodec.TYPE_REHELLO;
        assertEquals(SwmcCodec.TYPE_REHELLO, SwmcCodec.typeOf(asRehello, asRehello.length));
        assertFalse("a HELLO relabelled as a REHELLO", SwmcCodec.macOk(kAuth, asRehello, asRehello.length));
        byte[] audio = hex(v.get("pkt.audio7"));
        audio[5] = SwmcCodec.TYPE_HELLO_ACK;
        assertEquals(-1, SwmcCodec.openAudio(new Aead(hex(v.get("key.audio"))), audio, audio.length, new byte[2048]));

        byte[] v2 = hello.clone();
        v2[4] = 2;
        assertEquals("unknown version", -1, SwmcCodec.typeOf(v2, v2.length));
        byte[] reserved = hello.clone();
        reserved[7] = 1;
        assertEquals(-1, SwmcCodec.typeOf(reserved, reserved.length));
        byte[] plain = hex(v.get("vban.frame7"));
        assertEquals("plain VBAN isn't SWMC", -1, SwmcCodec.typeOf(plain, plain.length));
        assertEquals(-1, SwmcCodec.typeOf(hello, 23));
        assertEquals(-1, SwmcCodec.typeOf(null, 0));

        byte[] reject = SwmcCodec.pairReject(hex(v.get("pkt.pair_request")), SwmcCodec.REJECT_WRONG_CODE);
        assertEquals(SwmcCodec.REJECT_LENGTH, reject.length);
        assertEquals(SwmcCodec.REJECT_WRONG_CODE, SwmcCodec.pairRejectReason(reject, reject.length));
        assertTrue(SwmcCodec.isFor(reject, SwmcVectors.DEVICE_ID));
        assertTrue(SwmcCodec.contextIs(reject, SwmcVectors.PAIR_NONCE));
        assertEquals(-1, SwmcCodec.pairRejectReason(reject, 24));
        assertEquals(-1, SwmcCodec.pairRejectReason(Arrays.copyOf(reject, 26), 26));
    }

    // The receiver's rule (docs/dev/mic-protocol.md): each counter once and only going up, in
    // that session. Played in order, a gap is fine; a replayed or late one is not.
    @Test
    public void replayedAndLatePacketsDontPlay() throws Exception {
        SwmcTestReceiver receiver = new SwmcTestReceiver(SwmcVectors.PAIR_KEY, SwmcVectors.DEVICE_ID, 1);
        byte[] ack = receiver.handle(SwmcCodec.hello(receiver.authKey, SwmcVectors.DEVICE_ID, SwmcVectors.CLIENT_RANDOM), 40);
        byte[] session = Arrays.copyOfRange(ack, 24, 32);
        Aead aead = new Aead(SwmcCodec.audioKey(SwmcVectors.PAIR_KEY, SwmcVectors.DEVICE_ID, SwmcVectors.CLIENT_RANDOM, session));
        byte[][] packets = new byte[6][];
        for (int i = 0; i < packets.length; i++) {
            byte[] out = new byte[SwmcCodec.MAX_AUDIO_LENGTH];
            int n = SwmcCodec.audio(aead, SwmcVectors.DEVICE_ID, session, i, SwmcVectors.vbanFrame(i, i * 256), 540, out);
            packets[i] = Arrays.copyOf(out, n);
        }
        receiver.handle(packets[0], 584);
        receiver.handle(packets[1], 584);
        receiver.handle(packets[1], 584);   // replayed
        receiver.handle(packets[3], 584);   // 2 is late
        receiver.handle(packets[2], 584);
        receiver.handle(packets[0], 584);   // replayed from long ago
        receiver.handle(packets[5], 584);
        assertEquals(Arrays.asList(0L, 1L, 3L, 5L), receiver.playedCounters);
        assertEquals(3, receiver.replayedOrLate);

        // A packet from another session never plays in this one, even with a fresh counter
        byte[] moved = packets[4].clone();
        byte[] otherAck = receiver.handle(SwmcCodec.hello(receiver.authKey, SwmcVectors.DEVICE_ID, hex("0101010101010101")), 40);
        System.arraycopy(otherAck, 24, moved, 16, 8);
        receiver.handle(moved, moved.length);
        assertEquals(4, receiver.played.size());
        assertEquals(1, receiver.forged);
    }

    // The counter never wraps on one key: 0xFFFFFFFF is the last, and sealing past it is refused
    @Test
    public void theCounterStopsAt0xFFFFFFFF() throws Exception {
        Aead aead = new Aead(SwmcVectors.PAIR_KEY);
        byte[] vban = SwmcVectors.vbanFrame(0, 0);
        byte[] out = new byte[SwmcCodec.MAX_AUDIO_LENGTH];
        assertEquals(584, SwmcCodec.audio(aead, SwmcVectors.DEVICE_ID, SwmcVectors.SERVER_RANDOM, SwmcCodec.MAX_COUNTER, vban, 540, out));
        for (long bad : new long[] {SwmcCodec.MAX_COUNTER + 1, -1}) {
            try {
                SwmcCodec.audio(aead, SwmcVectors.DEVICE_ID, SwmcVectors.SERVER_RANDOM, bad, vban, 540, out);
                fail("sealed with counter " + bad);
            }
            catch (IllegalArgumentException expected) {
            }
        }
    }

    @Test
    public void pairingCodesAreReadLikePeopleTypeThem() {
        byte[] code = SwmcVectors.CODE;
        assertEquals("2491-650N-2RBH-G690", PairingCode.encode(code));
        for (String typed : new String[] {"2491-650N-2RBH-G690", "2491650N2RBHG690", "2491 650n 2rbh g690",
                "  2491-650N-2RBH-G69O ", "249l-65ON-2RBH-G690", "249I-650N-2RBH-G690", "2491--650N 2RBH-G690"}) {
            assertArrayEquals(typed, code, PairingCode.decode(typed));
        }
        for (String bad : new String[] {"", "2491-650N-2RBH-G69", "2491-650N-2RBH-G6900", "U491-650N-2RBH-G690",
                "2491-650N-2RBH-G69!", "2491_650N_2RBH_G690", "2491-650N-2RBH-G690-0"}) {
            assertNull(bad, PairingCode.decode(bad));
        }
        assertNull(PairingCode.decode(null));

        Random random = new Random(5);
        for (int i = 0; i < 1000; i++) {
            byte[] b = new byte[10];
            random.nextBytes(b);
            String text = PairingCode.encode(b);
            assertTrue(text, text.matches("[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){3}"));
            assertArrayEquals(text, b, PairingCode.decode(text));
        }

        // What the field shows while typing
        assertEquals("", PairingCode.format(""));
        assertEquals("2491", PairingCode.format("2491"));
        assertEquals("2491-6", PairingCode.format("24916"));
        assertEquals("2491-650N-2RBH-G690", PairingCode.format("2491650n2rbhg690"));
        assertEquals("2491-650N-2RBH-G690", PairingCode.format(" 2491-650N-2RBH-G690 "));
        assertEquals("2491-650N", PairingCode.format("2491-650N-"));
        assertEquals("U491", PairingCode.format("u491"));
        assertEquals("2491-650N-2RBH-G690-1", PairingCode.format("2491650N2RBHG6901"));
    }

    @Test
    public void onlyPointsOnP256AreTaken() throws Exception {
        byte[] good = SwmcVectors.phonePoint();
        assertNotNull(SwmcCodec.decodePoint(good, 0));
        byte[] offCurve = good.clone();
        offCurve[64] ^= 1;
        assertNull("not on the curve", SwmcCodec.decodePoint(offCurve, 0));
        byte[] compressed = good.clone();
        compressed[0] = 2;
        assertNull("not uncompressed", SwmcCodec.decodePoint(compressed, 0));
        byte[] zero = new byte[65];
        zero[0] = 4;
        assertNull("the zero point", SwmcCodec.decodePoint(zero, 0));
        // x at or above the field prime p = 2^256 - 2^224 + 2^192 + 2^96 - 1
        byte[] big = good.clone();
        byte[] p = hex("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff");
        System.arraycopy(p, 0, big, 1, 32);
        assertNull("x = p", SwmcCodec.decodePoint(big, 0));
        Arrays.fill(big, 1, 33, (byte) 0xFF);
        assertNull("x > p", SwmcCodec.decodePoint(big, 0));
        // y = p is y = 0 once reduced: refused, not reduced
        byte[] high = good.clone();
        System.arraycopy(p, 0, high, 33, 32);
        assertNull("y = p", SwmcCodec.decodePoint(high, 0));
        assertNull("too short", SwmcCodec.decodePoint(Arrays.copyOf(good, 64), 0));
        assertNull(SwmcCodec.decodePoint(good, 1));
        assertNull(SwmcCodec.decodePoint(good, -1));
    }

    @Test
    public void namesAreCutAndCleaned() {
        assertEquals("Test phone", new String(SwmcCodec.nameBytes("Test phone"), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(0, SwmcCodec.nameBytes(null).length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("🎮");   // a 4-byte character
        }
        byte[] cut = SwmcCodec.nameBytes(sb.toString());
        assertEquals(64, cut.length);
        assertEquals(16, new String(cut, java.nio.charset.StandardCharsets.UTF_8).codePointCount(0, 32));
        byte[] odd = SwmcCodec.nameBytes("x" + sb);
        assertEquals("cut before a character, not inside it", 61, odd.length);

        assertEquals("Desk PC", SwmcCodec.cleanName("Desk\r\nPC", "?"));
        assertEquals("Desk PC", SwmcCodec.cleanName("  Desk ‮  PC\u0007 ", "?"));
        assertEquals("fallback", SwmcCodec.cleanName("\u0000​\t", "fallback"));
        assertEquals("Café 🎮", SwmcCodec.cleanName("Café 🎮", "?"));
    }

    // Random bytes, and random bytes behind a real header, through everything that parses what
    // arrives from the network: nothing throws, nothing is taken as authentic
    @Test
    public void randomJunkIsNeverTaken() throws Exception {
        Map<String, String> v = SwmcVectors.read();
        Aead aead = new Aead(hex(v.get("key.audio")));
        byte[] kAuth = hex(v.get("key.auth"));
        byte[] kCode = hex(v.get("pair.code_key"));
        byte[] good = hex(v.get("pkt.audio7"));
        Random random = new Random(20261023);
        byte[] out = new byte[2048];
        int shaped = 0;
        for (int i = 0; i < 20000; i++) {
            int length = random.nextInt(1200);
            byte[] p = new byte[Math.max(length, 1)];
            random.nextBytes(p);
            switch (i % 4) {
                case 1:
                    // A valid header of a random type
                    SwmcCodec.writeHeader(p.length >= 24 ? p : new byte[24], 1 + random.nextInt(8), SwmcVectors.DEVICE_ID, SwmcVectors.SERVER_RANDOM);
                    break;
                case 2:
                    // A real packet with a few random bytes changed
                    p = good.clone();
                    length = p.length;
                    for (int k = 1 + random.nextInt(4); k > 0; k--) {
                        p[random.nextInt(p.length)] ^= (byte) (1 + random.nextInt(255));
                    }
                    if (Arrays.equals(p, good)) {
                        // Two changes to the same byte cancelled out
                        p[0] ^= 1;
                    }
                    break;
                case 3:
                    // A real packet cut or padded
                    int size = random.nextInt(1100);
                    p = Arrays.copyOf(good, size == good.length ? size + 1 : size);
                    length = p.length;
                    break;
                default:
                    break;
            }
            length = Math.min(length, p.length);
            if (SwmcCodec.typeOf(p, length) >= 0) {
                shaped++;
            }
            assertEquals(-1, SwmcCodec.openAudio(aead, p, length, out));
            assertFalse(SwmcCodec.macOk(kAuth, p, length));
            assertFalse(SwmcCodec.macOk(kCode, p, length));
            SwmcCodec.pairRejectReason(p, length);
            SwmcCodec.pairAcceptNameLength(p, length);
            SwmcCodec.pairRequestNameLength(p, length);
            if (length >= 65) {
                // Random X and Y are on the curve with chance about 2^-256
                assertNull(SwmcCodec.decodePoint(p, length - 65));
            }
        }
        assertTrue("the fuzz reached the parsers behind the header: " + shaped, shaped > 5000);
    }
}
