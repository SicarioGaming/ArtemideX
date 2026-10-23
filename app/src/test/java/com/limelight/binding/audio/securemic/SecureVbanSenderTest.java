package com.limelight.binding.audio.securemic;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The encrypted microphone sender on a pretend network and clock: when HELLOs go out, that no
 * audio goes before the receiver answers, which answers count, what the user is told, that
 * counters only go up and never wrap, that the receiver gets back exactly the VBAN stream of what
 * was recorded, and that it recovers when the receiver restarts.
 */
public class SecureVbanSenderTest {
    private final FakeNetwork net = new FakeNetwork();
    private final List<long[]> statuses = new ArrayList<>();
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
        net.responder = (sent, now) -> receiver.handle(sent, sent.length);
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
