package com.limelight.binding.audio.securemic;

import static com.limelight.binding.audio.securemic.SwmcVectors.hex;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Pairing with the PC's receiver, Starwire's side, on a pretend network and clock: the fixed
 * vectors give the vectors' pairing key, the request is repeated every 500 ms for 6 s, forged
 * answers are ignored, a REJECT only wins if no real ACCEPT follows within 500 ms, and each
 * failure says what went wrong.
 */
public class MicPairingClientTest {
    private static final InetSocketAddress LAN = InetSocketAddress.createUnresolved("192.168.1.20", 6980);
    private static final InetSocketAddress VPN = InetSocketAddress.createUnresolved("100.101.102.103", 6980);
    private static final String CODE = "2491-650N-2RBH-G690";

    private final FakeNetwork net = new FakeNetwork();

    // With the vectors' device id, nonce and one-time key, so the request is the vectors' one
    private MicPairingClient client() {
        return new MicPairingClient(new MicPairingClient.Transport() {
            @Override
            public void send(byte[] packet, int length, InetSocketAddress to) {
                net.send(packet, length, to);
            }

            @Override
            public int receive(byte[] buffer, int timeoutMs) {
                return net.receive(buffer, timeoutMs);
            }
        }, () -> net.now, new FakeNetwork.FixedRandom(SwmcVectors.PAIR_NONCE), SwmcVectors::phoneKeyPair);
    }

    private MicPairingClient.Outcome pair(List<InetSocketAddress> to) {
        return client().pair(to, CODE, SwmcVectors.PHONE_NAME, SwmcVectors.DEVICE_ID, "uuid-1", "Desk PC");
    }

    // What the receiver answers to the vectors' request: its point from RFC 5903, made with the code
    private static byte[] acceptFor(byte[] request, byte[] confirmKey) throws Exception {
        byte[] codeKey = SwmcCodec.codeKey(SwmcVectors.CODE);
        byte[] deviceId = Arrays.copyOfRange(request, 8, 16);
        byte[] nonce = Arrays.copyOfRange(request, 16, 24);
        byte[] phonePoint = Arrays.copyOfRange(request, 24, 89);
        byte[] z = SwmcCodec.ecdhHash(SwmcVectors.privateKey(SwmcVectors.ECDH_R), SwmcCodec.decodePoint(phonePoint, 0));
        byte[] pairKey = SwmcCodec.pairKey(codeKey, z, deviceId, nonce, phonePoint, SwmcVectors.receiverPoint());
        return SwmcCodec.pairAccept(codeKey, confirmKey != null ? confirmKey : pairKey, deviceId, nonce,
                SwmcVectors.receiverPoint(), SwmcVectors.RECEIVER_ID, SwmcVectors.PC_NAME);
    }

    private static List<Long> times(List<FakeNetwork.Sent> sent) {
        List<Long> out = new ArrayList<>();
        for (FakeNetwork.Sent s : sent) {
            out.add(s.at);
        }
        return out;
    }

    @Test
    public void theVectorsPairWithTheVectorsKey() throws Exception {
        Map<String, String> v = SwmcVectors.read();
        net.deliver(30, hex(v.get("pkt.pair_accept")));
        MicPairingClient.Outcome outcome = pair(Collections.singletonList(LAN));

        assertEquals(MicPairingClient.Result.PAIRED, outcome.result);
        assertEquals("the request is the vectors' PAIR_REQUEST", v.get("pkt.pair_request"), hex(net.sent.get(0).bytes));
        assertEquals(1, net.sent.size());
        MicPairing pairing = outcome.pairing;
        assertEquals(v.get("pair.key"), hex(pairing.key));
        assertArrayEquals(SwmcVectors.DEVICE_ID, pairing.deviceId);
        assertArrayEquals(SwmcVectors.RECEIVER_ID, pairing.receiverId);
        assertEquals("TEST-PC", pairing.receiverName);
        assertEquals("uuid-1", pairing.computerUuid);
        assertEquals("Desk PC", pairing.displayName());
    }

