package com.limelight.binding.audio.securemic;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The encrypted microphone sender on a pretend network and clock: when HELLOs go out, that no
 * audio goes before the receiver answers, which answers count, what the user is told, that
 * counters only go up and never wrap, that the receiver gets back exactly the VBAN stream of what
 * was recorded, that it recovers when the receiver restarts, and that answers recorded earlier
 * never make it use a key and nonce twice.
 */
public class SecureVbanSenderTest {
    private final FakeNetwork net = new FakeNetwork();
    private final List<long[]> statuses = new ArrayList<>();
    // The receiver's HELLO_ACKs, in order, as someone on the network could record them
    private final List<byte[]> answers = new ArrayList<>();
    private SwmcTestReceiver receiver;
    private SecureVbanSender sender;

    private SecureVbanSender sender(long seed) {
        sender = new SecureVbanSender(() -> new SecureVbanSender.Transport() {
            @Override
            public void send(byte[] packet, int length) {
                net.send(packet, length, null);
            }

            @Override
            public int receive(byte[] buffer, int timeoutMs) {
                return net.receive(buffer, timeoutMs);
            }

            @Override
            public void close() {
            }
        }, () -> net.now, new Random(seed), SwmcVectors.DEVICE_ID, SwmcVectors.PAIR_KEY,
                status -> statuses.add(new long[] {net.now, status}), false);
        return sender;
    }

    // A receiver that answers what the sender sends
    private void connectReceiver() throws Exception {
        receiver = new SwmcTestReceiver(SwmcVectors.PAIR_KEY, SwmcVectors.DEVICE_ID, 99);
        net.responder = (sent, now) -> {
            byte[] answer = receiver.handle(sent, sent.length);
            if (answer != null && SwmcCodec.typeOf(answer, answer.length) == SwmcCodec.TYPE_HELLO_ACK) {
                answers.add(answer);
            }
            return answer;
        };
    }

    // Someone on the network sends the sender a packet they recorded
    private void replay(byte[] recorded) {
        net.deliver(net.now + 1, recorded);
        runUntil(net.now + 5);
    }

    // The key each audio packet was sealed with, in order, named clientRandom/sessionId: found by
    // opening the packet with the key of each HELLO the sender sent
    private List<String> audioKeys() throws Exception {
        Map<String, byte[]> clientRandoms = new LinkedHashMap<>();
        for (FakeNetwork.Sent s : net.ofType(SwmcCodec.TYPE_HELLO)) {
            clientRandoms.put(SwmcVectors.hex(s.bytes, 16, 8), Arrays.copyOfRange(s.bytes, 16, 24));
        }
        Map<String, Aead> aeads = new HashMap<>();
        byte[] vban = new byte[2048];
        List<String> keys = new ArrayList<>();
        for (FakeNetwork.Sent s : net.ofType(SwmcCodec.TYPE_AUDIO)) {
            byte[] sessionId = Arrays.copyOfRange(s.bytes, 16, 24);
            String key = null;
            for (Map.Entry<String, byte[]> c : clientRandoms.entrySet()) {
                String name = c.getKey() + "/" + SwmcVectors.hex(sessionId);
                Aead aead = aeads.get(name);
                if (aead == null) {
                    aead = new Aead(SwmcCodec.audioKey(SwmcVectors.PAIR_KEY, SwmcVectors.DEVICE_ID, c.getValue(), sessionId));
                    aeads.put(name, aead);
                }
                if (SwmcCodec.openAudio(aead, s.bytes, s.bytes.length, vban) >= 0) {
                    key = name;
                }
            }
            assertNotNull("sealed with the key of one of the sender's HELLOs", key);
            keys.add(key);
        }
        return keys;
    }

