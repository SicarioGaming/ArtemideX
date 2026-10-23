package com.limelight.binding.audio.securemic;

import com.limelight.LimeLog;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Starwire's side of pairing with the PC's microphone receiver (docs/dev/mic-protocol.md): sends
 * a PAIR_REQUEST made with the typed code to each of the PC's addresses every 500 ms for 6 s, and
 * takes the first PAIR_ACCEPT that checks out. Anyone can send a PAIR_REJECT, so one is only
 * believed if no real PAIR_ACCEPT follows within 500 ms.
 *
 * Blocks while it works, so it runs on a background thread. The network, the clock and the random
 * numbers are passed in, so the tests run it without sockets or waiting.
 */
public final class MicPairingClient {
    public enum Result {
        PAIRED,
        /** The code isn't 16 letters and numbers. */
        BAD_CODE,
        WRONG_CODE,
        /** The receiver isn't waiting to pair. */
        NOT_PAIRING,
        /** The code expired, was used up by wrong codes, or was used twice. */
        EXPIRED,
        NO_ANSWER,
        /** No home-network address to send to. */
        NO_ADDRESS,
        /** The receiver answered with the right code but a different key: try again. */
        FAILED,
    }

    public static final long RESEND_MS = 500;
    public static final long GIVE_UP_MS = 6000;
    public static final long REJECT_WAIT_MS = 500;

    public interface Transport {
        void send(byte[] packet, int length, InetSocketAddress to) throws IOException;

        /** Waits up to timeoutMs (at least 1) for a datagram; its length, or -1 if none came. */
        int receive(byte[] buffer, int timeoutMs) throws IOException;
    }

    public interface Clock {
        long nowMs();
    }

    public interface KeySource {
        KeyPair newKeyPair() throws GeneralSecurityException;
    }

    public static final class Outcome {
        public final Result result;
        /** Set when result is PAIRED. */
        public final MicPairing pairing;

        Outcome(Result result, MicPairing pairing) {
            this.result = result;
            this.pairing = pairing;
        }
    }

    private final Transport transport;
    private final Clock clock;
    private final Random random;
    private final KeySource keys;

    public MicPairingClient(Transport transport, Clock clock, Random random, KeySource keys) {
        this.transport = transport;
        this.clock = clock;
        this.random = random;
        this.keys = keys;
    }

    /** With the real clock, random numbers and one-time keys. */
    public static MicPairingClient create(Transport transport) {
        SecureRandom random = new SecureRandom();
        return new MicPairingClient(transport, () -> System.nanoTime() / 1_000_000, random,
                () -> SwmcCodec.newKeyPair(random));
    }

    /**
     * Pairs with the receiver at these addresses using the typed code. deviceId is the one Starwire
     * already uses with this PC, or null for a new one.
     */
    public Outcome pair(List<InetSocketAddress> receivers, String typedCode, String deviceName, byte[] deviceId,
                        String computerUuid, String computerName) {
        byte[] code = PairingCode.decode(typedCode);
        if (code == null) {
            return new Outcome(Result.BAD_CODE, null);
        }
        if (receivers.isEmpty()) {
            return new Outcome(Result.NO_ADDRESS, null);
        }
        try {
            return run(receivers, code, deviceName, deviceId, computerUuid, computerName);
        }
        catch (GeneralSecurityException e) {
            LimeLog.warning("Microphone pairing: " + e);
            return new Outcome(Result.FAILED, null);
        }
    }