    @Test
    public void noAnswerAfterExactlySixSecondsOfAskingEveryHalfSecond() {
        MicPairingClient.Outcome outcome = pair(Arrays.asList(LAN, VPN));
        assertEquals(MicPairingClient.Result.NO_ANSWER, outcome.result);
        assertNull(outcome.pairing);
        assertEquals(6000, net.now);
        List<Long> want = new ArrayList<>();
        for (long t = 0; t < 6000; t += 500) {
            want.add(t);
            want.add(t);
        }
        assertEquals(want, times(net.sent));
        // The same request each time, to each address in turn
        for (int i = 0; i < net.sent.size(); i++) {
            assertArrayEquals(net.sent.get(0).bytes, net.sent.get(i).bytes);
            assertEquals(i % 2 == 0 ? LAN : VPN, net.sent.get(i).to);
        }
    }

    @Test
    public void forgedAcceptsAreIgnored() throws Exception {
        Map<String, String> v = SwmcVectors.read();
        byte[] good = hex(v.get("pkt.pair_accept"));
        byte[] badMac = good.clone();
        badMac[badMac.length - 1] ^= 1;
        byte[] otherNonce = good.clone();
        otherNonce[16] ^= 1;
        byte[] shortened = Arrays.copyOf(good, good.length - 1);
        net.deliver(10, badMac);
        net.deliver(20, otherNonce);
        net.deliver(30, shortened);
        net.deliver(40, hex(v.get("pkt.hello_ack")));
        net.deliver(1700, good);
        MicPairingClient.Outcome outcome = pair(Collections.singletonList(LAN));
        assertEquals(MicPairingClient.Result.PAIRED, outcome.result);
        assertEquals(1700, net.now);
        assertEquals(v.get("pair.key"), hex(outcome.pairing.key));

        // Forged ones alone: no pairing
        FakeNetwork alone = new FakeNetwork();
        assertEquals(MicPairingClient.Result.NO_ANSWER, forgedOnly(alone, badMac).result);
    }

    private MicPairingClient.Outcome forgedOnly(FakeNetwork other, byte[] forged) {
        other.deliver(10, forged);
        return new MicPairingClient(new MicPairingClient.Transport() {
            @Override
            public void send(byte[] packet, int length, InetSocketAddress to) {
                other.send(packet, length, to);
            }

            @Override
            public int receive(byte[] buffer, int timeoutMs) {
                return other.receive(buffer, timeoutMs);
            }
        }, () -> other.now, new FakeNetwork.FixedRandom(SwmcVectors.PAIR_NONCE), SwmcVectors::phoneKeyPair)
                .pair(Collections.singletonList(LAN), CODE, SwmcVectors.PHONE_NAME, SwmcVectors.DEVICE_ID, "uuid-1", "Desk PC");
    }

    // Anyone can send a REJECT; the real ACCEPT arriving just after it still pairs
    @Test
    public void aSpoofedRejectLosesToARealAcceptWithinHalfASecond() throws Exception {
        Map<String, String> v = SwmcVectors.read();
        byte[] request = hex(v.get("pkt.pair_request"));
        net.deliver(5, SwmcCodec.pairReject(request, SwmcCodec.REJECT_WRONG_CODE));
        net.deliver(504, hex(v.get("pkt.pair_accept")));
        MicPairingClient.Outcome outcome = pair(Collections.singletonList(LAN));
        assertEquals(MicPairingClient.Result.PAIRED, outcome.result);
        assertEquals(504, net.now);
    }

    @Test
    public void aRejectIsBelievedAfterHalfASecondAndSaysWhy() throws Exception {
        byte[] request = hex(SwmcVectors.read().get("pkt.pair_request"));
        int[] reasons = {SwmcCodec.REJECT_NOT_PAIRING, SwmcCodec.REJECT_WRONG_CODE, SwmcCodec.REJECT_EXPIRED, 9};
        MicPairingClient.Result[] results = {MicPairingClient.Result.NOT_PAIRING, MicPairingClient.Result.WRONG_CODE,
                MicPairingClient.Result.EXPIRED, MicPairingClient.Result.FAILED};
        for (int i = 0; i < reasons.length; i++) {
            FakeNetwork other = new FakeNetwork();
            other.deliver(20, SwmcCodec.pairReject(request, reasons[i]));
            // A later, different REJECT doesn't change the answer
            other.deliver(100, SwmcCodec.pairReject(request, SwmcCodec.REJECT_NOT_PAIRING));
            MicPairingClient.Outcome outcome = forgedOnly(other, new byte[0]);
            assertEquals("reason " + reasons[i], results[i], outcome.result);
            assertEquals(520, other.now);
        }

        // A REJECT for someone else's request is nobody's business
        byte[] otherRequest = request.clone();
        otherRequest[16] ^= 1;
        FakeNetwork other = new FakeNetwork();
        other.deliver(20, SwmcCodec.pairReject(otherRequest, SwmcCodec.REJECT_WRONG_CODE));
        assertEquals(MicPairingClient.Result.NO_ANSWER, forgedOnly(other, new byte[0]).result);
    }

