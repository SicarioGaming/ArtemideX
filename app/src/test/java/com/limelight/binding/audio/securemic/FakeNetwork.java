package com.limelight.binding.audio.securemic;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * A pretend clock and network for the microphone's JVM tests: time only moves when a receive
 * waits, so every run is the same and nothing sleeps.
 */
final class FakeNetwork {
    static final class Sent {
        final long at;
        final byte[] bytes;
        final InetSocketAddress to;

        Sent(long at, byte[] bytes, InetSocketAddress to) {
            this.at = at;
            this.bytes = bytes;
            this.to = to;
        }

        int type() {
            return SwmcCodec.typeOf(bytes, bytes.length);
        }
    }

    /** Answers what's sent, like a receiver would; null for no answer. */
    interface Responder {
        byte[] answer(byte[] sent, long now) throws Exception;
    }

    private static final class Pending {
        final long at;
        final byte[] bytes;

        Pending(long at, byte[] bytes) {
            this.at = at;
            this.bytes = bytes;
        }
    }

    long now;
    final List<Sent> sent = new ArrayList<>();
    private final List<Pending> inbox = new ArrayList<>();
    Responder responder;
    long latencyMs = 1;

    /** Something arrives at that time. */
    void deliver(long at, byte[] bytes) {
        inbox.add(new Pending(at, bytes.clone()));
    }

    void send(byte[] packet, int length, InetSocketAddress to) {
        byte[] copy = Arrays.copyOf(packet, length);
        sent.add(new Sent(now, copy, to));
        if (responder != null) {
            try {
                byte[] reply = responder.answer(copy, now);
                if (reply != null) {
                    deliver(now + latencyMs, reply);
                }
            }
            catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }

    /** The first datagram due within timeoutMs, moving the clock to it; else waits it all out. */
    int receive(byte[] buffer, int timeoutMs) {
        if (timeoutMs < 1) {
            throw new AssertionError("a receive with no wait would spin");
        }
        Pending first = null;
        for (Pending p : inbox) {
            if (first == null || p.at < first.at) {
                first = p;
            }
        }
        if (first != null && first.at <= now + timeoutMs) {
            inbox.remove(first);
            now = Math.max(now, first.at);
            System.arraycopy(first.bytes, 0, buffer, 0, first.bytes.length);
            return first.bytes.length;
        }
        now += timeoutMs;
        return -1;
    }

    List<Sent> ofType(int type) {
        List<Sent> out = new ArrayList<>();
        for (Sent s : sent) {
            if (s.type() == type) {
                out.add(s);
            }
        }
        return out;
    }

    /** Hands out these bytes, in order, to nextBytes; then fails. */
    static final class FixedRandom extends Random {
        private final List<byte[]> queue = new ArrayList<>();

        FixedRandom(byte[]... values) {
            queue.addAll(Arrays.asList(values));
        }

        @Override
        public void nextBytes(byte[] bytes) {
            if (queue.isEmpty()) {
                throw new AssertionError("more random numbers than the test expected");
            }
            byte[] next = queue.remove(0);
            if (next.length != bytes.length) {
                throw new AssertionError("asked for " + bytes.length + " random bytes, not " + next.length);
            }
            System.arraycopy(next, 0, bytes, 0, bytes.length);
        }
    }
}
