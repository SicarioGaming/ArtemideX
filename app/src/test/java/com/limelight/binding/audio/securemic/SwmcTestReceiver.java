package com.limelight.binding.audio.securemic;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The receiver's rules from docs/dev/mic-protocol.md for one paired device, in memory, for the
 * JVM tests: a HELLO gets a HELLO_ACK (the same session for the same client random), audio plays
 * only if it decrypts and its counter is higher than any played in that session, and audio for a
 * session it doesn't know gets a REHELLO. The real receiver is windows/SecureMic.cs.
 */
final class SwmcTestReceiver {
    final byte[] pairKey;
    final byte[] deviceId;
    final byte[] authKey;
    private final Random random;
    int status = SwmcCodec.STATUS_READY;

    private final Map<String, byte[]> sessionForClient = new HashMap<>();
    private final Map<String, Aead> aeadForSession = new HashMap<>();
    private final Map<String, Long> highestForSession = new HashMap<>();
    final List<byte[]> played = new ArrayList<>();
    final List<String> playedSessions = new ArrayList<>();
    final List<Long> playedCounters = new ArrayList<>();
    int hellos, replayedOrLate, forged, unknownSession, malformed;

    SwmcTestReceiver(byte[] pairKey, byte[] deviceId, long seed) throws GeneralSecurityException {
        this.pairKey = pairKey;
        this.deviceId = deviceId;
        this.authKey = SwmcCodec.authKey(pairKey, deviceId);
        this.random = new Random(seed);
    }

    /** Forgets every session, as a receiver that restarted would. */
    void restart() {
        sessionForClient.clear();
        aeadForSession.clear();
        highestForSession.clear();
    }

    /** Takes one datagram; returns the receiver's answer, or null. */
    byte[] handle(byte[] p, int length) throws GeneralSecurityException {
        int type = SwmcCodec.typeOf(p, length);
        if (type < 0 || !SwmcCodec.isFor(p, deviceId)) {
            malformed++;
            return null;
        }
        if (type == SwmcCodec.TYPE_HELLO) {
            if (length != SwmcCodec.HELLO_LENGTH || !SwmcCodec.macOk(authKey, p, length)) {
                forged++;
                return null;
            }
            hellos++;
            byte[] clientRandom = Arrays.copyOfRange(p, 16, 24);
            String client = SwmcVectors.hex(clientRandom);
            byte[] session = sessionForClient.get(client);
            if (session == null) {
                session = new byte[8];
                random.nextBytes(session);
                sessionForClient.put(client, session);
                aeadForSession.put(SwmcVectors.hex(session),
                        new Aead(SwmcCodec.audioKey(pairKey, deviceId, clientRandom, session)));
            }
            return SwmcCodec.helloAck(authKey, deviceId, clientRandom, session, status);
        }
        if (type == SwmcCodec.TYPE_AUDIO) {
            String session = SwmcVectors.hex(p, 16, 8);
            Aead aead = aeadForSession.get(session);
            if (aead == null) {
                unknownSession++;
                return SwmcCodec.rehello(authKey, deviceId, Arrays.copyOfRange(p, 16, 24));
            }
            byte[] vban = new byte[2048];
            int n = SwmcCodec.openAudio(aead, p, length, vban);
            if (n < 0) {
                forged++;
                return null;
            }
            long counter = SwmcCodec.getU32(p, SwmcCodec.COUNTER_AT);
            Long highest = highestForSession.get(session);
            if (highest != null && counter <= highest) {
                replayedOrLate++;
                return null;
            }
            highestForSession.put(session, counter);
            played.add(Arrays.copyOf(vban, n));
            playedSessions.add(session);
            playedCounters.add(counter);
            return null;
        }
        malformed++;
        return null;
    }
}