    // Across everything the sender sent, no key and nonce were used twice, and once audio moved
    // on to a new key it never went back to an old one. Returns the keys in the order used.
    private List<String> assertNoKeyAndNonceUsedTwice() throws Exception {
        List<FakeNetwork.Sent> audio = net.ofType(SwmcCodec.TYPE_AUDIO);
        List<String> keys = audioKeys();
        Set<String> used = new HashSet<>();
        List<String> order = new ArrayList<>();
        for (int i = 0; i < audio.size(); i++) {
            String key = keys.get(i);
            String nonce = SwmcVectors.hex(audio.get(i).bytes, 16, 12);
            assertTrue("key " + key + " and nonce " + nonce + " used twice, at " + audio.get(i).at, used.add(key + " " + nonce));
            if (order.isEmpty() || !order.get(order.size() - 1).equals(key)) {
                assertFalse("went back to key " + key + " at " + audio.get(i).at, order.contains(key));
                order.add(key);
            }
        }
        return order;
    }

    private static List<Long> counters(long... values) {
        List<Long> out = new ArrayList<>();
        for (long v : values) {
            out.add(v);
        }
        return out;
    }

    // What the sender's own thread does, with the pretend clock
    private void runUntil(long until) {
        byte[] buffer = new byte[2048];
        while (net.now < until) {
            long wait = sender.poll();
            int n = net.receive(buffer, (int) Math.max(1, Math.min(Math.min(wait, 50), until - net.now)));
            if (n > 0) {
                sender.onDatagram(buffer, n);
            }
        }
    }