    private Outcome run(List<InetSocketAddress> receivers, byte[] code, String deviceName, byte[] knownDeviceId,
                        String computerUuid, String computerName) throws GeneralSecurityException {
        byte[] deviceId = knownDeviceId != null ? knownDeviceId.clone() : new byte[SwmcCodec.ID_LENGTH];
        if (knownDeviceId == null) {
            random.nextBytes(deviceId);
        }
        byte[] nonce = new byte[SwmcCodec.ID_LENGTH];
        random.nextBytes(nonce);
        KeyPair mine = keys.newKeyPair();
        byte[] phonePoint = SwmcCodec.encodePoint(mine.getPublic());
        byte[] codeKey = SwmcCodec.codeKey(code);
        // The same bytes every time, so the receiver knows it's the same request
        byte[] request = SwmcCodec.pairRequest(codeKey, deviceId, nonce, phonePoint, deviceName);

        long start = clock.nowMs();
        long nextSend = start;
        int rejectReason = -1;
        long rejectDecideAt = 0;
        byte[] buffer = new byte[2048];
        while (true) {
            long now = clock.nowMs();
            if (rejectReason >= 0 && now >= rejectDecideAt) {
                return new Outcome(reasonResult(rejectReason), null);
            }
            if (rejectReason < 0 && now - start >= GIVE_UP_MS) {
                return new Outcome(Result.NO_ANSWER, null);
            }
            if (now >= nextSend && now - start < GIVE_UP_MS) {
                for (InetSocketAddress to : receivers) {
                    try {
                        transport.send(request, request.length, to);
                    }
                    catch (IOException e) {
                        // One address the network can't reach (IPv6 on an IPv4-only network, say)
                    }
                }
                while (nextSend <= now) {
                    nextSend += RESEND_MS;
                }
            }

            long wake = start + GIVE_UP_MS;
            if (nextSend < wake) {
                wake = nextSend;
            }
            if (rejectReason >= 0) {
                wake = Math.min(wake, rejectDecideAt);
                if (now - start >= GIVE_UP_MS) {
                    wake = rejectDecideAt;
                }
            }
            int length;
            try {
                length = transport.receive(buffer, (int) Math.max(1, wake - now));
            }
            catch (IOException e) {
                length = -1;
            }
            if (length <= 0) {
                continue;
            }

            int type = SwmcCodec.typeOf(buffer, length);
            if (type == SwmcCodec.TYPE_PAIR_ACCEPT) {
                Outcome accepted = accept(buffer, length, codeKey, deviceId, nonce, mine, phonePoint, computerUuid, computerName);
                if (accepted != null) {
                    return accepted;
                }
            }
            else if (type == SwmcCodec.TYPE_PAIR_REJECT && rejectReason < 0
                    && SwmcCodec.isFor(buffer, deviceId) && SwmcCodec.contextIs(buffer, nonce)) {
                int reason = SwmcCodec.pairRejectReason(buffer, length);
                if (reason >= 0) {
                    rejectReason = reason;
                    rejectDecideAt = clock.nowMs() + REJECT_WAIT_MS;
                }
            }
        }
    }

    // The pairing a PAIR_ACCEPT makes, or null if it isn't for this request or doesn't check out
    private Outcome accept(byte[] p, int length, byte[] codeKey, byte[] deviceId, byte[] nonce, KeyPair mine,
                           byte[] phonePoint, String computerUuid, String computerName) throws GeneralSecurityException {
        int nameLength = SwmcCodec.pairAcceptNameLength(p, length);
        if (nameLength < 0 || !SwmcCodec.isFor(p, deviceId) || !SwmcCodec.contextIs(p, nonce)
                || !SwmcCodec.macOk(codeKey, p, length)) {
            return null;
        }
        // From here the receiver knew the code
        PublicKey receiverKey = SwmcCodec.decodePoint(p, SwmcCodec.ACCEPT_POINT_AT);
        if (receiverKey == null) {
            LimeLog.warning("Microphone pairing: the receiver's key isn't a point on P-256");
            return new Outcome(Result.FAILED, null);
        }
        byte[] receiverPoint = Arrays.copyOfRange(p, SwmcCodec.ACCEPT_POINT_AT, SwmcCodec.ACCEPT_POINT_AT + SwmcCodec.POINT_LENGTH);
        byte[] z = SwmcCodec.ecdhHash(mine.getPrivate(), receiverKey);
        byte[] pairKey = SwmcCodec.pairKey(codeKey, z, deviceId, nonce, phonePoint, receiverPoint);
        int confirmAt = SwmcCodec.ACCEPT_NAME_AT + nameLength;
        if (!SwmcCodec.constantTimeEquals(SwmcCodec.confirm(pairKey), 0, p, confirmAt, SwmcCodec.MAC_LENGTH)) {
            LimeLog.warning("Microphone pairing: the receiver's confirmation doesn't match");
            return new Outcome(Result.FAILED, null);
        }
        byte[] receiverId = Arrays.copyOfRange(p, SwmcCodec.ACCEPT_RECEIVER_ID_AT, SwmcCodec.ACCEPT_RECEIVER_ID_AT + SwmcCodec.ID_LENGTH);
        String receiverName = SwmcCodec.cleanName(
                new String(p, SwmcCodec.ACCEPT_NAME_AT, nameLength, java.nio.charset.StandardCharsets.UTF_8), "");
        return new Outcome(Result.PAIRED, new MicPairing(computerUuid, computerName, deviceId, pairKey, receiverId,
                receiverName, System.currentTimeMillis()));
    }

    private static Result reasonResult(int reason) {
        switch (reason) {
            case SwmcCodec.REJECT_NOT_PAIRING:
                return Result.NOT_PAIRING;
            case SwmcCodec.REJECT_WRONG_CODE:
                return Result.WRONG_CODE;
            case SwmcCodec.REJECT_EXPIRED:
                return Result.EXPIRED;
            default:
                return Result.FAILED;
        }
    }
}
