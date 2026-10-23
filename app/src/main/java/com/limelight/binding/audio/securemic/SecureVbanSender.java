package com.limelight.binding.audio.securemic;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.audio.MicrophoneSink;
import com.limelight.binding.audio.VbanPacketizer;
import com.limelight.binding.audio.VbanSender;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Random;

/**
 * Sends the microphone to Starwire's receiver on the PC, encrypted (SWMC v1,
 * docs/dev/mic-protocol.md). The audio is packed into VBAN packets as for any VBAN receiver, and
 * each one is sealed with the session's AES-256-GCM key and a counter that always goes up.
 *
 * A session starts with a HELLO, sent at 0, 250 ... 2000 ms and then every 2 s until the receiver
 * answers. Audio before that is dropped. A thread of its own ("MicSecureRx") sends the HELLOs and
 * reads the receiver's answers; the capture thread only seals and sends audio. The network, clock
 * and random numbers can be passed in, so the tests drive it without sockets or waiting.
 */
public final class SecureVbanSender implements MicrophoneSink {
    /** Where the stream stands, for the user. */
    public static final int STATUS_CONNECTING = 0;
    public static final int STATUS_SENDING = 1;
    public static final int STATUS_NO_ANSWER = 2;
    public static final int STATUS_BUSY = 3;
    public static final int STATUS_NO_OUTPUT = 4;

    static final long FAST_RETRY_MS = 250;
    static final long FAST_RETRIES_FOR_MS = 2000;
    static final long SLOW_RETRY_MS = 2000;
    static final long NO_ANSWER_MS = 3000;
    static final long REHELLO_GAP_MS = 2000;
    // The receive thread looks at least this often, so a used-up counter gets a new HELLO quickly
    private static final int MAX_WAIT_MS = 50;

    /** Told when the status changes, on the sender's own thread. */
    public interface Listener {
        void onStatus(int status);
    }

    interface Transport {
        void send(byte[] packet, int length) throws IOException;

        /** Waits up to timeoutMs (at least 1) for a datagram; its length, or -1 if none came. */
        int receive(byte[] buffer, int timeoutMs) throws IOException;

        void close();
    }

    interface TransportFactory {
        Transport open() throws IOException;
    }

    interface Clock {
        long nowMs();
    }

    private static final class Session {
        final Aead aead;
        final byte[] id;
        long next;

        Session(Aead aead, byte[] id, long first) {
            this.aead = aead;
            this.id = id;
            this.next = first;
        }
    }

    private final TransportFactory factory;
    private final Clock clock;
    private final Random random;
    private final byte[] deviceId;
    private final byte[] pairKey;
    private final Listener listener;
    private final boolean ownThread;
    private final VbanPacketizer packetizer;
    private final byte[] sealed = new byte[SwmcCodec.MAX_AUDIO_LENGTH];

    private volatile Transport transport;
    private volatile boolean closed = true;
    private volatile int generation;
    private byte[] authKey;

    private final Object lock = new Object();
    // Guarded by lock: the session audio goes out on, shared with the capture thread
    private Session session;
    private boolean needNewSession;
    private long firstCounter;
    private long dropped;
    private long sent;

    // The receive thread's (or the test's) own
    private byte[] clientRandom;
    private byte[] hello;
    // The session the receiver first answered this HELLO with; null until then
    private byte[] helloSession;
    private long helloStart;
    private int hellosSent;
    private boolean acked;
    private int ackStatus;
    private long lastHelloAt;
    private long lastRehelloAt;
    private int status = -1;
    private boolean sendFailureLogged;

    public SecureVbanSender(InetAddress address, int port, MicPairing pairing, Listener listener) {
        this(() -> new UdpTransport(address, port), () -> System.nanoTime() / 1_000_000, new SecureRandom(),
                pairing.deviceId, pairing.key, listener, true);
    }

    SecureVbanSender(TransportFactory factory, Clock clock, Random random, byte[] deviceId, byte[] pairKey,
                     Listener listener, boolean ownThread) {
        this.factory = factory;
        this.clock = clock;
        this.random = random;
        this.deviceId = deviceId.clone();
        this.pairKey = pairKey.clone();
        this.listener = listener;
        this.ownThread = ownThread;
        // The receiver ignores the stream name inside: pairing says who sent it
        this.packetizer = new VbanPacketizer(VbanSender.DEFAULT_STREAM_NAME, this::sendAudio);
    }

    @Override
    public int open() {
        close();
        try {
            authKey = SwmcCodec.authKey(pairKey, deviceId);
            transport = factory.open();
        }
        catch (IOException | GeneralSecurityException e) {
            LimeLog.warning("Couldn't start the encrypted microphone: " + e);
            return R.string.microphone_secure_failed;
        }
        synchronized (lock) {
            session = null;
            needNewSession = false;
            dropped = 0;
            sent = 0;
        }
        packetizer.reset();
        status = -1;
        sendFailureLogged = false;
        lastRehelloAt = Long.MIN_VALUE / 2;
        closed = false;
        final int run = ++generation;
        startHandshake(clock.nowMs());
        if (ownThread) {
            final Transport t = transport;
            Thread thread = new Thread(() -> receiveLoop(t, run), "MicSecureRx");
            thread.setDaemon(true);
            thread.start();
        }
        return 0;
    }