    private static short[] ramp(int samples, int offset) {
        short[] pcm = new short[samples];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) ((i + offset) * 37 - 16000);
        }
        return pcm;
    }

    private void writePackets(int packets) {
        sender.write(ramp(256 * packets, 0), 256 * packets);
    }

    private List<Long> helloTimes() {
        List<Long> out = new ArrayList<>();
        for (FakeNetwork.Sent s : net.ofType(SwmcCodec.TYPE_HELLO)) {
            out.add(s.at);
        }
        return out;
    }

    private List<long[]> statusList() {
        return statuses;
    }

    private void assertStatuses(long[]... want) {
        assertEquals(Arrays.deepToString(want), Arrays.deepToString(statusList().toArray()));
    }

    @Test
    public void hellosGoOutOnScheduleAndNoAnswerIsToldAtThreeSeconds() throws Exception {
        sender(1);
        assertEquals(0, sender.open());
        runUntil(10_001);
        assertEquals(Arrays.asList(0L, 250L, 500L, 750L, 1000L, 1250L, 1500L, 1750L, 2000L, 4000L, 6000L, 8000L, 10000L),
                helloTimes());
        byte[] authKey = SwmcCodec.authKey(SwmcVectors.PAIR_KEY, SwmcVectors.DEVICE_ID);
        byte[] first = net.sent.get(0).bytes;
        for (FakeNetwork.Sent s : net.sent) {
            assertArrayEquals("the same HELLO each time", first, s.bytes);
            assertEquals(SwmcCodec.HELLO_LENGTH, s.bytes.length);
            assertTrue(SwmcCodec.macOk(authKey, s.bytes, s.bytes.length));
            assertTrue(SwmcCodec.isFor(s.bytes, SwmcVectors.DEVICE_ID));
        }
        assertStatuses(new long[] {0, SecureVbanSender.STATUS_CONNECTING}, new long[] {3000, SecureVbanSender.STATUS_NO_ANSWER});
    }

    @Test
    public void noAudioGoesBeforeTheReceiverAnswers() throws Exception {
        sender(2);
        assertEquals(0, sender.open());
        writePackets(10);
        runUntil(500);
        assertTrue(net.ofType(SwmcCodec.TYPE_AUDIO).isEmpty());
        assertEquals(10, sender.droppedPackets());

        connectReceiver();
        runUntil(800);
        assertEquals(1, receiver.hellos);
        writePackets(3);
        assertEquals(3, net.ofType(SwmcCodec.TYPE_AUDIO).size());
        assertEquals(3, receiver.played.size());
        assertEquals(10, sender.droppedPackets());
        assertEquals(SecureVbanSender.STATUS_SENDING, statuses.get(statuses.size() - 1)[1]);
    }

    // Only an answer to this HELLO, for this device, with the right MAC, starts the session
    @Test
    public void answersThatDontCheckOutAreIgnored() throws Exception {
        sender(3);
        assertEquals(0, sender.open());
        runUntil(1);
        byte[] hello = net.sent.get(0).bytes;
        byte[] clientRandom = Arrays.copyOfRange(hello, 16, 24);
        byte[] authKey = SwmcCodec.authKey(SwmcVectors.PAIR_KEY, SwmcVectors.DEVICE_ID);
        byte[] session = SwmcVectors.SERVER_RANDOM;

        byte[] otherRandom = clientRandom.clone();
        otherRandom[0] ^= 1;
        net.deliver(10, SwmcCodec.helloAck(authKey, SwmcVectors.DEVICE_ID, otherRandom, session, 0));
        byte[] otherDevice = SwmcVectors.DEVICE_ID.clone();
        otherDevice[0] ^= 1;
        net.deliver(20, SwmcCodec.helloAck(SwmcCodec.authKey(SwmcVectors.PAIR_KEY, otherDevice), otherDevice, clientRandom, session, 0));
        byte[] badMac = SwmcCodec.helloAck(authKey, SwmcVectors.DEVICE_ID, clientRandom, session, 0);
        badMac[51] ^= 1;
        net.deliver(30, badMac);
        byte[] wrongKey = SwmcCodec.helloAck(SwmcCodec.authKey(new byte[32], SwmcVectors.DEVICE_ID), SwmcVectors.DEVICE_ID, clientRandom, session, 0);
        net.deliver(40, wrongKey);
        net.deliver(50, Arrays.copyOf(SwmcCodec.helloAck(authKey, SwmcVectors.DEVICE_ID, clientRandom, session, 0), 53));
        // A HELLO sent back at it, and plain VBAN
        net.deliver(60, hello);
        net.deliver(70, SwmcVectors.vbanFrame(0, 0));
        runUntil(3500);
        writePackets(2);
        assertTrue(net.ofType(SwmcCodec.TYPE_AUDIO).isEmpty());
        assertStatuses(new long[] {0, SecureVbanSender.STATUS_CONNECTING}, new long[] {3000, SecureVbanSender.STATUS_NO_ANSWER});

        // The real one, late, still works
        net.deliver(3600, SwmcCodec.helloAck(authKey, SwmcVectors.DEVICE_ID, clientRandom, session, 0));
        runUntil(3700);
        writePackets(1);
        assertEquals(1, net.ofType(SwmcCodec.TYPE_AUDIO).size());
        assertEquals(SecureVbanSender.STATUS_SENDING, statuses.get(statuses.size() - 1)[1]);
    }

    @Test
    public void busyAndNoOutputAreToldAndAskedAgainUntilReady() throws Exception {
        sender(4);
        connectReceiver();
        receiver.status = SwmcCodec.STATUS_BUSY;
        assertEquals(0, sender.open());
        runUntil(100);
        assertStatuses(new long[] {0, SecureVbanSender.STATUS_CONNECTING}, new long[] {1, SecureVbanSender.STATUS_BUSY});
        // Audio goes anyway, so it plays as soon as the other device stops
        writePackets(2);
        assertEquals(2, receiver.played.size());

        receiver.status = SwmcCodec.STATUS_NO_OUTPUT;
        runUntil(2100);
        assertEquals(Arrays.asList(0L, 2000L), helloTimes());
        receiver.status = SwmcCodec.STATUS_READY;
        runUntil(4100);
        assertEquals(Arrays.asList(0L, 2000L, 4000L), helloTimes());
        runUntil(9000);
        assertEquals("no more HELLOs once it's ready", 3, helloTimes().size());
        assertStatuses(new long[] {0, SecureVbanSender.STATUS_CONNECTING}, new long[] {1, SecureVbanSender.STATUS_BUSY},
                new long[] {2001, SecureVbanSender.STATUS_NO_OUTPUT}, new long[] {4001, SecureVbanSender.STATUS_SENDING});
        // The same session all along: the counter carries on
        writePackets(1);
        assertEquals(Arrays.asList(0L, 1L, 2L), receiver.playedCounters);
        assertEquals(1, receiver.playedSessions.stream().distinct().count());
    }

    // The receiver gets back exactly the VBAN stream of what was recorded, in the same 256-sample
    // packets as VbanSenderTest.audioArrivesInOrderedPacketsOf256Samples
    @Test
    public void theReceiverGetsTheRecordedAudioInOrder() throws Exception {
        sender(5);
        connectReceiver();
        assertEquals(0, sender.open());
        runUntil(10);
        short[] ramp = ramp(1000, 0);
        short[] chunk = new short[300];
        for (int done = 0; done < ramp.length; done += chunk.length) {
            int n = Math.min(chunk.length, ramp.length - done);
            System.arraycopy(ramp, done, chunk, 0, n);
            sender.write(chunk, n);
        }
        List<FakeNetwork.Sent> audio = net.ofType(SwmcCodec.TYPE_AUDIO);
        assertEquals(3, audio.size());
        assertEquals(3, receiver.played.size());
        for (int p = 0; p < 3; p++) {
            assertEquals(584, audio.get(p).bytes.length);
            assertEquals(p, SwmcCodec.getU32(audio.get(p).bytes, SwmcCodec.COUNTER_AT));
            byte[] vban = receiver.played.get(p);
            assertArrayEquals(SwmcVectors.vbanFrame(p, p * 256), vban);
            // And nothing of the audio is readable on the wire
            byte[] wire = audio.get(p).bytes;
            assertFalse(new String(wire, java.nio.charset.StandardCharsets.ISO_8859_1).contains("VBAN"));
            assertFalse(new String(wire, java.nio.charset.StandardCharsets.ISO_8859_1).contains("Stream1"));
        }
        assertEquals(0, receiver.forged + receiver.replayedOrLate);
    }

    // At 0xFFFFFFFF the counter is used up: two packets go, then a new session, never a wrap
    @Test
    public void aUsedUpCounterStartsANewSession() throws Exception {
        sender(6);
        connectReceiver();
        sender.setFirstCounter(0xFFFFFFFEL);
        assertEquals(0, sender.open());
        runUntil(10);
        writePackets(3);
        List<FakeNetwork.Sent> audio = net.ofType(SwmcCodec.TYPE_AUDIO);
        assertEquals(2, audio.size());
        assertEquals(0xFFFFFFFEL, SwmcCodec.getU32(audio.get(0).bytes, SwmcCodec.COUNTER_AT));
        assertEquals(0xFFFFFFFFL, SwmcCodec.getU32(audio.get(1).bytes, SwmcCodec.COUNTER_AT));
        assertEquals(1, sender.droppedPackets());

        sender.setFirstCounter(0);
        runUntil(20);
        List<FakeNetwork.Sent> hellos = net.ofType(SwmcCodec.TYPE_HELLO);
        assertEquals(2, hellos.size());
        assertFalse("a new client random", Arrays.equals(hellos.get(0).bytes, hellos.get(1).bytes));
        writePackets(1);
        audio = net.ofType(SwmcCodec.TYPE_AUDIO);
        assertEquals(3, audio.size());
        assertEquals(0, SwmcCodec.getU32(audio.get(2).bytes, SwmcCodec.COUNTER_AT));
        assertNotEquals("in a new session", Arrays.toString(Arrays.copyOfRange(audio.get(1).bytes, 16, 24)),
                Arrays.toString(Arrays.copyOfRange(audio.get(2).bytes, 16, 24)));
        assertEquals(3, receiver.played.size());
    }

    // Every counter across a stream, sessions included, goes up: none is ever used twice on a key
    @Test
    public void countersOnlyGoUp() throws Exception {
        sender(7);
        connectReceiver();
        assertEquals(0, sender.open());
        runUntil(10);
        for (int i = 0; i < 50; i++) {
            writePackets(7);
            runUntil(net.now + 37);
        }
        long last = -1;
        for (FakeNetwork.Sent s : net.ofType(SwmcCodec.TYPE_AUDIO)) {
            long counter = SwmcCodec.getU32(s.bytes, SwmcCodec.COUNTER_AT);
            assertTrue(counter > last);
            last = counter;
        }
        assertEquals(349, last);
        assertEquals(350, receiver.played.size());
    }

    // The receiver restarted: it asks for a new session (REHELLO), and audio is back right after
    @Test
    public void aRestartedReceiverGetsANewSession() throws Exception {
        sender(8);
        connectReceiver();
        assertEquals(0, sender.open());
        runUntil(10);
        writePackets(5);
        assertEquals(5, receiver.played.size());

        receiver.restart();
        writePackets(2);
        assertEquals(2, receiver.unknownSession);
        // Its REHELLOs arrived; the sender asks once, and the old session keeps going until then
        runUntil(net.now + 5);
        assertEquals(2, helloTimes().size());
        writePackets(3);
        assertEquals(8, receiver.played.size());
        assertEquals(2, receiver.playedSessions.stream().distinct().count());
        assertEquals(Arrays.asList(0L, 1L, 2L, 3L, 4L, 0L, 1L, 2L), receiver.playedCounters);
    }

    // The receiver restarts while it says "busy", with audio going, and none of its REHELLOs get
    // through. The HELLO the sender repeats every 2 s is answered with another session, so the
    // sender asks with a new HELLO, and audio plays again by itself with no key used twice
    @Test
    public void aRestartWhileBusyRecoversWithoutRehellos() throws Exception {
        restartWhileNotReadyWithRehellosLost(SwmcCodec.STATUS_BUSY, SecureVbanSender.STATUS_BUSY);
    }

    // The same while it says "no output"
    @Test
    public void aRestartWithNoOutputRecoversWithoutRehellos() throws Exception {
        restartWhileNotReadyWithRehellosLost(SwmcCodec.STATUS_NO_OUTPUT, SecureVbanSender.STATUS_NO_OUTPUT);
    }

    private void restartWhileNotReadyWithRehellosLost(int status, int told) throws Exception {
        sender(15);
        connectReceiver();
        receiver.status = status;
        FakeNetwork.Responder answer = net.responder;
        net.responder = (sent, now) -> {
            byte[] reply = answer.answer(sent, now);
            boolean rehello = reply != null && SwmcCodec.typeOf(reply, reply.length) == SwmcCodec.TYPE_REHELLO;
            return rehello ? null : reply;
        };
        assertEquals(0, sender.open());
        runUntil(10);
        writePackets(3);

        // A packet every 20 ms, through the restart and on until 10 s
        receiver.restart();
        while (net.now < 10_000) {
            writePackets(1);
            runUntil(net.now + 20);
        }

        assertArrayEquals("an answer to the first HELLO", Arrays.copyOfRange(answers.get(0), 16, 24), Arrays.copyOfRange(answers.get(1), 16, 24));
        assertFalse("with another session", Arrays.equals(Arrays.copyOfRange(answers.get(0), 24, 32), Arrays.copyOfRange(answers.get(1), 24, 32)));
        // The first HELLO, again at 2 s; then one new HELLO, repeated every 2 s while not ready
        assertEquals(Arrays.asList(0L, 2000L, 2001L, 4001L, 6001L, 8001L, 10001L), helloTimes());
        List<FakeNetwork.Sent> hellos = net.ofType(SwmcCodec.TYPE_HELLO);
        assertArrayEquals(hellos.get(0).bytes, hellos.get(1).bytes);
        assertFalse(Arrays.equals(hellos.get(0).bytes, hellos.get(2).bytes));
        for (FakeNetwork.Sent s : hellos.subList(2, hellos.size())) {
            assertArrayEquals("one new HELLO, repeated", hellos.get(2).bytes, s.bytes);
        }
        assertEquals("the first session, then the new HELLO's", 2, assertNoKeyAndNonceUsedTwice().size());
        // Only the 100 packets of the 2 s before the repeated HELLO were lost; every one after it
        // played, in the new session
        assertEquals(100, receiver.unknownSession);
        assertEquals(403, receiver.played.size());
        assertEquals(2, receiver.playedSessions.stream().distinct().count());
        assertEquals(0, receiver.replayedOrLate + receiver.forged);
        assertStatuses(new long[] {0, SecureVbanSender.STATUS_CONNECTING}, new long[] {1, told},
                new long[] {2001, SecureVbanSender.STATUS_CONNECTING}, new long[] {2002, told});
    }

    @Test
    public void rehellosForOtherSessionsOrTooOftenAreIgnored() throws Exception {
        sender(9);
        connectReceiver();
        assertEquals(0, sender.open());
        runUntil(10);
        writePackets(1);
        byte[] authKey = receiver.authKey;
        byte[] current = Arrays.copyOfRange(net.ofType(SwmcCodec.TYPE_AUDIO).get(0).bytes, 16, 24);
        byte[] other = current.clone();
        other[0] ^= 1;
        net.deliver(20, SwmcCodec.rehello(authKey, SwmcVectors.DEVICE_ID, other));
        byte[] forged = SwmcCodec.rehello(authKey, SwmcVectors.DEVICE_ID, current);
        forged[39] ^= 1;
        net.deliver(30, forged);
        net.deliver(40, SwmcCodec.rehello(SwmcCodec.authKey(new byte[32], SwmcVectors.DEVICE_ID), SwmcVectors.DEVICE_ID, current));
        runUntil(100);
        assertEquals(1, helloTimes().size());

        // A real one starts a new session; another for that one within 2 s doesn't, after 2 s it does
        net.deliver(200, SwmcCodec.rehello(authKey, SwmcVectors.DEVICE_ID, current));
        runUntil(300);
        assertEquals(2, helloTimes().size());
        assertEquals(Long.valueOf(200), helloTimes().get(1));
        writePackets(1);
        List<FakeNetwork.Sent> audio = net.ofType(SwmcCodec.TYPE_AUDIO);
        byte[] second = Arrays.copyOfRange(audio.get(audio.size() - 1).bytes, 16, 24);
        assertFalse(Arrays.equals(current, second));
        net.deliver(1000, SwmcCodec.rehello(authKey, SwmcVectors.DEVICE_ID, second));
        net.deliver(2199, SwmcCodec.rehello(authKey, SwmcVectors.DEVICE_ID, second));
        runUntil(2199 + 10);
        assertEquals(2, helloTimes().size());
        net.deliver(2200 + 20, SwmcCodec.rehello(authKey, SwmcVectors.DEVICE_ID, second));
        runUntil(2300);
        assertEquals(3, helloTimes().size());
        assertEquals(Long.valueOf(2220), helloTimes().get(2));
        // The old session's audio was played up to the switch, the new ones' after it
        assertEquals(2, receiver.played.size());
    }

    // While the new HELLO waits for its answer, REHELLOs for the old session don't start yet another
    @Test
    public void aWaitingHelloIsNotRestartedByMoreRehellos() throws Exception {
        sender(11);
        connectReceiver();
        assertEquals(0, sender.open());
        runUntil(10);
        writePackets(1);
        byte[] old = Arrays.copyOfRange(net.ofType(SwmcCodec.TYPE_AUDIO).get(0).bytes, 16, 24);
        net.responder = null;
        for (long t = 100; t <= 9100; t += 1000) {
            net.deliver(t, SwmcCodec.rehello(receiver.authKey, SwmcVectors.DEVICE_ID, old));
        }
        runUntil(9500);
        List<FakeNetwork.Sent> hellos = net.ofType(SwmcCodec.TYPE_HELLO);
        for (FakeNetwork.Sent s : hellos.subList(1, hellos.size())) {
            assertArrayEquals("one new HELLO, repeated", hellos.get(1).bytes, s.bytes);
        }
        assertEquals(Arrays.asList(0L, 100L, 350L, 600L, 850L, 1100L, 1350L, 1600L, 1850L, 2100L, 4100L, 6100L, 8100L),
                helloTimes());
    }

    // The receiver restarts while it says "no output", so the HELLO the sender repeats gets a
    // second session. Someone who recorded the first answer sends it back, then the second one.
    // Neither may start its key again at counter 0: the sender asks with a new HELLO instead
    @Test
    public void aSecondAnswerToTheSameHelloNeverReusesAKey() throws Exception {
        secondAnswerToTheFirstHello(SwmcCodec.STATUS_NO_OUTPUT);
    }

    // The same with the PC ready, so the sender doesn't repeat its HELLO: someone who recorded it
    // sends it to the restarted receiver themselves, and gives the sender the answer
    @Test
    public void aReplayedHelloAndItsOldAnswersNeverReuseAKey() throws Exception {
        secondAnswerToTheFirstHello(SwmcCodec.STATUS_READY);
    }

    private void secondAnswerToTheFirstHello(int status) throws Exception {
        sender(12);
        connectReceiver();
        receiver.status = status;
        assertEquals(0, sender.open());
        runUntil(10);
        writePackets(3);
        byte[] hello1 = net.ofType(SwmcCodec.TYPE_HELLO).get(0).bytes;
        byte[] answerA = answers.get(0);

        // The receiver restarts before any REHELLO gets through (the microphone was quiet, or
        // someone used them up), and the first HELLO is answered again with a new session
        receiver.restart();
        if (status == SwmcCodec.STATUS_READY) {
            byte[] stolen = receiver.handle(hello1, hello1.length);
            answers.add(stolen);
            net.deliver(net.now + 1, stolen);
        }
        runUntil(2010);
        byte[] answerB = answers.get(1);
        assertArrayEquals("an answer to the first HELLO", Arrays.copyOfRange(answerA, 16, 24), Arrays.copyOfRange(answerB, 16, 24));
        assertFalse("with another session", Arrays.equals(Arrays.copyOfRange(answerA, 24, 32), Arrays.copyOfRange(answerB, 24, 32)));
        writePackets(3);

        // The recorded answers come back in turn: the first, the second (after the session that
        // came next), and the current one again
        replay(answerA);
        writePackets(3);
        replay(answerB);
        writePackets(3);
        replay(answers.get(answers.size() - 1));
        writePackets(3);

        List<String> keys = assertNoKeyAndNonceUsedTwice();
        assertEquals("the first session, then the new HELLO's", 2, keys.size());
        // And it got going again by itself: everything since the restart played, in one session
        // that the restarted receiver knows, its counter carrying on through the replays
        assertEquals(counters(0, 1, 2, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11), receiver.playedCounters);
        assertEquals(0, receiver.unknownSession + receiver.replayedOrLate + receiver.forged);
        List<FakeNetwork.Sent> audio = net.ofType(SwmcCodec.TYPE_AUDIO);
        byte[] current = answers.get(answers.size() - 1);
        assertArrayEquals(Arrays.copyOfRange(current, 24, 32), Arrays.copyOfRange(audio.get(audio.size() - 1).bytes, 16, 24));
        int want = status == SwmcCodec.STATUS_READY ? SecureVbanSender.STATUS_SENDING : SecureVbanSender.STATUS_NO_OUTPUT;
        assertEquals(want, statuses.get(statuses.size() - 1)[1]);
    }

    // After real restarts (REHELLO, then a new HELLO), answers to earlier HELLOs change nothing,
    // whenever they come: before the new answer, after a later session, or the current one again
    @Test
    public void oldAnswersAfterARestartChangeNothing() throws Exception {
        sender(13);
        connectReceiver();
        assertEquals(0, sender.open());
        runUntil(10);
        writePackets(3);
        byte[] answerA = answers.get(0);

        // The first answer comes back between the new HELLO and its answer
        receiver.restart();
        net.latencyMs = 20;
        long t = net.now;
        writePackets(2);
        net.deliver(t + 30, answerA);
        runUntil(t + 50);
        net.latencyMs = 1;
        assertEquals(2, helloTimes().size());
        assertEquals(2, answers.size());
        byte[] answerB = answers.get(1);
        writePackets(3);

        // Another restart, more than 2 s later; then the older answers, and the current one again
        runUntil(3000);
        receiver.restart();
        writePackets(2);
        runUntil(3010);
        assertEquals(3, helloTimes().size());
        byte[] answerC = answers.get(2);
        writePackets(3);
        replay(answerB);
        writePackets(3);
        replay(answerA);
        writePackets(3);
        replay(answerC);
        writePackets(3);

        assertEquals(3, assertNoKeyAndNonceUsedTwice().size());
        assertEquals(counters(0, 1, 2, 0, 1, 2, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11), receiver.playedCounters);
        assertEquals(3, receiver.playedSessions.stream().distinct().count());
        assertEquals(SecureVbanSender.STATUS_SENDING, statuses.get(statuses.size() - 1)[1]);
    }

    // A used-up counter ends the session. If its answer comes again before the receive thread has
    // made the new HELLO, that key mustn't start again
    @Test
    public void anOldAnswerAfterTheCounterRanOutDoesntStartItsKeyAgain() throws Exception {
        sender(14);
        connectReceiver();
        sender.setFirstCounter(0xFFFFFFFEL);
        assertEquals(0, sender.open());
        runUntil(10);
        writePackets(3);
        assertEquals(2, net.ofType(SwmcCodec.TYPE_AUDIO).size());

        byte[] answer = answers.get(0);
        sender.onDatagram(answer.clone(), answer.length);
        writePackets(1);
        runUntil(20);
        writePackets(1);

        assertEquals(2, assertNoKeyAndNonceUsedTwice().size());
        assertEquals(2, helloTimes().size());
        assertEquals(3, net.ofType(SwmcCodec.TYPE_AUDIO).size());
        assertEquals(2, sender.droppedPackets());
        assertEquals(3, receiver.played.size());
    }

    @Test
    public void closingStopsEverythingAndOpeningAgainStartsFresh() throws Exception {
        sender(10);
        connectReceiver();
        assertEquals(0, sender.open());
        runUntil(10);
        writePackets(2);
        sender.close();
        sender.close();
        int before = net.sent.size();
        writePackets(4);
        assertEquals(before, net.sent.size());

        assertEquals(0, sender.open());
        runUntil(net.now + 10);
        writePackets(1);
        List<FakeNetwork.Sent> hellos = net.ofType(SwmcCodec.TYPE_HELLO);
        assertEquals(2, hellos.size());
        assertFalse(Arrays.equals(hellos.get(0).bytes, hellos.get(1).bytes));
        List<FakeNetwork.Sent> audio = net.ofType(SwmcCodec.TYPE_AUDIO);
        assertEquals(0, SwmcCodec.getU32(audio.get(audio.size() - 1).bytes, SwmcCodec.COUNTER_AT));
        assertEquals(3, receiver.played.size());
    }
}