    // The right code but not the same pairing key (someone in the middle who saw the code, or a
    // broken receiver): an error, never a pairing
    @Test
    public void aConfirmationThatDoesntMatchIsAnError() throws Exception {
        byte[] request = hex(SwmcVectors.read().get("pkt.pair_request"));
        net.deliver(10, acceptFor(request, new byte[32]));
        MicPairingClient.Outcome outcome = pair(Collections.singletonList(LAN));
        assertEquals(MicPairingClient.Result.FAILED, outcome.result);
        assertNull(outcome.pairing);

        // And an ACCEPT whose own key isn't on P-256, with the right code
        byte[] accept = acceptFor(request, null);
        accept[24 + 64] ^= 1;
        byte[] codeKey = SwmcCodec.codeKey(SwmcVectors.CODE);
        System.arraycopy(SwmcCodec.mac16(codeKey, accept, 0, accept.length - 16), 0, accept, accept.length - 16, 16);
        FakeNetwork other = new FakeNetwork();
        assertEquals(MicPairingClient.Result.FAILED, forgedOnly(other, accept).result);
    }

    @Test
    public void aRealReceiversAnswerPairs() throws Exception {
        net.responder = (sent, now) -> now >= 1000 ? acceptFor(sent, null) : null;
        MicPairingClient.Outcome outcome = pair(Collections.singletonList(LAN));
        assertEquals(MicPairingClient.Result.PAIRED, outcome.result);
        assertEquals(1001, net.now);
        assertEquals(3, net.sent.size());
        assertEquals(SwmcVectors.read().get("pair.key"), hex(outcome.pairing.key));
    }

    @Test
    public void badCodesAndNoAddressesSendNothing() {
        for (String bad : new String[] {"", "1234", "2491-650N-2RBH-G69U", "2491-650N-2RBH-G690-1"}) {
            MicPairingClient.Outcome outcome = client().pair(Collections.singletonList(LAN), bad, "x", null, "u", "n");
            assertEquals(bad, MicPairingClient.Result.BAD_CODE, outcome.result);
        }
        assertEquals(MicPairingClient.Result.NO_ADDRESS,
                client().pair(Collections.emptyList(), CODE, "x", null, "u", "n").result);
        assertTrue(net.sent.isEmpty());
        assertEquals(0, net.now);
    }

    // A PC paired before keeps the device id, so the receiver replaces its old key; a new PC gets a new id
    @Test
    public void theDeviceIdIsKeptForAPcAndNewOtherwise() throws Exception {
        byte[] madeUp = hex("0102030405060708");
        MicPairingClient fresh = new MicPairingClient(new MicPairingClient.Transport() {
            @Override
            public void send(byte[] packet, int length, InetSocketAddress to) {
                net.send(packet, length, to);
            }

            @Override
            public int receive(byte[] buffer, int timeoutMs) {
                return net.receive(buffer, timeoutMs);
            }
        }, () -> net.now, new FakeNetwork.FixedRandom(madeUp, SwmcVectors.PAIR_NONCE), SwmcVectors::phoneKeyPair);
        net.responder = (sent, now) -> acceptFor(sent, null);
        MicPairingClient.Outcome outcome = fresh.pair(Collections.singletonList(LAN), CODE, "Phone", null, "u", "n");
        assertEquals(MicPairingClient.Result.PAIRED, outcome.result);
        assertArrayEquals(madeUp, outcome.pairing.deviceId);
        assertArrayEquals(madeUp, Arrays.copyOfRange(net.sent.get(0).bytes, 8, 16));
        assertNotNull(SwmcCodec.decodePoint(net.sent.get(0).bytes, 24));
        assertFalse(Arrays.equals(SwmcVectors.DEVICE_ID, outcome.pairing.deviceId));
    }
}