    @Override
    public void write(short[] pcm, int samples) {
        if (closed) {
            return;
        }
        packetizer.write(pcm, samples);
    }

    @Override
    public void close() {
        boolean wasOpen = !closed;
        closed = true;
        Transport t = transport;
        if (t != null) {
            t.close();
        }
        synchronized (lock) {
            if (wasOpen) {
                LimeLog.info("Encrypted microphone: sent " + sent + " packets, dropped " + dropped + " while waiting for the receiver");
            }
        }
    }

    private void receiveLoop(Transport t, int run) {
        byte[] buffer = new byte[2048];
        while (!closed && run == generation) {
            long wait = poll();
            int length;
            try {
                length = t.receive(buffer, (int) Math.max(1, Math.min(wait, MAX_WAIT_MS)));
            }
            catch (IOException e) {
                if (closed) {
                    break;
                }
                // Usually the network dropped for a moment
                length = -1;
                try {
                    Thread.sleep(MAX_WAIT_MS);
                }
                catch (InterruptedException ie) {
                    break;
                }
            }
            if (length > 0 && !closed) {
                onDatagram(buffer, length);
            }
        }
    }

    // ---------------------------------------------------------------- the handshake
    private void startHandshake(long now) {
        clientRandom = new byte[SwmcCodec.ID_LENGTH];
        random.nextBytes(clientRandom);
        try {
            hello = SwmcCodec.hello(authKey, deviceId, clientRandom);
        }
        catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        helloStart = now;
        hellosSent = 0;
        acked = false;
        helloSession = null;
        setStatus(STATUS_CONNECTING);
    }

    // When the HELLO after the first n is due: every 250 ms for 2 s, then every 2 s
    private long helloDue(int n) {
        if (n * FAST_RETRY_MS <= FAST_RETRIES_FOR_MS) {
            return helloStart + n * FAST_RETRY_MS;
        }
        int fast = (int) (FAST_RETRIES_FOR_MS / FAST_RETRY_MS) + 1;
        return helloStart + FAST_RETRIES_FOR_MS + (n - fast + 1) * SLOW_RETRY_MS;
    }

    /** Sends whatever is due; returns how long until something else is. */
    long poll() {
        if (closed) {
            return MAX_WAIT_MS;
        }
        long now = clock.nowMs();
        boolean renew;
        synchronized (lock) {
            renew = needNewSession;
            needNewSession = false;
        }
        if (renew) {
            startHandshake(now);
        }
        long next;
        if (!acked) {
            if (now >= helloDue(hellosSent)) {
                sendHello(now);
                hellosSent++;
                // After a long pause, don't send the ones that were missed all at once
                while (helloDue(hellosSent) <= now) {
                    hellosSent++;
                }
            }
            next = helloDue(hellosSent);
            if (now - helloStart >= NO_ANSWER_MS) {
                setStatus(STATUS_NO_ANSWER);
            }
            else {
                next = Math.min(next, helloStart + NO_ANSWER_MS);
            }
        }
        else if (ackStatus != SwmcCodec.STATUS_READY) {
            // Busy or no output: ask again (same HELLO, same session) to see when it's ready
            if (now - lastHelloAt >= SLOW_RETRY_MS) {
                sendHello(now);
            }
            next = lastHelloAt + SLOW_RETRY_MS;
        }
        else {
            next = now + MAX_WAIT_MS;
        }
        return Math.max(0, next - now);
    }

    private void sendHello(long now) {
        lastHelloAt = now;
        send(hello, hello.length);
    }

    /** Takes one datagram from the receiver. */
    void onDatagram(byte[] p, int length) {
        try {
            int type = SwmcCodec.typeOf(p, length);
            if (type == SwmcCodec.TYPE_HELLO_ACK) {
                onAck(p, length);
            }
            else if (type == SwmcCodec.TYPE_REHELLO) {
                onRehello(p, length);
            }
        }
        catch (GeneralSecurityException e) {
            LimeLog.warning("Encrypted microphone: " + e);
        }
    }

    private void onAck(byte[] p, int length) throws GeneralSecurityException {
        if (length != SwmcCodec.ACK_LENGTH || !SwmcCodec.isFor(p, deviceId) || !SwmcCodec.contextIs(p, clientRandom)
                || !SwmcCodec.macOk(authKey, p, length)) {
            return;
        }
        byte[] serverRandom = Arrays.copyOfRange(p, SwmcCodec.HEADER, SwmcCodec.HEADER + SwmcCodec.ID_LENGTH);
        if (helloSession != null && !Arrays.equals(helloSession, serverRandom)) {
            // Another session for the same HELLO: the receiver restarted, or someone sent an
            // older answer again, whose key may already have sealed audio from counter 0. A HELLO
            // only ever gets one session, so ask with a new one; until it's answered, audio goes
            // on in the current session
            startHandshake(clock.nowMs());
            return;
        }
        if (helloSession == null) {
            // The first answer to this HELLO, and the only one it ever gets a session from
            synchronized (lock) {
                Aead aead = new Aead(SwmcCodec.audioKey(pairKey, deviceId, clientRandom, serverRandom));
                session = new Session(aead, serverRandom, firstCounter);
                LimeLog.info("Encrypted microphone: session started (AES-GCM from " + aead.providerName() + ")");
            }
            helloSession = serverRandom;
        }
        acked = true;
        ackStatus = p[SwmcCodec.ACK_STATUS_AT] & 0xFF;
        switch (ackStatus) {
            case SwmcCodec.STATUS_BUSY:
                setStatus(STATUS_BUSY);
                break;
            case SwmcCodec.STATUS_NO_OUTPUT:
                setStatus(STATUS_NO_OUTPUT);
                break;
            default:
                // Ready, or a status a newer receiver added: the session works either way
                ackStatus = SwmcCodec.STATUS_READY;
                setStatus(STATUS_SENDING);
                break;
        }
    }

    private void onRehello(byte[] p, int length) throws GeneralSecurityException {
        if (length != SwmcCodec.HELLO_LENGTH || !SwmcCodec.isFor(p, deviceId) || !SwmcCodec.macOk(authKey, p, length)) {
            return;
        }
        byte[] current;
        synchronized (lock) {
            current = session != null ? session.id : null;
        }
        long now = clock.nowMs();
        // Only for the session audio goes out on, and not while a new one is on its way
        if (current == null || !acked || !SwmcCodec.contextIs(p, current) || now - lastRehelloAt < REHELLO_GAP_MS) {
            return;
        }
        lastRehelloAt = now;
        // The old session keeps sending until the new one is answered
        startHandshake(now);
    }

    private void setStatus(int newStatus) {
        if (newStatus != status) {
            status = newStatus;
            if (listener != null) {
                listener.onStatus(newStatus);
            }
        }
    }

    // ---------------------------------------------------------------- audio
    private void sendAudio(byte[] vban, int length) {
        int n;
        synchronized (lock) {
            Session s = session;
            if (s == null) {
                dropped++;
                return;
            }
            if (s.next > SwmcCodec.MAX_COUNTER) {
                // Never wrap on the same key: a new session first
                session = null;
                needNewSession = true;
                dropped++;
                return;
            }
            try {
                n = SwmcCodec.audio(s.aead, deviceId, s.id, s.next, vban, length, sealed);
            }
            catch (GeneralSecurityException e) {
                dropped++;
                LimeLog.warning("Encrypted microphone: couldn't seal a packet: " + e);
                return;
            }
            s.next++;
            sent++;
        }
        send(sealed, n);
    }

    private void send(byte[] packet, int length) {
        Transport t = transport;
        if (t == null || closed) {
            return;
        }
        try {
            t.send(packet, length);
        }
        catch (IOException e) {
            // Usually the network dropped for a moment; keep trying with the next packet
            if (!sendFailureLogged) {
                sendFailureLogged = true;
                LimeLog.warning("Encrypted microphone: send failed: " + e.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------- for tests
    /** Sessions start their counter here (normally 0), to test what happens when it runs out. */
    void setFirstCounter(long first) {
        synchronized (lock) {
            firstCounter = first;
        }
    }

    long droppedPackets() {
        synchronized (lock) {
            return dropped;
        }
    }

    /** UDP to the PC's receiver. Not connected, so an ICMP error can't break the receive loop. */
    private static final class UdpTransport implements Transport {
        private final DatagramSocket socket;
        private final DatagramPacket out;

        UdpTransport(InetAddress address, int port) throws IOException {
            socket = new DatagramSocket();
            out = new DatagramPacket(new byte[0], 0, address, port);
            LimeLog.info("Sending the microphone, encrypted, to " + address.getHostAddress() + ":" + port);
        }

        @Override
        public void send(byte[] packet, int length) throws IOException {
            // The capture thread sends audio and the receive thread HELLOs
            synchronized (out) {
                out.setData(packet, 0, length);
                socket.send(out);
            }
        }

        @Override
        public int receive(byte[] buffer, int timeoutMs) throws IOException {
            DatagramPacket in = new DatagramPacket(buffer, buffer.length);
            socket.setSoTimeout(Math.max(1, timeoutMs));
            try {
                socket.receive(in);
            }
            catch (SocketTimeoutException e) {
                return -1;
            }
            // Anyone can send here, from any address they like: the MACs decide what counts
            return in.getLength();
        }

        @Override
        public void close() {
            socket.close();
        }
    }
}
